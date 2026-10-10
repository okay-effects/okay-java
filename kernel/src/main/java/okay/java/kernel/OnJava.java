package okay.java.kernel;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import okay.core.Eff;
import okay.core.Eff.Top;
import okay.core.Prompt;
import okay.kernel.Aborting;
import okay.kernel.Answering;
import okay.kernel.Answers;
import okay.kernel.Computation;
import okay.kernel.Direct;
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
 * OKAY-JAVA AS AN IMPLEMENTATION OF OKAY-KERNEL: {@link Effects} (and {@link Direct}) on okay-java's {@link Eff}, run
 * by its machine. A kernel program is an {@code Eff} at the level {@link Top} (okay-java's levels are types only),
 * wrapped as a {@link Prog}: one object a node, the bridge's cost.
 *
 * <p>THE CONTEXT. An {@code OnJava} is the context a program is built in: its frame, the okay-java prompt of that
 * frame's delimiter, the context outside. {@code handle}, {@code dollar}, {@code recover}, {@code ensuring},
 * {@code uncancelable} and an imported program's operation are each an {@link Eff#handle} — a delimiter with a fresh
 * prompt a run — whose body is built in a context one frame further in. The root is a run's: the host's
 * {@link Answers} and the prompt a step is handed out to.
 *
 * <p>EXACT, okay-java's own:
 * <ul>
 *   <li>{@code pure}, {@code flatMap}, {@code delay}: its nodes; a run in constant stack (100 000 operations at
 *       {@code -Xss512k}).
 *   <li>{@code handle}: {@code ret $ body} under a fresh prompt. An operation of a {@link General} handler is a
 *       {@link Prompt#shift0} to that handler's prompt: {@code k} the rest with the handler (deep), multi-shot, the
 *       clause run outside, in the handler's place, built in the handler's outer context. {@link Answering}: the value
 *       in place, no capture. {@link Returning}: the return a value. The shapes tried in {@code Steps}' order.
 *   <li>{@code shift0} and {@code dollar}: {@code Prompt.shift0} to the nearest delimiter — a handler's or a
 *       {@code dollar}'s, not a frame of failures or finalizers, which it crosses and {@code k} keeps — and
 *       {@code Eff.handle(body, k)}, which is {@code k $ body}.
 *   <li>Export: a run's operation with no handler in force, not answered by the host in place, is a
 *       {@code shift0} to the run's root: the step {@code Yield(op, k)}, {@code k} okay-java's continuation, so
 *       multi-shot and resumable from any thread — each {@code resume} a new {@code Eff.run} of it.
 * </ul>
 *
 * <p>BUILT IN THE BRIDGE, okay-java having no primitive for them:
 * <ul>
 *   <li>FAILURES. Every function the bridge passes to okay-java (a bind's, a delay's, a return, a clause, an answering
 *       handler's {@code value}, a body's {@code in}) runs in a {@code try}: its exception is thrown as data in the
 *       context it was built in — to the nearest frame it concerns, by {@code shift0} to that frame's prompt, the
 *       rest dropped: {@code recover} answers it by its function; {@code ensuring} runs its finalizer, then throws it
 *       on outward; an imported program's operation gives it to that program ({@code k.fail}); the root makes it the
 *       step {@code Failed}. {@code failed(e)} is the same throw. Errors of the JVM ({@link VirtualMachineError},
 *       {@link LinkageError}) are not caught.
 *   <li>ABORTS. An {@link Aborting} handler's operation is the same throw, of an abort to that handler: finalizers on
 *       the way run, no {@code recover} catches it, the handler's answer the abort's.
 *   <li>CANCELLATION. A handed-out {@code k} is resumed with a signal: a value, a failure ({@code k.fail}: thrown where
 *       the operation was, catchable) or a cancellation ({@code k.cancel}: thrown as a cancellation — finalizers run,
 *       no {@code recover} catches it, the root makes it {@code Cancelled}). Inside {@code uncancelable}, a
 *       {@code cancel} answers the same {@code Yield} again, its {@code k} pending: resumed, the rest of the region
 *       is captured ({@code shift0} to the region's prompt) and run, and at its end the cancellation thrown.
 *   <li>IMPORT. Another's program is started with the host answering in place what the nearest handler here
 *       answers in place (else the host of this run); each operation it hands out is performed here under a frame
 *       of its own, its value given to its {@code k.resume}, a failure to {@code k.fail}; an abort or a cancellation
 *       crossing it cancels it ({@code k.cancel}, its step not looked at — as {@code Steps}).
 * </ul>
 *
 * <p>APPROXIMATED:
 * <ul>
 *   <li>LEXICAL, NOT DYNAMIC. The handler an operation goes to, the nearest delimiter of a {@code shift0} and the
 *       frame a failure is thrown to are found in the context the program was BUILT in, not on the stack where it
 *       runs ({@code Steps} looks at the stack). The two agree for a program built in the {@code Effects} it is
 *       given — every {@link Program}'s, every clause's. They differ for a program built in one context and run in
 *       another: a {@code shift0} body built with the {@code Effects} inside the delimiter yet run outside it (to
 *       perform or fail there it should use the outer one), or a {@code k} resumed under other handlers than those
 *       outside its delimiter when captured (an operation to a handler outside the captured part goes to the one of
 *       its build time). Such an operation's prompt is not in force: okay-java's {@code IllegalStateException},
 *       which ends the run as {@code Failed}.
 *   <li>A failure of okay-java's machine itself (that one, the only kind) is not catchable by {@code recover}: it
 *       leaves {@code Eff.run} and the run is {@code Failed}.
 *   <li>A pending cancellation is the region's only: if the region fails instead, the failure goes on and the
 *       cancellation is dropped ({@code Steps} keeps it pending for a later region's end).
 *   <li>Cost: a wrapper a node; {@code map} a bind ({@code Eff.map}'s function could not fail as a program); an
 *       answering handler's value a {@code delay} not a {@code sync}, so its failure is catchable; a lookup walks
 *       the context, one frame a step.
 * </ul>
 *
 * <p>TRUSTED STEPS, the only casts: {@link #level} (okay-java's levels are phantom types), {@link #erase} (a program
 * is covariant in its value at run time), {@link #answer} and {@link #value} (a delimiter's answer is erased in its
 * prompt, as {@code Steps}' {@code trust}: a value given where the program, built typed, expects it — the handler
 * found by its effect's exact identity). And the four methods {@code Effects} and {@code Direct} share, raw (see
 * {@link Shared}).
 */
public final class OnJava implements Shared {

  // -- the context

  /** a frame: what a context adds to the one outside */
  private sealed interface Frame permits Root, InHandler, InDollar, InRecover, InEnsuring, InUncancelable, InForeign {}

  /** a run's root: its host, and the prompt its steps are handed out to; {@code prompt} null outside a run */
  private record Root(Answers host, Prompt<Top, Top, Step<Object>> prompt) implements Frame {}

  private record InHandler(Handler<?, ?> h) implements Frame {}

  private record InDollar() implements Frame {}

  private record InRecover(Fn<Throwable, ? extends Prog<?>> h) implements Frame {}

  private record InEnsuring(Program<Void> fin) implements Frame {}

  private record InUncancelable() implements Frame {}

  /** an operation of an imported program, performed here: its {@code k} */
  private record InForeign(Resume<?, ?> k) implements Frame {}

  private final Frame frame;
  /** the frame's delimiter; null at the root */
  private final Prompt<Top, Top, Object> prompt;
  /** null at the root */
  private final OnJava outer;
  private final Root root;
  /** the nearest delimiter of {@code shift0}, a handler's or a dollar's: its context, or null */
  private final OnJava delimiter;

  private OnJava(Frame frame, Prompt<Top, Top, Object> prompt, OnJava outer) {
    this.frame = frame;
    this.prompt = prompt;
    this.outer = outer;
    this.root = outer == null ? (Root) frame : outer.root;
    this.delimiter = frame instanceof InHandler || frame instanceof InDollar ? this : outer == null ? null : outer.delimiter;
  }

  /** okay-java's {@code Effects}, outside any run: programs are built in it to be run by {@link #computation} or
   * {@link #run}; an operation built here, with no handler, fails — a run's own context hands it out */
  public static final OnJava EFFECTS = new OnJava(new Root(Answers.NONE, null), null, null);

  // -- the programs

  /** okay-java's program, as the kernel's */
  private static final class W<A> implements Prog<A> {
    final Eff<Top, A> eff;

    W(Eff<Top, A> eff) {
      this.eff = eff;
    }
  }

  private static <A> Prog<A> wrap(Eff<Top, A> e) {
    return new W<>(e);
  }

  private static <A> Eff<Top, A> raw(Prog<A> p) {
    if (p instanceof W<A> w) return w.eff;
    throw new IllegalArgumentException("not a program of okay-java's bridge: " + p);
  }

  /** a Direct program: okay-java's own, or the bridge's {@code Prog} (what the methods shared with Effects give) */
  private static Eff<Top, Object> eff(Object p) {
    if (p instanceof W<?> w) return erase(w.eff);
    if (p instanceof Eff<?, ?> e) return erase(level(e));
    throw new IllegalArgumentException("not a program of okay-java: " + p);
  }

  // -- the trusted steps

  /** TRUSTED: okay-java's levels are phantom types — the bridge runs every program at {@link Top}, a handler's body
   * at its fresh level {@code M} the same at run time */
  @SuppressWarnings("unchecked")
  private static <R> Prompt<Top, Top, R> level(Prompt<Top, ?, R> p) {
    return (Prompt<Top, Top, R>) p;
  }

  /** TRUSTED: the same, for a program of okay-java handed to {@link Direct} */
  @SuppressWarnings("unchecked")
  private static Eff<Top, ?> level(Eff<?, ?> e) {
    return (Eff<Top, ?>) e;
  }

  /** TRUSTED, SOUND: a program of {@code A} is one of {@code Object} — covariant at run time, invariant to Java only */
  @SuppressWarnings("unchecked")
  private static Eff<Top, Object> erase(Eff<Top, ?> e) {
    return (Eff<Top, Object>) e;
  }

  /** TRUSTED: a delimiter's answer, erased in its prompt to {@code Object}, of the type the program was built at */
  @SuppressWarnings("unchecked")
  private static <R> Eff<Top, R> answer(Eff<Top, Object> e) {
    return (Eff<Top, R>) (Eff<Top, ?>) e;
  }

  /** TRUSTED: a value given where the program, built typed, expects it (Steps' {@code trust}) */
  @SuppressWarnings("unchecked")
  private static <T> T value(Object v) {
    return (T) v;
  }

  // -- failures, aborts, cancellation: thrown as data to the nearest frame they concern

  /** not a failure: an abort or a cancellation — no {@code recover} catches it */
  private abstract static class Control extends RuntimeException {
    Control(String m) {
      super(m, null, false, false);
    }
  }

  /** an {@link Aborting} handler's operation: to that handler, its answer */
  private static final class Abort extends Control {
    final OnJava to;
    final Object answer;

    Abort(OnJava to, Object answer) {
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

  /** a function of the program's, run: its exception thrown here */
  private <A> Eff<Top, A> guarded(Supplier<Eff<Top, A>> s) {
    try {
      return s.get();
    } catch (Throwable t) {
      return throwIn(nonFatal(t));
    }
  }

  /** {@code t} thrown in this context, when the run gets to it */
  private <A> Eff<Top, A> throwIn(Throwable t) {
    return Eff.delay(() -> thrown(t));
  }

  private <A> Eff<Top, A> thrown(Throwable t) {
    for (OnJava c = this; ; c = c.outer) {
      OnJava at = c;
      switch (c.frame) {
        case Root r -> {
          Step<Object> s = t instanceof Cancel ? new Step.Cancelled<>()
              : new Step.Failed<>(t instanceof Abort a ? new IllegalStateException("an abort with its handler gone", a) : t);
          if (r.prompt() == null) throw new IllegalStateException("a failure outside a run of okay-java's bridge", t);
          return r.prompt().shift0(k -> Eff.pure(s));
        }
        case InHandler _ -> {
          if (t instanceof Abort a && a.to == at) return at.prompt.shift0(k -> Eff.pure(a.answer));
        }
        case InRecover(var h) -> {
          if (!(t instanceof Control)) return c.prompt.shift0(k -> at.outer.guarded(() -> erase(raw(h.apply(t)))));
        }
        case InEnsuring(var fin) -> {
          return c.prompt.shift0(k -> at.outer.finalized(fin, at.outer.throwIn(t)));
        }
        case InForeign(var fk) -> {
          if (t instanceof Control) fk.cancel();
          else return c.prompt.shift0(k -> at.outer.guarded(() -> erase(at.outer.stepped(fk.fail(t)))));
        }
        case InDollar _, InUncancelable _ -> {}
      }
    }
  }

  /** the finalizer, built here, then {@code then} */
  private <A> Eff<Top, A> finalized(Program<Void> fin, Eff<Top, A> then) {
    return this.<Void>guarded(() -> raw(fin.in(this))).flatMap(_ -> then);
  }

  // -- Effects

  public <A, B> Prog<B> flatMap(Prog<A> p, Fn<A, Prog<B>> f) {
    return wrap(raw(p).flatMap(a -> guarded(() -> raw(f.apply(a)))));
  }

  @Override
  public <A, B> Prog<B> map(Prog<A> p, Fn<A, B> f) {
    return wrap(raw(p).flatMap(a -> guarded(() -> Eff.pure(f.apply(a)))));
  }

  static Prog<Object> pureOf(Object a) {
    return wrap(Eff.pure(a));
  }

  Prog<Object> delayOf(Thunk<?> p) {
    return wrap(Eff.<Top, Object>delay(() -> guarded(() -> eff(p.get()))));
  }

  <X> Prog<X> performOf(Op<X> op) {
    return wrap(performed(op));
  }

  /** Direct's, untyped: the body's value of the handler's type, trusted */
  Prog<Object> handleOf(Handler<?, ?> h, Program<?> body) {
    return wrap(this.<Object, Object>handled(value(h), value(body)));
  }

  /** an operation: to the nearest handler of its effect here; none, to the host in place, else handed out */
  private <X> Eff<Top, X> performed(Op<X> op) {
    OnJava c = handlerOf(op.effect());
    if (c == null) return unhandled(op);
    Handler<?, ?> h = ((InHandler) c.frame).h();
    if (h instanceof Answering<?, ?> a) return Eff.delay(() -> guarded(() -> Eff.pure(a.value(op))));
    if (h instanceof Aborting<?, ?> ab) return Eff.delay(() -> guarded(() -> throwIn(new Abort(c, ab.abort(op)))));
    if (h instanceof General<?, ?> g) return clause(g, c, op);
    return throwIn(new IllegalStateException("a handler of no shape: " + h));
  }

  /** a general handler's operation: {@code shift0} to its prompt, the clause in its place */
  private static <A, R, X> Eff<Top, X> clause(General<A, R> g, OnJava c, Op<X> op) {
    OnJava out = c.outer;
    return c.prompt.shift0(k -> erase(out.guarded(() -> raw(g.apply(op, x -> wrap(OnJava.<R>answer(k.apply(x))), out)))));
  }

  private OnJava handlerOf(Type t) {
    for (OnJava c = this; c.outer != null; c = c.outer)
      if (c.frame instanceof InHandler(var h) && h.effect() == t) return c;
    return null;
  }

  private <X> Eff<Top, X> unhandled(Op<X> op) {
    return Eff.delay(() -> guarded(() -> {
      Root r = root;
      if (r.host().answers(op.effect())) return Eff.pure(r.host().value(op));
      if (r.prompt() == null)
        return throwIn(new IllegalStateException("an operation of " + op.effect() + " with no handler, outside a run"));
      return handedOut(op, r.prompt());
    }));
  }

  private <A, R> Eff<Top, R> handled(Handler<A, R> h, Program<A> body) {
    return answer(delimited(new InHandler(h), body, returned(h)));
  }

  /** a handler's return, outside it */
  private <A, R> Function<A, Eff<Top, Object>> returned(Handler<A, R> h) {
    if (h instanceof Returning<A, R> r) return a -> guarded(() -> Eff.pure(r.answer(a)));
    if (h instanceof General<A, R> g) return a -> erase(guarded(() -> raw(g.ret(a, this))));
    return Eff::pure; // no return: the body's value the answer, as Steps
  }

  /** {@code body} under a delimiter of a fresh prompt, built in a context of the frame {@code f}; its value to
   * {@code ret}, here */
  private <A> Eff<Top, Object> delimited(Frame f, Program<A> body, Function<A, Eff<Top, Object>> ret) {
    return built(f, inside -> raw(body.in(inside)), ret);
  }

  private <A> Eff<Top, Object> built(Frame f, Function<OnJava, Eff<Top, A>> body, Function<A, Eff<Top, Object>> ret) {
    OnJava here = this;
    return Eff.handle(new Eff.Body<Top, A, Object>() {
      public <M extends Top> Eff<M, A> in(Prompt<Top, M, Object> p) {
        OnJava inside = new OnJava(f, level(p), here);
        return inside.<A>guarded(() -> body.apply(inside)).<M>at();
      }
    }, ret);
  }

  public <A, I, O> Prog<A> shift0(Fn<Fn<A, Prog<I>>, Prog<O>> body) {
    OnJava d = delimiter;
    if (d == null) return wrap(throwIn(new IllegalStateException("shift0 with no delimiter around it")));
    OnJava out = d.outer;
    return wrap(d.prompt.shift0(k -> erase(out.guarded(() -> raw(body.apply(x -> wrap(OnJava.<I>answer(k.apply(x)))))))));
  }

  public <A, I, O> Prog<O> dollar(Program<A> body, Fn<A, Prog<I>> k) {
    return wrap(answer(delimited(new InDollar(), body, a -> erase(guarded(() -> raw(k.apply(a)))))));
  }

  public <A> Prog<A> failed(Throwable e) {
    return wrap(throwIn(e));
  }

  public <A> Prog<A> recover(Program<A> body, Fn<Throwable, Prog<A>> h) {
    return wrap(answer(delimited(new InRecover(h), body, a -> Eff.pure(a))));
  }

  public <A> Prog<A> ensuring(Program<A> body, Program<Void> finalizer) {
    return wrap(answer(delimited(new InEnsuring(finalizer), body, a -> finalized(finalizer, Eff.pure(a)))));
  }

  public <A> Prog<A> uncancelable(Program<A> body) {
    return wrap(answer(delimited(new InUncancelable(), body, a -> Eff.pure(a))));
  }

  private OnJava uncancelableHere() {
    for (OnJava c = this; c.outer != null; c = c.outer) if (c.frame instanceof InUncancelable) return c;
    return null;
  }

  // -- export: a run handed out

  /** what a handed-out {@code k} is resumed with */
  private sealed interface Signal<X> permits Value, Fail, Cancelled, Pending {}

  private record Value<X>(X x) implements Signal<X> {}

  private record Fail<X>(Throwable e) implements Signal<X> {}

  private record Cancelled<X>() implements Signal<X> {}

  /** a resumption while a cancellation waits for the region's end */
  private record Pending<X>(Signal<X> s) implements Signal<X> {}

  /** the operation handed out: {@code Yield(op, k)} at the root, {@code k} resumed with a signal */
  private <X> Eff<Top, X> handedOut(Op<X> op, Prompt<Top, Top, Step<Object>> top) {
    return top.<Signal<X>>shift0(k -> Eff.<Top, Step<Object>>pure(new Step.Yield<>(op, resumption(op, k, false))))
        .flatMap(this::waited)
        .flatMap(this::signalled);
  }

  /** a pending signal: the rest of the region cancellation waits for, from here, captured and run with the signal,
   * then the cancellation thrown at its end */
  private <X> Eff<Top, Signal<X>> waited(Signal<X> s) {
    if (!(s instanceof Pending<X>(var on))) return Eff.pure(s);
    OnJava u = uncancelableHere();
    return u.prompt.shift0(rest -> rest.apply(on).flatMap(_ -> u.outer.throwIn(CANCEL)));
  }

  private <X> Eff<Top, X> signalled(Signal<X> s) {
    return switch (s) {
      case Value<X>(var x) -> Eff.pure(x);
      case Fail<X>(var e) -> throwIn(e);
      case Cancelled<X>() -> throwIn(CANCEL);
      case Pending<X>(var on) -> signalled(on);
    };
  }

  private <X> Resume<X, Object> resumption(Op<X> op, Function<Signal<X>, Eff<Top, Step<Object>>> k, boolean pending) {
    OnJava here = this;
    return new Resume<>() {
      private Step<Object> on(Signal<X> s) {
        return run(k.apply(pending ? new Pending<>(s) : s));
      }

      public Step<Object> resume(X x) {
        return on(new Value<>(x));
      }

      public Step<Object> fail(Throwable e) {
        return on(new Fail<>(e));
      }

      public Step<Object> cancel() {
        // in a region cancellation waits for: the same wait again, the cancellation pending
        if (pending || here.uncancelableHere() != null) return new Step.Yield<>(op, here.resumption(op, k, true));
        return on(new Cancelled<>());
      }
    };
  }

  /** a run on okay-java's machine, to a step; an exception out of it (the machine's own) a failure */
  private static Step<Object> run(Eff<Top, Step<Object>> e) {
    try {
      return Eff.run(e);
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
        return OnJava.value(run(started(program, host)));
      }

      public Set<Type> effects() {
        return row;
      }
    };
  }

  private static <A> Eff<Top, Step<Object>> started(Program<A> program, Answers host) {
    return Eff.handle(new Eff.Body<Top, A, Step<Object>>() {
      public <M extends Top> Eff<M, A> in(Prompt<Top, M, Step<Object>> p) {
        OnJava top = new OnJava(new Root(host, level(p)), null, null);
        return top.<A>guarded(() -> raw(program.in(top))).<M>at();
      }
    }, a -> Eff.pure(new Step.Done<Object>(a)));
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

  // -- import: another's program, its operations answered here

  public <A> Prog<A> foreign(Computation<A> c) {
    return wrap(Eff.delay(() -> guarded(() -> stepped(c.start(answersHere())))));
  }

  /** a foreign step, taken here */
  private <A> Eff<Top, A> stepped(Step<A> s) {
    return switch (s) {
      case Step.Done<A>(var a) -> Eff.pure(a);
      case Step.Failed<A>(var e) -> throwIn(e);
      case Step.Cancelled<A>() -> throwIn(CANCEL);
      case Step.Yield<?, A> y -> yielded(y);
    };
  }

  /** its operation performed here under a frame of its own; the value to its {@code k} */
  private <X, A> Eff<Top, A> yielded(Step.Yield<X, A> y) {
    Resume<X, A> k = y.k();
    return answer(built(new InForeign(k), (OnJava inside) -> inside.performed(y.op()),
        x -> erase(guarded(() -> stepped(k.resume(x))))));
  }

  /** what an imported program is answered in place by: the nearest handler here, if it answers in place; none, this
   * run's host */
  private Answers answersHere() {
    OnJava here = this;
    return new Answers() {
      public boolean answers(Type e) {
        OnJava c = here.handlerOf(e);
        return c == null ? here.root.host().answers(e) : ((InHandler) c.frame).h() instanceof Answering<?, ?>;
      }

      public <X> X value(Op<X> op) {
        OnJava c = here.handlerOf(op.effect());
        if (c == null) return here.root.host().value(op);
        if (((InHandler) c.frame).h() instanceof Answering<?, ?> a) return a.value(op);
        throw new IllegalStateException("not answered in place: " + op);
      }
    };
  }

  // -- Direct: okay-java's programs, untyped; a bind's function passed as it is

  public Object flatMap(Object p, Fn<Object, Object> f) {
    return eff(p).flatMap(a -> guarded(() -> eff(f.apply(a))));
  }

  public Object map(Object p, Fn<Object, Object> f) {
    return eff(p).flatMap(a -> guarded(() -> Eff.pure(f.apply(a))));
  }

  public <A> Prog<A> prog(Object p) {
    return p instanceof W<?> ? value(p) : wrap(answer(eff(p)));
  }

  public Object unprog(Prog<?> p) {
    return raw(p);
  }
}
