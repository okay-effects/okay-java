package okay.resumable;

import java.util.function.Function;
import java.util.function.Supplier;
import okay.rows.Audit;
import okay.rows.Comp;
import okay.rows.Effects;
import okay.rows.Here;
import okay.rows.NoHandler;
import okay.rows.Op;
import okay.rows.Sensitive;

/** The boundary: runs a direct-style body; where it suspends, asks the operation at its place in the row, and
 * resumes the body with the answer. Three ways, by the handler the operation goes to, found when it is performed:
 * <ol>
 * <li>in place: answered at once, on the body's own stack ({@link Here}) — nothing suspends;
 * <li>general and declared {@code Once}, or written in direct style: the handler's clause is called from the body's
 * stack, and its answer returned if it resumes at once (a direct-style handler that may resume many times: in tail
 * position). Else, where the JVM's continuations can be used and the handler is declared {@code Once}, the body runs
 * in one ({@link LoomRun}) and yields; otherwise its frames are saved as in 3, for the clause's answer to resume;
 * <li>otherwise: the frames are saved as data ({@link Capture}) and re-entered — the innermost first, each given
 * what the one inside it returned, so the Java stack holds one of them at a time.
 * </ol> */
final class Driver {
  private Driver() {}

  interface Run {
    Object run() throws Exception;
  }

  /** A PROGRAM while it runs: its row. A direct-style clause's is its {@link Direct.K}, so that the rest is resumed
   * only by its own clause */
  static class Prog {
    final Class<?>[] row;

    Prog(Class<?>[] row) {
      this.row = row;
    }
  }

  /** where a program runs, while it runs on this thread: the handlers in force, the program, its continuation if
   * any */
  record Ctx(Here here, Prog prog, LoomRun loom) {}

  static final ThreadLocal<Ctx> CTX = new ThreadLocal<>();

  /** for the module's tests (-Dokay.resumable.trace=true): how many operations went each way — 0 answered in place,
   * 1 answered by a clause on the body's stack, 2 yielded, 3 frames saved. Off, the counting is compiled away */
  static final boolean TRACE = Boolean.getBoolean("okay.resumable.trace");
  static final int[] TAKEN = new int[4];
  /** for the tests: how many times a direct-style clause's k.resume was found in tail position, nothing kept */
  static int TAILS;

  /** {@link Resume#perform}: answered in place, or by the clause of a handler declared {@code Once} or written in
   * direct style on this stack, or yielded; or a capture returned, for the frames to be saved */
  static Object perform(Op<?, ?, ?> op) {
    // one that is both, through a raw type: sensitive, so it passes its audit
    if (op instanceof Sensitive.Op<?, ?, ?> s) return performSensitive(s);
    Ctx c = CTX.get();
    if (c != null) {
      int place = place(c.prog().row, op);
      Object a = c.here().ask(op, place);
      if (a != Here.ELSEWHERE) {
        if (a == Here.EAGER) return here(c, op, place, false);
        if (a != Here.ONCE) {
          if (TRACE) TAKEN[0]++;
          return a;
        }
        LoomRun r = c.loom();
        if (r != null && r.current()) return once(c, r, op, place);
        return here(c, op, place, true);
      }
    }
    return new Capture(op);
  }

  /** {@link Resume#performSensitive}: a sensitive operation passes its audit, so it is never answered in place —
   * its audit's clause is called on the body's stack where it declares {@code Once}; else the frames are saved */
  static Object performSensitive(Sensitive.Op<?, ?, ?> op) {
    Ctx c = CTX.get();
    if (c != null) {
      int place = place(c.prog().row, op);
      if (c.here().kind(op, place) == Here.ONCE) {
        LoomRun r = c.loom();
        if (r != null && r.current()) return once(c, r, op, place);
        return here(c, op, place, true);
      }
    }
    return new Capture(op);
  }

  /** 2: the handler's clause called from the body's stack. Resumed at once, as most are, its answer is returned and
   * nothing suspends; else the body yields, and the boundary goes on with the clause's program. If the yield cannot
   * be made, the frames are saved, and the answer resumes them */
  private static Object once(Ctx c, LoomRun r, Object op, int place) {
    if (!r.guarded) {
      // the program's one guard, put before the first rest a clause may keep: if a rest is left unanswered while
      // the body waits, its frames are unwound. Not put for a program that never asks such a handler
      r.guarded = true;
      Prog p = c.prog();
      c.here().ensure(() -> Here.here(h -> unwound(p, r, h)));
    }
    Answer later = new Answer(c.prog(), r, ++r.asked, op);
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
    return new Capture(op, p.next(), later);
  }

