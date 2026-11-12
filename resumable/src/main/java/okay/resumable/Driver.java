package okay.resumable;

import java.util.function.Supplier;
import okay.rows.Comp;
import okay.rows.Effects;
import okay.rows.Op;

/** The boundary: runs a direct-style body; where it suspends, asks the operation at its place in the row, and
 * resumes the saved frames with the answer — the innermost first, each given what the one inside it returned, so
 * the Java stack holds one of them at a time. */
final class Driver {
  private Driver() {}

  interface Run {
    Object run() throws Suspend;
  }

  /** the saved frames, the innermost first; a tail is shared by every continuation taken under it */
  record Chain(Frame frame, Chain next, boolean finalizes) {
    Chain(Frame frame, Chain next) {
      this(frame, next, frame.finalizes() || next != null && next.finalizes());
    }
  }

  @SuppressWarnings("unchecked") // the row is types only: the program was built for the row the caller named
  static <A, R> Comp<A, R> program(Class<?>[] row, Run body) {
    return (Comp<A, R>) (Comp<?, ?>) Effects.defer(() -> start(row, body));
  }

  private static Comp<Object, Object> start(Class<?>[] row, Run body) {
    Object a;
    try {
      a = body.run();
    } catch (Capture c) {
      Resume.outermost(c);
      return suspended(row, c, null);
    } catch (Suspend s) {
      throw thrown(s);
    }
    return Effects.pure(a);
  }

  private static IllegalStateException thrown(Suspend s) {
    return new IllegalStateException("okay.resumable: the operation " + s + " was thrown, not performed");
  }

  private static Comp<Object, Object> suspended(Class<?>[] row, Capture c, Chain tail) {
    Chain k = tail;
    for (int i = c.frames.size() - 1; i >= 0; i--) k = new Chain(c.frames.get(i), k);
    Chain rest = k;
    Comp<Object, Object> ask = ask(c.op, place(row, c.op));
    if (rest.finalizes()) ask = finalizing(ask, rest);
    return ask.flatMap(x -> Effects.defer(() -> resume(row, rest, x, null)));
  }

  /** the operation's answer, and — if the rest is left unanswered — the {@code finally} blocks of its frames run.
   * The flag is per path: a path sets it and its finalizer, run right after, clears it */
  private static Comp<Object, Object> finalizing(Comp<Object, Object> ask, Chain rest) {
    boolean[] answered = {false};
    Supplier<Comp<Object, Object>> fin = () -> {
      if (!answered[0]) return unwind(rest);
      answered[0] = false;
      return Effects.pure(null);
    };
    return ask.<Object, Object>map(x -> {
      answered[0] = true;
      return x;
    }).ensuring(fin);
  }

  /** the frames re-entered one at a time, the innermost first: each is given the value the one inside it returned,
   * or what it threw */
  private static Comp<Object, Object> resume(Class<?>[] row, Chain k, Object x, Throwable thrown) {
    Object v = x;
    Throwable t = thrown;
    for (Chain c = k; c != null; c = c.next()) {
      try {
        v = enter(c.frame(), v, t);
        t = null;
      } catch (Capture cap) {
        return suspended(row, cap, c.next());
      } catch (Throwable e) {
        t = e;
        v = null;
      }
    }
    if (t != null) throw Driver.<RuntimeException>sneaky(t);
    return Effects.pure(v);
  }

  /** the frames left: each that a handler of its method covers re-entered with {@link Unwound} thrown at its call,
   * so its {@code finally} blocks run; a finalizer that fails does not stop the others */
  private static Comp<Object, Object> unwind(Chain k) {
    for (Chain c = k; c != null; c = c.next()) {
      if (!c.frame().finalizes()) continue;
      try {
        enter(c.frame(), null, Unwound.INSTANCE);
      } catch (Capture cap) {
        throw new IllegalStateException("okay.resumable: a finally block performed " + cap.op
            + " while its program was unwound: not supported");
      } catch (Throwable e) {
        // Unwound, as expected; or the finalizer's own failure, which ends this frame only
      }
    }
    return Effects.pure(null);
  }

  private static Object enter(Frame f, Object v, Throwable t) throws Throwable {
    return (Object) f.resumer.resume.invokeExact(f, v, t);
  }

  @SuppressWarnings("unchecked") // rethrown as it is: what the body threw leaves the program unchanged
  private static <T extends Throwable> T sneaky(Throwable t) throws T {
    throw (T) t;
  }

  @SuppressWarnings({"unchecked", "rawtypes"}) // the operation is of the row's effect at `place`: rows are types only
  private static Comp<Object, Object> ask(Object op, int place) {
    Comp c = Effects.perform((Op) op);
    for (int i = 0; i < place; i++) c = Effects.up(c);
    return c;
  }

  private static int place(Class<?>[] row, Object op) {
    Class<?> effect = op.getClass();
    while (effect.getSuperclass() != Suspend.class) effect = effect.getSuperclass();
    for (int i = 0; i < row.length; i++) if (row[i] == effect) return i;
    throw new IllegalStateException("okay.resumable: " + op + " is of " + effect.getName()
        + ", which is not in the program's row");
  }
}
