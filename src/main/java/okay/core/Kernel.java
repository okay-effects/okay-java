package okay.core;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import okay.core.Machine.Meta;
import okay.core.Machine.Run;
import okay.core.Machine.Seg;
import okay.core.Nodes.Frames;
import okay.core.Nodes.Handle;
import okay.core.Nodes.Return;
import okay.kernel.Aborting;
import okay.kernel.Answering;
import okay.kernel.Answers;
import okay.kernel.Computation;
import okay.kernel.Effects;
import okay.kernel.Fn;
import okay.kernel.General;
import okay.kernel.Handler;
import okay.kernel.Op;
import okay.kernel.Prog;
import okay.kernel.Program;
import okay.kernel.Resume;
import okay.kernel.Returning;
import okay.kernel.Step;
import okay.kernel.Thunk;
import okay.kernel.Type;

/**
 * OKAY-JAVA AS AN IMPLEMENTATION OF OKAY-KERNEL ({@code docs/kernel.md}): {@link Effects} on {@link Eff}'s own nodes,
 * the kernel's operations run by okay-java's machine — no wrapper, no translation. Its semantics are okay-kernel's
 * reference machine's ({@code Steps}), checked by okay-kernel's TCK.
 *
 * <ul>
 *   <li>A kernel program is an {@code Eff}: {@code pure}, {@code flatMap}, {@code delay} its nodes, a bind's kernel
 *       function adapted by one closure.
 *   <li>A kernel handler, {@code dollar}, {@code recover}, {@code ensuring}, {@code uncancelable}, an imported
 *       program's operation, an export's root: each okay-java's {@link Handle}, a fresh prompt, the prompt carrying
 *       what it is for (a FRAME, below). okay-java's own handlers have none.
 *   <li>AN OPERATION GOES TO THE NEAREST HANDLER OF ITS EFFECT IN FORCE WHERE IT RUNS: the machine walks its own stack
 *       of delimiters to the first handler frame of the operation's {@code Type}. A resumed {@code k} puts its
 *       delimiters in front of those where it is resumed, so the lookup is dynamic by construction. By shape:
 *       {@link Answering} answered in place, no capture; {@link General} a capture to the handler's prompt, {@code k}
 *       okay-java's continuation (a kernel {@link Fn} itself), the clause in the handler's place; {@link Aborting} an
 *       abort unwinding to the handler. {@link Returning}: the return a value.
 *   <li>{@code shift0}: a capture to the nearest delimiter whose frame is a handler's or a dollar's.
 *   <li>FAILURES: every function of the program's that the kernel calls runs in a {@code try} (a bind's, a map's, a
 *       delay's, a return — {@code Returning.answer} too — a clause, a body's {@code Program.in}, an answering
 *       handler's {@code value}, the host's, another implementation's steps). Its exception becomes a node
 *       ({@link Fail}) that the machine steps where the function was called, with the stack as it is there; stepped,
 *       it UNWINDS: to the nearest delimiter it concerns — {@code recover} answers it, {@code ensuring} runs its
 *       finalizer and fails on, an imported program's operation gives it to that program, the root makes it the step
 *       {@code Failed}. An abort and a cancellation unwind the same way, and no {@code recover} takes them. So
 *       okay-java's own paths — its run loop, its captures and resumptions — are not touched: no {@code try} in
 *       them.
 *   <li>EXPORT ({@link #computation}): the program under a root delimiter holding the host's {@link Answers}. An
 *       operation with no handler in force: the host's value in place if it answers, else a capture to the root —
 *       {@code Yield(op, k)}, {@code k} okay-java's continuation, multi-shot, from any thread, each call a run of it,
 *       resumed with a signal (a value, {@code k.fail}'s failure thrown where the operation was, {@code k.cancel}'s
 *       cancellation). Inside {@code uncancelable}, {@code cancel} answers the same {@code Yield} again, pending:
 *       resumed, the rest of the region is captured and run under a delimiter that cancels when the region ends, with
 *       a value or a failure.
 *   <li>IMPORT ({@link #foreign}): another's computation started with the host answering in place what the nearest
 *       handler here answers in place; its yields performed here, each under a delimiter of its own whose return is
 *       the value given to its {@code k}. An abort or a cancellation crossing it cancels it, and its finalizers'
 *       operations are performed here; a failure goes to its {@code k.fail}.
 * </ul>
 *
 * <p>Errors of the JVM ({@link VirtualMachineError}, {@link LinkageError}) are not caught. TRUSTED STEPS, the only
 * unchecked casts: {@link #eff} (a kernel program given to this implementation is one of its nodes, at a level of its
 * own — levels are phantom types) and {@link #trust} (a value given where the program, built typed, expects it:
 * {@code Steps}' {@code trust}, the handler found by its effect's exact identity).
 */