  /** 2 with no continuation to yield: the clause of a handler declared {@code Once} ({@code once}), or of one
   * written in direct style, called from this stack. Its answer returned if it resumes at once — a direct-style one
   * that may resume many times, in tail position; else the frames are saved, the clause's program goes on, and its
   * answer resumes them, as many times as it is given */
  private static Object here(Ctx c, Object op, int place, boolean once) {
    Many later = new Many(c.prog(), op);
    Object x = op instanceof Sensitive.Op<?, ?, ?> s ? c.here().resumeHere(s, place, later, later, once)
        : c.here().resumeHere((Op<?, ?, ?>) op, place, later, later, once);
    if (!(x instanceof Here.Pending p)) {
      if (TRACE) TAKEN[1]++;
      return x;
    }
    if (once && !Loom.AVAILABLE) Loom.tell();
    return new Capture(op, p.next(), later);
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
    Prog p = new Prog(row);
    if (!Loom.AVAILABLE) return (Comp<A, R>) (Comp<?, ?>) Here.here(h -> start(p, body, h, RUN));
    // a continuation for each run of the program
    return (Comp<A, R>) (Comp<?, ?>) Here.here(h -> step(p, new LoomRun(p, body, RUN), h));
  }

  /** how the boundary enters the outermost frame: a body's run, a direct-style clause's on or ret */
  record Entry(String name, String desc) {}

  static final Entry RUN = new Entry("run", "()Ljava/lang/Object;");
  static final Entry ON = new Entry("on", "(Ljava/lang/Object;Lokay/resumable/Direct$K;)Ljava/lang/Object;");
  static final Entry RET = new Entry("ret", "(Ljava/lang/Object;)Ljava/lang/Object;");

  /** A DIRECT-STYLE CLAUSE as a program of the row outside its handler, {@code p}'s: run as a body is, on saved
   * frames — not on the JVM's continuation: one made for each clause, run inside the body's, cost 20 times what
   * the saved frames do (bench.d/2026-11-13_resumable-3.txt). Its operations of a handler declared Once are asked
   * from its stack all the same; its {@code k.resume} saves its frames while the rest of the handled program runs */
  @SuppressWarnings("unchecked") // the row is types only: the handler was made for the row its caller named
  static <B, R> Comp<B, R> clause(Prog p, Run body, Entry entry) {
    return (Comp<B, R>) (Comp<?, ?>) Here.here(h -> start(p, body, h, entry));
  }

  /** what a clause's {@code k.resume} or {@code k.unwind} asks: the rest of the handled program, as okay.rows gives
   * it, run outside the handler — its value the clause's answer */
  record Await(Comp<Object, Object> rest, boolean resumes) {}

  /** whether {@code k} may be used here: by its own clause only, while it runs, on its thread — refused otherwise,
   * with what was done */
  static void valid(Direct.K<?> k) {
    Ctx c = CTX.get();
    if (c != null && c.prog() == k) return;
    String what = "okay.resumable: the rest k of " + k.asked + " was used ";
    String rule = ": a direct-style k is valid only in its own clause, while that clause runs, on the thread that runs"
        + " it — the clause may resume it once, many times or never, but nothing else may";
    if (c == null) throw new IllegalStateException(what + "where no program runs — on another thread, or after its "
        + "clause and program ended (kept in a field?)" + rule);
    if (c.prog() instanceof Direct.K<?> other && other.handler == k.handler)
      throw new IllegalStateException(what + "by a later clause of its handler, after its own clause returned (kept "
          + "in a field?); use the k this clause is given" + rule);
    if (c.prog() instanceof Direct.K<?>) throw new IllegalStateException(what + "by a clause of another handler"
        + rule);
    throw new IllegalStateException(what + "by a program's body, not by its handler's clause (a handler reached "
        + "through a reference and called directly?)" + rule);
  }

  /** {@code rest} awaited by the clause running here, whose {@code k} is {@link #valid}: its frames saved, the rest
   * run, they are resumed with its value */
  @SuppressWarnings("unchecked") // the rest's value is the clause's B, its row the handler's: rows are types only
  static <T> T await(Comp<?, ?> rest) {
    throw new Capture(new Await((Comp<Object, Object>) rest, true));
  }

