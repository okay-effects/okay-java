package okay.resumable;

import java.util.function.Supplier;
import okay.rows.Comp;
import okay.rows.Effects;
import okay.rows.Here;
import okay.rows.Op;

/** The boundary: runs a direct-style body; where it suspends, asks the operation at its place in the row, and
 * resumes the body with the answer. Three ways, by the handler the operation goes to, found when it is performed:
 * <ol>
 * <li>in place: answered at once, on the body's own stack ({@link Here}) — nothing suspends;
 * <li>general and declared {@code Once}, where the JVM's continuations can be used: the body runs in one
 * ({@link LoomRun}) and yields;
 * <li>otherwise: the frames are saved as data ({@link Capture}) and re-entered — the innermost first, each given
 * what the one inside it returned, so the Java stack holds one of them at a time.
 * </ol> */
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

  /** where a body runs, while it runs on this thread: the handlers in force, its row, its continuation if any */
  record Ctx(Here here, Class<?>[] row, LoomRun loom) {}

  static final ThreadLocal<Ctx> CTX = new ThreadLocal<>();

  /** {@link Resume#perform}: answered in place, or yielded, or the frames saved */
  static Object perform(Op<?, ?, ?> op) {
    Ctx c = CTX.get();
    if (c != null) {
      Object a = c.here().ask(op, place(c.row(), op));
      if (a != Here.ELSEWHERE) {
        if (a != Here.ONCE) return a;
        if (c.loom() != null) {
          Object y = c.loom().yield(op);
          if (y != LoomRun.NOT) return y;
        } else if (!Loom.AVAILABLE) Loom.tell();
      }
    }
    throw new Capture(op);
  }

  private static <T> T at(Ctx c, Supplier<T> f) {
    Ctx outer = CTX.get();
    CTX.set(c);
    try {
      return f.get();
    } finally {
      CTX.set(outer);
    }
  }

  @SuppressWarnings("unchecked") // the row is types only: the program was built for the row the caller named
  static <A, R> Comp<A, R> program(Class<?>[] row, Run body) {
    return (Comp<A, R>) (Comp<?, ?>) Here.here(h -> start(row, body, h));
  }

  private static Comp<Object, Object> start(Class<?>[] row, Run body, Here h) {
    if (Loom.AVAILABLE) return step(row, new LoomRun(body), h);
    Object a;
    try {
      a = at(new Ctx(h, row, null), () -> {
        try {
          return body.run();
        } catch (Suspend s) {
          throw thrown(s);
        }
      });
    } catch (Capture c) {
      Resume.outermost(c);
      return suspended(row, c, null);
    }
    return Effects.pure(a);
  }

  private static IllegalStateException thrown(Suspend s) {
    return new IllegalStateException("okay.resumable: the operation " + s + " was thrown, not performed");
  }

  // ---- 2: on the JVM's continuation

  private static Comp<Object, Object> step(Class<?>[] row, LoomRun r, Here h) {
    at(new Ctx(h, row, r), () -> {
      r.run();
      return null;
    });
    if (r.cont.isDone()) {
      if (r.capture != null) {
        Resume.outermost(r.capture);
        return suspended(row, r.capture, null);
      }
      if (r.failure instanceof Suspend s) throw thrown(s);
      if (r.failure != null) throw sneaky(r.failure);
      return Effects.pure(r.value);
    }
    Object op = r.pending;
    r.pending = null;
    boolean[] used = {false};
    Comp<Object, Object> ask = finalizing(ask(op, place(row, op)), () -> Here.here(h2 -> {
      if (used[0]) return Effects.pure(null);
      used[0] = true;
      r.thrown = Unwound.INSTANCE;
      at(new Ctx(h2, row, r), () -> {
        r.run();
        return null;
      });
      if (!r.cont.isDone() || r.capture != null)
        throw new IllegalStateException("okay.resumable: a finally block performed an operation while its "
            + "program was unwound: not supported");
      return Effects.pure(null);
    }));
    return ask.flatMap(x -> Here.here(h2 -> {
      if (used[0]) throw new IllegalStateException("okay.resumable: the handler of " + op + " declares Once but "
          + "resumed its rest again; the JVM's continuation goes on once. A handler that resumes more than once "
          + "must not implement Once: its programs' frames are then saved as data");
      used[0] = true;
      r.answer = x;
      return step(row, r, h2);
    }));
  }

  // ---- 3: frames saved as data

  static Comp<Object, Object> suspended(Class<?>[] row, Capture c, Chain tail) {
    Chain k = tail;
    for (int i = c.frames.size() - 1; i >= 0; i--) k = new Chain(c.frames.get(i), k);
    Chain rest = k;
    Comp<Object, Object> ask = ask(c.op, place(row, c.op));
    if (rest.finalizes()) ask = finalizing(ask, () -> Here.here(h -> unwind(row, rest, h)));
    return ask.flatMap(x -> Here.here(h -> resume(row, rest, x, h)));
  }

  /** the operation's answer, and — if the rest is left unanswered — {@code unwind}. The flag is per path: a path
   * sets it, and its finalizer, run right after, clears it */
  private static Comp<Object, Object> finalizing(Comp<Object, Object> ask, Supplier<Comp<Object, Object>> unwind) {
    boolean[] answered = {false};
    Supplier<Comp<Object, Object>> fin = () -> {
      if (!answered[0]) return unwind.get();
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
  private static Comp<Object, Object> resume(Class<?>[] row, Chain k, Object x, Here h) {
    Ctx ctx = new Ctx(h, row, null);
    Ctx outer = CTX.get();
    CTX.set(ctx);
    try {
      Object v = x;
      Throwable t = null;
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
    } finally {
      CTX.set(outer);
    }
  }

  /** the frames left: each that a handler of its method covers re-entered with {@link Unwound} thrown at its call,
   * so its {@code finally} blocks run; a finalizer that fails does not stop the others */
  private static Comp<Object, Object> unwind(Class<?>[] row, Chain k, Here h) {
    return at(new Ctx(h, row, null), () -> {
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
    });
  }

  private static Object enter(Frame f, Object v, Throwable t) throws Throwable {
    return (Object) f.resumer.resume.invokeExact(f, v, t);
  }

  @SuppressWarnings("unchecked") // rethrown as it is: what the body threw leaves the program unchanged
  static <T extends Throwable> T sneaky(Throwable t) throws T {
    throw (T) t;
  }

  @SuppressWarnings({"unchecked", "rawtypes"}) // the operation is of the row's effect at `place`: rows are types only
  private static Comp<Object, Object> ask(Object op, int place) {
    Comp c = Effects.perform((Op) op);
    for (int i = 0; i < place; i++) c = Effects.up(c);
    return c;
  }

  /** the effect of an operation: the class right under Suspend */
  private static final ClassValue<Class<?>> EFFECT = new ClassValue<>() {
    @Override
    protected Class<?> computeValue(Class<?> type) {
      Class<?> effect = type;
      while (effect.getSuperclass() != Suspend.class) effect = effect.getSuperclass();
      return effect;
    }
  };

  static int place(Class<?>[] row, Object op) {
    Class<?> effect = EFFECT.get(op.getClass());
    for (int i = 0; i < row.length; i++) if (row[i] == effect) return i;
    throw new IllegalStateException("okay.resumable: " + op + " is of " + effect.getName()
        + ", which is not in the program's row");
  }
}