public final class Kernel implements Effects {
  private Kernel() {}

  /** okay-java's {@code Effects}: the kernel's programs, built in okay-java's nodes */
  public static final Kernel EFFECTS = new Kernel();

  @Override
  public String toString() {
    return "okay-java";
  }

  // -- the trusted steps

  /** TRUSTED: a kernel program given to okay-java is one of its nodes; at the level {@code Object}, levels phantom */
  @SuppressWarnings("unchecked")
  static <A> Eff<Object, A> eff(Prog<A> p) {
    if (p instanceof Eff<?, ?> e) return (Eff<Object, A>) e;
    throw new IllegalArgumentException("not a program of okay-java: " + p);
  }

  /** TRUSTED: a value given where the program, built typed, expects it */
  @SuppressWarnings("unchecked")
  static <T> T trust(Object v) {
    return (T) v;
  }

  // -- the frames: what a kernel delimiter is for, held by its prompt

  record KRoot(Answers host) {}

  record KHandler(Handler<?, ?> h, Type effect) {}

  record KRecover(Fn<Throwable, ? extends Prog<?>> h) {}

  record KEnsuring(Program<Void> fin) {}

  record KForeign(Resume<?, ?> k) {}

  /** an imported program being cancelled, its finalizers' operations performed here: then {@code then} goes on */
  record KAfter(Throwable then) {}

  enum Mark {
    DOLLAR, UNCANCELABLE,
    /** around an uncancelable region a cancellation waits for: its end — a value or a failure — cancels */
    PENDING
  }

  // -- aborts and cancellations: unwound as failures are, no recover takes them

  private abstract static class Control extends RuntimeException {
    Control(String m) {
      super(m, null, false, false);
    }
  }

  /** an {@link Aborting} handler's operation: to that handler's delimiter, its answer */
  private static final class Abort extends Control {
    final Prompt<?, ?, ?> to;
    final Object answer;

    Abort(Prompt<?, ?, ?> to, Object answer) {
      super("an abort");
      this.to = to;
      this.answer = answer;
    }
  }

  private static final class Cancel extends Control {
    Cancel() {
      super("cancelled");
    }
  }

  private static final Cancel CANCEL = new Cancel();

  private static Throwable nonFatal(Throwable t) {
    if (t instanceof VirtualMachineError || t instanceof LinkageError) throw (Error) t;
    return t;
  }

  // -- the kernel's nodes: each stepped by okay-java's machine

  /** an operation, to the nearest handler of its effect in force */
  static final class Perform<L, X> extends Nodes.Node<L, X> {
    private final Op<X> op;

    Perform(Op<X> op) {
      this.op = op;
    }

    Eff<?, ?> step(Run m) {
      return performed(m, op);
    }
  }

  /** the rest up to the nearest delimiter, a handler's or a dollar's */
  static final class Shift0K<L, A> extends Nodes.Node<L, A> {
    /** its {@code k} okay-java's continuation, a kernel {@code Fn} itself */
    private final Fn<Machine.Kont, ? extends Prog<?>> body;

    Shift0K(Fn<Machine.Kont, ? extends Prog<?>> body) {
      this.body = body;
    }

    Eff<?, ?> step(Run m) {
      return shifted(m, body);
    }
  }

  /** a failure (an abort, a cancellation): unwound from where it is stepped */
  static final class Fail<L, A> extends Nodes.Node<L, A> {
    private final Throwable e;

    Fail(Throwable e) {
      this.e = e;
    }

    Eff<?, ?> step(Run m) {
      return unwind(m, e);
    }
  }

  /** a handed-out operation's signal, read where it was performed */
  static final class Signalled<L, A> extends Nodes.Node<L, A> {
    private final Object s;

    Signalled(Object s) {
      this.s = s;
    }

    Eff<?, ?> step(Run m) {
      return signalled(m, s);
    }
  }