  /** A SUB-PROGRAM RUN AS A PART OF THE PROGRAM THAT CALLS IT ({@code Direct.row(..).embed}), its handlers inside it:
   * the caller's frames saved, the sub-program run in their place — no nested run: its operations that leave it go
   * to the program's handlers — and they are resumed with its value. Its row, {@code row}, is the program's or some of
   * its places in the same order: a place put for each other one. Refused where no program runs */
  @SuppressWarnings("unchecked") // the value is the sub-program's A, as the caller's embed says
  static <T> T embed(Class<?>[] row, Comp<?, ?> sub) {
    Ctx c = CTX.get();
    if (c == null) throw new IllegalStateException("okay.resumable: Direct.row(..).embed was called where no program "
        + "runs: it runs a sub-program as a part of the program that calls it — call it from a program's body or a "
        + "direct-style clause; a program by itself is run by Effects.run");
    throw new Capture(new Await(fitted(row, c.prog().row, (Comp<Object, Object>) sub), false));
  }

  /** {@code sub}, of the places {@code row}, made a program of {@code in}: a place put for each place of {@code in}
   * that {@code row} has not, so that each of its operations goes to its own effect's place */
  private static Comp<Object, Object> fitted(Class<?>[] row, Class<?>[] in, Comp<Object, Object> sub) {
    int j = 0;
    for (int i = 0; i < in.length && j < row.length; i++) {
      if (in[i] == row[j]) j++;
      else sub = Here.lifted(sub, i);
    }
    if (j < row.length) throw new IllegalStateException("okay.resumable: Direct.row(" + names(row) + ").embed was "
        + "called by a program of the row (" + names(in) + "): the sub-program's effects must be the program's, in "
        + "the same order");
    return sub;
  }

  private static String names(Class<?>[] row) {
    StringBuilder s = new StringBuilder();
    for (Class<?> c : row) s.append(s.isEmpty() ? "" : ", ").append(c.getSimpleName()).append(".class");
    return s.toString();
  }

  private static Comp<Object, Object> start(Prog p, Run body, Here h, Entry entry) {
    Object a;
    // the body called from here, no frame between: a capture crosses as few as it can
    Ctx outer = CTX.get();
    CTX.set(new Ctx(h, p, null));
    try {
      a = body.run();
    } catch (Capture c) {
      Resume.outermost(c, entry);
      return suspended(p, c, null, null);
    } catch (Suspend | SensitiveSuspend s) {
      throw thrown(s);
    } catch (Exception e) {
      throw sneaky(nested(e));
    } finally {
      CTX.set(outer);
    }
    return Effects.pure(a);
  }

  /** what leaves a body: an operation that reached no handler ({@code NoHandler}) came out of a program the body ran
   * by itself, to its end, by a top-level runner — a nested run, which sees only the handlers inside it. Said so */
  private static Throwable nested(Throwable t) {
    if (!(t instanceof NoHandler n)) return t;
    return new IllegalStateException("okay.resumable: " + n.op + " reached no handler: the program that asked it was "
        + "run inside a program's body, which ran a program by a top-level runner (Effects.run, Effects.step, a std "
        + "effect's runner): a nested run sees only the handlers inside it, not the program's. Run the part with "
        + "Direct.row(..).embed(..) instead, its handlers inside it, or perform the effect in the body itself", n);
  }

  private static IllegalStateException thrown(Throwable s) {
    return new IllegalStateException("okay.resumable: the operation " + s + " was thrown, not performed");
  }

  // ---- 2: on the JVM's continuation

  /** the body run on to its end or its next yield, then the program it yielded */
  static Comp<Object, Object> step(Prog p, LoomRun r, Here h) {
    at(new Ctx(h, p, r), r);
    if (r.cont.isDone()) {
      Capture c = r.capture;
      if (c != null) {
        Resume.outermost(c, r.entry);
        return suspended(p, c, null, r);
      }
      if (r.failure instanceof Suspend || r.failure instanceof SensitiveSuspend) throw thrown(r.failure);
      if (r.failure != null) throw sneaky(nested(r.failure));
      return Effects.pure(r.value);
    }
    Comp<Object, Object> next = r.pending;
    r.pending = null;
    return next;
  }

  /** WHAT ANSWERS A REST TAKEN FROM THE BODY'S STACK ({@code Here.resumeHere}) whose clause did not resume at once:
   * the frames saved for it, if the body could not wait on its own stack */
  abstract static class Later implements Function<Object, Comp<Object, Object>> {
    Frame frames;

    /** the frames saved, {@code r} the body's continuation if it ran in one */
    void saved(Frame f, LoomRun r) {
      frames = f;
    }
  }

  /** the answer to the {@code n}th operation asked: given once — a handler declared {@code Once} that resumes
   * again is refused */
  static final class Answer extends Later {
    final Prog p;
    final LoomRun r;
    final int n;
    final Object op;

    Answer(Prog p, LoomRun r, int n, Object op) {
      this.p = p;
      this.r = r;
      this.n = n;
      this.op = op;
    }

