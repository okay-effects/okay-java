package okay.resumable;

import java.util.function.Function;
import java.util.function.Supplier;
import okay.rows.Audit;
import okay.rows.Comp;
import okay.rows.Effects;
import okay.rows.Here;
import okay.rows.Op;
import okay.rows.Sensitive;

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
    Object run() throws Exception;
  }

  /** where a body runs, while it runs on this thread: the handlers in force, its row, its continuation if any */
  record Ctx(Here here, Class<?>[] row, LoomRun loom) {}

  static final ThreadLocal<Ctx> CTX = new ThreadLocal<>();

  /** for the module's tests (-Dokay.resumable.trace=true): how many operations went each way — 0 answered in place,
   * 1 answered by a clause on the body's stack, 2 yielded, 3 frames saved. Off, the counting is compiled away */
  static final boolean TRACE = Boolean.getBoolean("okay.resumable.trace");
  static final int[] TAKEN = new int[4];

  /** {@link Resume#perform}: answered in place, or by the clause of a handler declared {@code Once} on this stack,
   * or yielded, or the frames saved */
  static Object perform(Op<?, ?, ?> op) {
    // one that is both, through a raw type: sensitive, so it passes its audit
    if (op instanceof Sensitive.Op<?, ?, ?> s) return performSensitive(s);
    Ctx c = CTX.get();
    if (c != null) {
      int place = place(c.row(), op);
      Object a = c.here().ask(op, place);
      if (a != Here.ELSEWHERE) {
        if (a != Here.ONCE) {
          if (TRACE) TAKEN[0]++;
          return a;
        }
        LoomRun r = c.loom();
        if (r != null && r.current()) return once(c, r, op, place);
        if (!Loom.AVAILABLE) Loom.tell();
      }
    }
    throw new Capture(op);
  }

  /** {@link Resume#performSensitive}: a sensitive operation passes its audit, so it is never answered in place —
   * its audit's clause is called on the body's stack where it declares {@code Once} and the body runs on the JVM's
   * continuation; else the frames are saved */
  static Object performSensitive(Sensitive.Op<?, ?, ?> op) {
    Ctx c = CTX.get();
    if (c != null) {
      int place = place(c.row(), op);
      if (c.here().kind(op, place) == Here.ONCE) {
        LoomRun r = c.loom();
        if (r != null && r.current()) return once(c, r, op, place);
        if (!Loom.AVAILABLE) Loom.tell();
      }
    }
    throw new Capture(op);
  }

  /** 2: the handler's clause called from the body's stack. Resumed at once, as most are, its answer is returned and
   * nothing suspends; else the body yields, and the boundary goes on with the clause's program. If the yield cannot
   * be made, the frames are saved, and the answer resumes them */
  private static Object once(Ctx c, LoomRun r, Object op, int place) {
    Answer later = new Answer(c.row(), r, ++r.asked, op);
    // the cast: an operation that is not sensitive came here from perform, which takes an Op
    Object x = op instanceof Sensitive.Op<?, ?, ?> s ? c.here().resumeHere(s, place, later)
        : c.here().resumeHere((Op<?, ?, ?>) op, place, later);
    if (!(x instanceof Here.Pending p)) {
      r.answered = later.n;
      if (TRACE) TAKEN[1]++;
      return x;
    }
    if (TRACE) TAKEN[2]++;
    Object y = r.yield(p.next());
    if (y != LoomRun.NOT) return y;
    if (TRACE) TAKEN[2]--;
    throw new Capture(op, p.next(), later);
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
    if (!Loom.AVAILABLE) return (Comp<A, R>) (Comp<?, ?>) Here.here(h -> start(row, body, h, RUN));
    // a continuation for each run of the program, and its one guard around it: if the rest is left unanswered
    // while the body waits, its frames are unwound
    return (Comp<A, R>) (Comp<?, ?>) Effects.defer(() -> {
      LoomRun r = new LoomRun(row, body);
      return r.next.ensuring(() -> Here.here(h -> unwound(row, r, h)));
    });
  }

  /** how the boundary enters the outermost frame: a body's run, a direct-style clause's on or ret */
  record Entry(String name, String desc) {}

  static final Entry RUN = new Entry("run", "()Ljava/lang/Object;");
  static final Entry ON = new Entry("on", "(Ljava/lang/Object;Lokay/resumable/Direct$K;)Ljava/lang/Object;");
  static final Entry RET = new Entry("ret", "(Ljava/lang/Object;)Ljava/lang/Object;");

  /** A DIRECT-STYLE CLAUSE as a program of the row outside its handler: run as a body is, on saved frames — its
   * {@code k.resume} suspends it while the rest of the handled program runs */
  @SuppressWarnings("unchecked") // the row is types only: the handler was made for the row its caller named
  static <B, R> Comp<B, R> clause(Class<?>[] row, Run body, Entry entry) {
    return (Comp<B, R>) (Comp<?, ?>) Here.here(h -> start(row, body, h, entry));
  }

  /** what a clause's {@code k.resume} or {@code k.unwind} asks: the rest of the handled program, as okay.rows gives
   * it, run outside the handler — its value the clause's answer */
  record Await(Comp<Object, Object> rest) {}

  /** {@code rest} awaited by the clause running here, made for the row {@code row}: its frames saved, the rest run,
   * they are resumed with its value. Refused outside a clause of that handler */
  @SuppressWarnings("unchecked") // the rest's value is the clause's B, its row the handler's: rows are types only
  static <T> T await(Class<?>[] row, Comp<?, ?> rest) {
    Ctx c = CTX.get();
    if (c == null || c.row() != row) throw new IllegalStateException("okay.resumable: the rest of a handled "
        + "program resumed outside a clause of its handler: k.resume is called by the handler's clauses only");
    throw new Capture(new Await((Comp<Object, Object>) rest));
  }

  private static Comp<Object, Object> start(Class<?>[] row, Run body, Here h, Entry entry) {
    Object a;
    try {
      a = at(new Ctx(h, row, null), () -> {
        try {
          return body.run();
        } catch (Suspend | SensitiveSuspend s) {
          throw thrown(s);
        } catch (Exception e) {
          throw sneaky(e);
        }
      });
    } catch (Capture c) {
      Resume.outermost(c, entry);
      return suspended(row, c, null);
    }
    return Effects.pure(a);
  }

  private static IllegalStateException thrown(Throwable s) {
    return new IllegalStateException("okay.resumable: the operation " + s + " was thrown, not performed");
  }

  // ---- 2: on the JVM's continuation

  /** the body run on to its end or its next yield, then the program it yielded */
  static Comp<Object, Object> step(Class<?>[] row, LoomRun r, Here h) {
    at(new Ctx(h, row, r), r);
    if (r.cont.isDone()) {
      Capture c = r.capture;
      if (c != null) {
        Resume.outermost(c, RUN);
        if (c.next == null) return suspended(row, c, null);
        // a yield that could not be made: the frames wait for the answer the clause's program will give
        if (TRACE) TAKEN[3]++;
        c.later.frames = r.waiting = c.link(null);
        return c.next;
      }
      if (r.failure instanceof Suspend || r.failure instanceof SensitiveSuspend) throw thrown(r.failure);
      if (r.failure != null) throw sneaky(r.failure);
      return Effects.pure(r.value);
    }
    Comp<Object, Object> next = r.pending;
    r.pending = null;
    return next;
  }

  /** the answer to the {@code n}th operation asked: given once — a handler declared {@code Once} that resumes
   * again is refused */
  static final class Answer implements Function<Object, Comp<Object, Object>> {
    final Class<?>[] row;
    final LoomRun r;
    final int n;
    final Object op;
    /** the frames saved for it, where the body could not yield */
    Frame frames;

    Answer(Class<?>[] row, LoomRun r, int n, Object op) {
      this.row = row;
      this.r = r;
      this.n = n;
      this.op = op;
    }

    @Override
    public Comp<Object, Object> apply(Object x) {
      if (r.answered >= n) throw new IllegalStateException("okay.resumable: the handler of " + op + " declares Once "
          + "but resumed its rest again; the JVM's continuation goes on once. A handler that resumes more than once "
          + "must not implement Once: its programs' frames are then saved as data");
      r.answered = n;
      Frame k = frames;
      if (k == null) {
        r.answer = x;
        return r.next;
      }
      r.waiting = null;
      return Here.here(h -> resume(row, k, x, h));
    }
  }

  /** the program's guard: run when the program is left, by its end or unwound. Unwound while the body waits for an
   * answer, its frames are left: by {@link Unwound} thrown at the yield, or re-entered if they were saved */
  private static Comp<Object, Object> unwound(Class<?>[] row, LoomRun r, Here h) {
    r.answered = r.asked;
    Frame k = r.waiting;
    if (k != null) {
      r.waiting = null;
      return k.finalizes ? unwind(row, k, h) : Effects.pure(null);
    }
    if (r.cont.isDone()) return Effects.pure(null);
    r.thrown = Unwound.INSTANCE;
    at(new Ctx(h, row, r), r);
    if (!r.cont.isDone() || r.capture != null)
      throw new IllegalStateException("okay.resumable: a finally block performed an operation while its program "
          + "was unwound: not supported");
    return Effects.pure(null);
  }

  // ---- 3: frames saved as data

  static Comp<Object, Object> suspended(Class<?>[] row, Capture c, Frame tail) {
    if (TRACE) TAKEN[3]++;
    Frame rest = c.link(tail);
    Comp<Object, Object> ask;
    if (c.op instanceof Await a) {
      // a clause's k.resume: in tail position, the clause's program is the resumption itself — nothing waits
      // for it, so a handler that resumes so adds nothing for each operation
      if (rest.tails) return a.rest();
      ask = a.rest();
    } else ask = ask(c.op, place(row, c.op));
    if (rest.finalizes) ask = finalizing(ask, () -> Here.here(h -> unwind(row, rest, h)));
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
   * or what it threw. A frame that suspends again returns its capture */
  private static Comp<Object, Object> resume(Class<?>[] row, Frame k, Object x, Here h) {
    Ctx ctx = new Ctx(h, row, null);
    Ctx outer = CTX.get();
    CTX.set(ctx);
    try {
      Object v = x;
      Throwable t = null;
      for (Frame f = k; f != null; f = f.next) {
        try {
          v = enter(f, v, t);
          t = null;
        } catch (Capture cap) {
          return suspended(row, cap, f.next);
        } catch (Throwable e) {
          t = e;
          v = null;
          continue;
        }
        if (v instanceof Capture cap) return suspended(row, cap, f.next);
      }
      if (t != null) throw Driver.<RuntimeException>sneaky(t);
      return Effects.pure(v);
    } finally {
      CTX.set(outer);
    }
  }

  /** the frames left: each that a handler of its method covers re-entered with {@link Unwound} thrown at its call,
   * so its {@code finally} blocks run; a finalizer that fails does not stop the others */
  private static Comp<Object, Object> unwind(Class<?>[] row, Frame k, Here h) {
    return at(new Ctx(h, row, null), () -> {
      for (Frame f = k; f != null; f = f.next) {
        if (!f.covered()) continue;
        Object v;
        try {
          v = enter(f, null, Unwound.INSTANCE);
        } catch (Capture cap) {
          v = cap;
        } catch (Throwable e) {
          // Unwound, as expected; or the finalizer's own failure, which ends this frame only
          continue;
        }
        if (v instanceof Capture cap) throw new IllegalStateException("okay.resumable: a finally block performed "
            + cap.op + " while its program was unwound: not supported");
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
    Comp c = op instanceof Sensitive.Op s ? Audit.perform(s) : Effects.perform((Op) op);
    for (int i = 0; i < place; i++) c = Effects.up(c);
    return c;
  }

  /** the effect of an operation: the class right under Suspend or SensitiveSuspend */
  private static final ClassValue<Class<?>> EFFECT = new ClassValue<>() {
    @Override
    protected Class<?> computeValue(Class<?> type) {
      Class<?> effect = type;
      while (effect.getSuperclass() != Suspend.class && effect.getSuperclass() != SensitiveSuspend.class)
        effect = effect.getSuperclass();
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