  /** another implementation's program, started where it is placed */
  static final class Foreign<L, A> extends Nodes.Node<L, A> {
    private final Computation<A> c;

    Foreign(Computation<A> c) {
      this.c = c;
    }

    Eff<?, ?> step(Run m) {
      return foreignStarted(m, c);
    }
  }

  // -- Effects

  public <A> Prog<A> pure(A a) {
    return Eff.pure(a);
  }

  public <A, B> Prog<B> flatMap(Prog<A> p, Fn<A, Prog<B>> f) {
    return eff(p).flatMap(a -> bound(f, a));
  }

  private static <A, B> Eff<Object, B> bound(Fn<A, Prog<B>> f, A a) {
    try {
      return eff(f.apply(a));
    } catch (Throwable t) {
      return new Fail<>(nonFatal(t));
    }
  }

  @Override
  public <A, B> Prog<B> map(Prog<A> p, Fn<A, B> f) {
    return eff(p).flatMap(a -> mapped(f, a));
  }

  private static <A, B> Eff<Object, B> mapped(Fn<A, B> f, A a) {
    try {
      return Eff.pure(f.apply(a));
    } catch (Throwable t) {
      return new Fail<>(nonFatal(t));
    }
  }

  public <A> Prog<A> delay(Thunk<Prog<A>> p) {
    return Eff.<Object, A>delay(() -> built(p::get));
  }

  /** a program the program's code gives: its exception a failure where it is placed */
  private static <A> Eff<Object, A> built(Supplier<? extends Prog<A>> p) {
    try {
      return eff(p.get());
    } catch (Throwable t) {
      return new Fail<>(nonFatal(t));
    }
  }

  private static Eff<?, ?> builtAny(Supplier<? extends Prog<?>> p) {
    try {
      return eff(p.get());
    } catch (Throwable t) {
      return new Fail<>(nonFatal(t));
    }
  }

  public <X> Prog<X> perform(Op<X> op) {
    return new Perform<>(op);
  }

  public <A, R> Prog<R> handle(Handler<A, R> h, Program<A> body) {
    return new Handle<Object, A, R>(body(body), returned(h), new KHandler(h, h.effect()));
  }

  /** a kernel delimiter's body: the program built when the delimiter is entered */
  private static <A, R> Eff.Body<Object, A, R> body(Program<A> program) {
    return new Eff.Body<>() {
      public <M> Eff<M, A> in(Prompt<Object, M, R> p) {
        return Kernel.<A>built(() -> program.in(EFFECTS)).at();
      }
    };
  }

  /** a body already a program of okay-java's */
  private static <A, R> Eff.Body<Object, A, R> body(Supplier<Eff<?, ?>> program) {
    return new Eff.Body<>() {
      public <M> Eff<M, A> in(Prompt<Object, M, R> p) {
        return trust(program.get());
      }
    };
  }

  /** a handler's return, outside it */
  private static <A, R> Function<? super A, ?> returned(Handler<A, R> h) {
    if (h instanceof Returning<A, R> r) return (A a) -> {
      try {
        return Eff.pure(r.answer(a));
      } catch (Throwable t) {
        return new Fail<>(nonFatal(t));
      }
    };
    if (h instanceof General<A, R> g) return (A a) -> built(() -> g.ret(a, EFFECTS));
    return Machine.SAME; // no return: the body's value the answer, as Steps
  }

  public <A, I, O> Prog<A> shift0(Fn<Fn<A, Prog<I>>, Prog<O>> body) {
    return new Shift0K<Object, A>(trust(body));
  }

  public <A, I, O> Prog<O> dollar(Program<A> body, Fn<A, Prog<I>> k) {
    Function<A, Eff<?, ?>> ret = a -> built(() -> k.apply(a));
    return new Handle<Object, A, O>(body(body), ret, Mark.DOLLAR);
  }

  public <A> Prog<A> failed(Throwable e) {
    return new Fail<>(e);
  }

  public <A> Prog<A> recover(Program<A> body, Fn<Throwable, Prog<A>> h) {
    return new Handle<Object, A, A>(body(body), Machine.SAME, new KRecover(h));
  }