    @Override
    void saved(Frame f, LoomRun r) {
      frames = f;
      this.r.waiting = f;
    }

    @Override
    public Comp<Object, Object> apply(Object x) {
      if (r.answered >= n) throw twice(op);
      r.answered = n;
      Frame k = frames;
      if (k == null) {
        r.answer = x;
        return r.next;
      }
      r.waiting = null;
      return Here.here(h -> resume(p, k, x, h));
    }
  }

  static IllegalStateException twice(Object op) {
    return new IllegalStateException("okay.resumable: the handler of " + op + " declares Once but resumed its rest "
        + "again; the JVM's continuation goes on once. A handler that resumes more than once must not implement Once: "
        + "its programs' frames are then saved as data");
  }

  /** the answer to an operation asked from the body's stack with no continuation to yield: the frames saved, resumed
   * with each answer given — many times, for a handler that may. Answered at once, nothing was saved: a handler
   * declared Once that resumes again is refused. Its {@link #get}: the frames' finalizers, when the rest is unwound */
  static final class Many extends Later implements Supplier<Comp<Object, Object>> {
    final Prog p;
    final Object op;

    Many(Prog p, Object op) {
      this.p = p;
      this.op = op;
    }

    @Override
    public Comp<Object, Object> apply(Object x) {
      Frame k = frames;
      if (k == null) throw twice(op);
      return Here.here(h -> resume(p, k, x, h));
    }

    @Override
    public Comp<Object, Object> get() {
      Frame k = frames;
      return k == null || !k.finalizes ? Effects.pure(null) : Here.here(h -> unwind(p, k, h));
    }
  }

  /** the program's guard: run when the program is left, by its end or unwound. Unwound while the body waits for an
   * answer, its frames are left: by {@link Unwound} thrown at the yield, or re-entered if they were saved */
  private static Comp<Object, Object> unwound(Prog p, LoomRun r, Here h) {
    r.answered = r.asked;
    Frame k = r.waiting;
    if (k != null) {
      r.waiting = null;
      return k.finalizes ? unwind(p, k, h) : Effects.pure(null);
    }
    if (r.cont.isDone()) return Effects.pure(null);
    r.thrown = Unwound.INSTANCE;
    at(new Ctx(h, p, r), r);
    if (!r.cont.isDone() || r.capture != null)
      throw new IllegalStateException("okay.resumable: a finally block performed an operation while its program "
          + "was unwound: not supported");
    return Effects.pure(null);
  }

  // ---- 3: frames saved as data

  /** the frames of {@code c} saved, linked to {@code tail}, and the program to go on with: the operation asked, its
   * answer resuming them; or the program of a clause already called, where one was ({@code c.next}) */
  static Comp<Object, Object> suspended(Prog p, Capture c, Frame tail, LoomRun r) {
    if (TRACE) TAKEN[3]++;
    Frame rest = c.link(tail);
    if (c.next != null) {
      // a clause called from the body's stack that did not resume at once: the frames wait for its answer
      c.later.saved(rest, r);
      return c.next;
    }
    Comp<Object, Object> ask;
    if (c.op instanceof Await a) {
      // a clause's k.resume: in tail position, the clause's program is the resumption itself — nothing waits
      // for it, so a handler that resumes so adds nothing for each operation
      boolean tails = rest.tails;
      if (a.resumes() && p instanceof Direct.K<?> k) k.handler.resumed(tails);
      if (tails) {
        if (TRACE) TAILS++;
        return a.rest();
      }
      ask = a.rest();
    } else ask = ask(c.op, place(p.row, c.op));
    if (rest.finalizes) ask = finalizing(ask, () -> Here.here(h -> unwind(p, rest, h)));
    return ask.flatMap(x -> Here.here(h -> resume(p, rest, x, h)));
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
  private static Comp<Object, Object> resume(Prog p, Frame k, Object x, Here h) {
    Ctx ctx = new Ctx(h, p, null);
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
          return suspended(p, cap, f.next, null);
        } catch (Throwable e) {
          t = e;
          v = null;
          continue;
        }
        if (v instanceof Capture cap) return suspended(p, cap, f.next, null);
      }
      if (t != null) throw Driver.<RuntimeException>sneaky(nested(t));
      return Effects.pure(v);
    } finally {
      CTX.set(outer);
    }
  }

  /** the frames left: each that a handler of its method covers re-entered with {@link Unwound} thrown at its call,
   * so its {@code finally} blocks run; a finalizer that fails does not stop the others */
  private static Comp<Object, Object> unwind(Prog p, Frame k, Here h) {
    return at(new Ctx(h, p, null), () -> {
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