  public <A> Prog<A> ensuring(Program<A> body, Program<Void> finalizer) {
    Function<A, Eff<?, ?>> ret = a -> finalized(finalizer).then(Eff.pure(a));
    return new Handle<Object, A, A>(body(body), ret, new KEnsuring(finalizer));
  }

  private static Eff<Object, Void> finalized(Program<Void> fin) {
    return built(() -> fin.in(EFFECTS));
  }

  public <A> Prog<A> uncancelable(Program<A> body) {
    return new Handle<Object, A, A>(body(body), Machine.SAME, Mark.UNCANCELABLE);
  }

  /** IMPORT: another's program, its operations answered where it runs */
  public <A> Prog<A> foreign(Computation<A> c) {
    return new Foreign<>(c);
  }

  // -- an operation, in the machine

  /** the nearest delimiter of the stack {@code ms} that is a handler of {@code t}, or the root: its index, or -1 */
  private static int handlerOf(Meta ms, Type t) {
    int i = 0;
    while (ms instanceof Seg s) {
      if (s.prompt().frame instanceof KHandler h ? h.effect() == t : s.prompt().frame instanceof KRoot) return i;
      ms = s.next();
      i++;
    }
    if (ms instanceof Machine.Top) return -1;
    int j = Machine.indexWhere(ms, Integer.MAX_VALUE,
        s -> s.prompt().frame instanceof KHandler h ? h.effect() == t : s.prompt().frame instanceof KRoot);
    return j < 0 ? -1 : i + j;
  }

  private static Eff<?, ?> performed(Run m, Op<?> op) {
    int i = handlerOf(m.ms, op.effect());
    if (i < 0) return unwind(m, new IllegalStateException("an operation of " + op.effect() + " with no handler in force"));
    Seg s = Machine.head(Machine.drop(m.ms, i));
    Object frame = s.prompt().frame;
    if (frame instanceof KRoot r) return unhandled(m, op, r, s, i);
    Handler<?, ?> h = ((KHandler) frame).h();
    if (h instanceof Answering<?, ?> a) {
      Object v;
      try {
        v = a.value(op);
      } catch (Throwable t) {
        return unwind(m, nonFatal(t));
      }
      return m.value(v);
    }
    if (h instanceof Aborting<?, ?> ab) {
      Object answer;
      try {
        answer = ab.abort(op);
      } catch (Throwable t) {
        return unwind(m, nonFatal(t));
      }
      return unwind(m, new Abort(s.prompt(), answer));
    }
    if (h instanceof General<?, ?> g) return m.capture(s.prompt(), (Function<Machine.Kont, Eff<?, ?>>) k -> clause(g, op, k), m.k);
    return unwind(m, new IllegalStateException("a handler of no shape: " + h));
  }

  /** a general handler's clause, in its place; {@code k} okay-java's continuation, a kernel {@code Fn} itself */
  private static <A, R, X> Eff<?, ?> clause(General<A, R> g, Op<X> op, Machine.Kont k) {
    return built(() -> g.apply(op, Kernel.<Fn<X, Prog<R>>>trust(k), EFFECTS));
  }

  private static Eff<?, ?> shifted(Run m, Fn<Machine.Kont, ? extends Prog<?>> body) {
    int i = Machine.indexWhere(m.ms, Integer.MAX_VALUE, s -> s.prompt().frame instanceof KHandler || s.prompt().frame == Mark.DOLLAR);
    if (i < 0) return unwind(m, new IllegalStateException("shift0 with no delimiter around it"));
    Seg d = Machine.head(Machine.drop(m.ms, i));
    return m.capture(d.prompt(), (Function<Machine.Kont, Eff<?, ?>>) k -> builtAny(() -> body.apply(k)), m.k);
  }

  // -- unwinding: a failure, an abort, a cancellation to the nearest delimiter it concerns

  private static boolean concerns(Seg s, Throwable t) {
    Object f = s.prompt().frame;
    return f instanceof KRecover ? !(t instanceof Control)
        : f instanceof KHandler ? t instanceof Abort a && a.to == s.prompt()
        : f instanceof KEnsuring || f instanceof KForeign || f instanceof KAfter || f == Mark.PENDING || f instanceof KRoot;
  }

  static Eff<?, ?> unwind(Run m, Throwable t) {
    int i = Machine.indexWhere(m.ms, Integer.MAX_VALUE, s -> concerns(s, t));
    if (i < 0) {
      // no kernel delimiter around: the program's run, not the kernel's — the failure leaves it as okay-java's do
      if (t instanceof Control) throw new IllegalStateException("an abort or a cancellation with no delimiter of it", t);
      if (t instanceof RuntimeException r) throw r;
      if (t instanceof Error e) throw e;
      throw new IllegalStateException(t);
    }
    Seg s = Machine.head(Machine.drop(m.ms, i));
    // the rest up to it dropped: on in its place
    m.ms = Machine.drop(m.ms, i + 1);
    m.k = s.outer();
    if (s.then() != null) m.k = new Frames.Then(s.then(), m.k);
    return switch (s.prompt().frame) {
      case KRecover(var h) -> builtAny(() -> h.apply(t));
      case KEnsuring(var fin) -> finalized(fin).then(new Fail<>(t));
      case KHandler _ -> Eff.pure(((Abort) t).answer);
      case KForeign(var fk) when t instanceof Control ->
          // an abort or a cancellation crossing it: it is cancelled, its finalizers' operations performed here, then
          // this goes on
          new Handle<Object, Object, Object>(body(() -> stepped(fk::cancel)), (Function<Object, Eff<?, ?>>) _ -> new Fail<>(t), new KAfter(t));
      case KForeign(var fk) -> stepped(() -> fk.fail(t));
      case KAfter(var then) -> new Fail<>(then);
      case KRoot _ -> Eff.pure(t instanceof Cancel ? new Step.Cancelled<>()
          : new Step.Failed<>(t instanceof Abort a ? new IllegalStateException("an abort with its handler gone", a) : t));
      default -> new Fail<>(CANCEL); // Mark.PENDING: the region ended, failed — the cancellation waiting goes on
    };
  }

  // -- export: a run handed out

  /** what a handed-out {@code k} is resumed with */
  private record SigValue(Object x) {}

  private record SigFail(Throwable e) {}

  private record SigCancel() {}

  /** a resumption while a cancellation waits for the region's end */
  private record SigPending(Object s) {}

  private static final Function<Object, Eff<?, ?>> SIGNALLED = Signalled::new;

  private static Eff<?, ?> unhandled(Run m, Op<?> op, KRoot root, Seg s, int i) {
    Answers host = root.host();
    Object v;
    try {
      if (!host.answers(op.effect())) return handedOut(m, op, s, i);
      v = host.value(op);
    } catch (Throwable t) {
      return unwind(m, nonFatal(t));
    }
    return m.value(v);
  }

  /** a capture to the root: the step {@code Yield(op, k)}; {@code k} resumed with a signal, read where the operation
   * was */
  private static <X> Eff<?, ?> handedOut(Run m, Op<X> op, Seg root, int i) {
    boolean region = Machine.indexWhere(m.ms, i, s -> s.prompt().frame == Mark.UNCANCELABLE) >= 0;
    return m.capture(root.prompt(),
        (Function<Machine.Kont, Eff<?, ?>>) k -> Eff.pure(new Step.Yield<>(op, resumption(op, k, region, false))),
        new Frames.Then(SIGNALLED, m.k));
  }

  private static <X> Resume<X, Object> resumption(Op<X> op, Machine.Kont k, boolean region, boolean pending) {
    return new Resume<>() {
      private Step<Object> on(Object s) {
        return run(k.apply(pending ? new SigPending(s) : s));
      }

      public Step<Object> resume(X x) {
        return on(new SigValue(x));
      }

      public Step<Object> fail(Throwable e) {
        return on(new SigFail(e));
      }

      public Step<Object> cancel() {
        // in a region cancellation waits for: the same wait again, the cancellation pending
        if (pending || region) return new Step.Yield<>(op, resumption(op, k, region, true));
        return on(new SigCancel());
      }
    };
  }

  private static Eff<?, ?> signalled(Run m, Object s) {
    return switch (s) {
      case SigValue(var x) -> m.value(x);
      case SigFail(var e) -> unwind(m, e);
      case SigCancel() -> unwind(m, CANCEL);
      case SigPending(var on) -> {
        // the rest of the region captured, run under a delimiter that cancels when the region ends
        int u = Machine.indexWhere(m.ms, Integer.MAX_VALUE, x -> x.prompt().frame == Mark.UNCANCELABLE);
        Seg region = Machine.head(Machine.drop(m.ms, u));
        yield m.capture(region.prompt(), (Function<Machine.Kont, Eff<?, ?>>) k -> new Handle<Object, Object, Object>(
                body(() -> k.apply(on)), (Function<Object, Eff<?, ?>>) _ -> new Fail<>(CANCEL), Mark.PENDING),
            new Frames.Then(SIGNALLED, m.k));
      }
      default -> unwind(m, new IllegalStateException("a signal of no kind: " + s));
    };
  }

  /** a run on okay-java's machine, to a step; an exception out of it (the machine's own) a failure */
  private static Step<Object> run(Eff<?, ?> e) {
    try {
      return trust(Machine.run(e));
    } catch (Throwable t) {
      return new Step.Failed<>(nonFatal(t));
    }
  }

  /** EXPORT: each start a run under a root of its own — its value {@code Done}, an operation nothing here answers
   * {@code Yield}, a failure {@code Failed}, a cancellation {@code Cancelled} */
  public <A> Computation<A> computation(Program<A> program, Set<Type> effects) {
    Set<Type> row = Collections.unmodifiableSet(new HashSet<>(effects));
    return new Computation<>() {
      public Step<A> start(Answers host) {
        Function<A, Eff<?, ?>> done = a -> Eff.pure(new Step.Done<Object>(a));
        return trust(run(new Handle<Object, A, Step<Object>>(body(program), done, new KRoot(host))));
      }

      public Set<Type> effects() {
        return row;
      }
    };
  }

  /** a program with nothing left to perform: its value, or its failure thrown */
  public static <A> A run(Program<A> p) {
    return switch (EFFECTS.computation(p, Set.of()).start(Answers.NONE)) {
      case Step.Done<A>(var a) -> a;
      case Step.Failed<A>(var e) -> {
        if (e instanceof RuntimeException r) throw r;
        if (e instanceof Error r) throw r;
        throw new IllegalStateException(e);
      }
      case Step.Cancelled<A>() -> throw new IllegalStateException("cancelled");
      case Step.Yield<?, A> y -> throw new IllegalStateException("an operation with no handler: " + y.op());
    };
  }

  // -- import

  private static Eff<?, ?> foreignStarted(Run m, Computation<?> c) {
    Answers here = answersHere(m.ms);
    return stepped(() -> c.start(here));
  }

  /** another's step, taken here: a value goes on, a failure fails here, a cancellation cancels here, an operation is
   * performed here — its value to the other's {@code k} */
  private static Eff<?, ?> stepped(Supplier<? extends Step<?>> next) {
    Step<?> s;
    try {
      s = next.get();
    } catch (Throwable t) {
      return new Fail<>(nonFatal(t));
    }
    return switch (s) {
      case Step.Done<?> d -> Eff.pure(d.value());
      case Step.Failed<?> f -> new Fail<>(f.error());
      case Step.Cancelled<?> _ -> new Fail<>(CANCEL);
      case Step.Yield<?, ?> y -> yielded(y);
    };
  }

  private static <X, A> Eff<?, ?> yielded(Step.Yield<X, A> y) {
    Resume<X, A> k = y.k();
    Op<X> op = y.op();
    return new Handle<Object, X, Object>(body(() -> new Perform<>(op)),
        (Function<X, Eff<?, ?>>) x -> stepped(() -> k.resume(x)), new KForeign(k));
  }

  /** what an imported program is answered in place by: the nearest handler of the stack {@code ms}, if it answers in
   * place; none, the root's host */
  private static Answers answersHere(Meta ms) {
    return new Answers() {
      private Object nearest(Type t) {
        int i = handlerOf(ms, t);
        return i < 0 ? null : Machine.head(Machine.drop(ms, i)).prompt().frame;
      }

      public boolean answers(Type t) {
        return switch (nearest(t)) {
          case KHandler(var h, var e) -> h instanceof Answering<?, ?>;
          case KRoot(var host) -> host.answers(t);
          case null, default -> false;
        };
      }

      public <X> X value(Op<X> op) {
        return switch (nearest(op.effect())) {
          case KHandler(var h, var e) when h instanceof Answering<?, ?> a -> a.value(op);
          case KRoot(var host) -> host.value(op);
          case null, default -> throw new IllegalStateException("not answered in place: " + op);
        };
      }
    };
  }
}
