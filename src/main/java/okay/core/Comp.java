package okay.core;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A PROGRAM WITH EFFECTS AT A LEVEL, {@code Comp<L, A>}: of value {@code A}, run under the handlers of the level
 * {@code L} — {@link Cont}'s levels, its nested answer types, kept as a phantom: nothing at run time. A handler's
 * body is at a level of its own, FRESH, a type variable {@code M extends L} nobody outside the body can name (runST's
 * rank-2 trick); its capability is at {@code M}. So a capability cannot leave its handler: out of the body its type
 * is at a level no program is at — not a value of the top, not one of another handler's body. Statically, and at no
 * cost: the levels are types only, the run is {@link Cont}'s machine with the answer types erased into prompts.
 *
 * <p>Two primitives, all the rest is a library: {@link #handle} — {@code ret $ body} under a fresh {@link Prompt}, the
 * body given it; {@link Prompt#shift0} — the rest up to that prompt's delimiter, the delimiter included, as k, the
 * clause run OUTSIDE it, at the level outside. An operation goes to its handler by prompt, whatever handlers are
 * nearer. An effect is an interface at a level, a handler an implementation of it over a prompt.
 *
 * <p>A program of a level outside runs inside as it is — a level in is a subtype, {@code M extends L} — so a bind's
 * function may give one ({@code Comp<? super L, B>}); at the head of a chain, {@link #at} moves it in. The other way
 * does not compile.
 *
 * <p>A program of okay-java is a program of okay-kernel ({@link okay.kernel.Prog}) as it is: {@link Kernel#EFFECTS}
 * builds the kernel's programs in these nodes, and the machine runs the kernel's operations itself
 * ({@code docs/kernel.md}).
 */
public sealed interface Comp<L, A> extends okay.kernel.Prog<A>
    permits Nodes.Node {

  /** the level outside every handler: {@link #run}'s */
  interface Top {}

  default <B> Comp<L, B> flatMap(Function<? super A, ? extends Comp<? super L, B>> f) {
    return new Nodes.Bind<>(this, f);
  }

  default <B> Comp<L, B> map(Function<? super A, ? extends B> f) {
    return new Nodes.Map<>(this, f);
  }

  /** the next program after this one, this one's value dropped */
  default <B> Comp<L, B> then(Comp<? super L, B> next) {
    return new Nodes.Bind<>(this, _ -> next);
  }

  /** this program at a level further in: a program of a level outside runs inside, its handlers in force there */
  @SuppressWarnings("unchecked")
  default <M extends L> Comp<M, A> at() {
    return (Comp<M, A>) this;
  }

  static <L, A> Comp<L, A> pure(A a) {
    return new Nodes.Return<>(a);
  }

  /** the value of {@code a}, computed when the loop gets to it: an operation answered in place reads a cell so */
  static <L, A> Comp<L, A> sync(Supplier<? extends A> a) {
    return new Nodes.Sync<>(a);
  }

  /** the program {@code c}, built when the loop gets to it: a tail call in constant stack */
  static <L, A> Comp<L, A> delay(Supplier<? extends Comp<? super L, A>> c) {
    return new Nodes.Delay<>(c);
  }

  /** a handler's body: at a level of its own, {@code M}, fresh — given the delimiter's prompt */
  interface Body<L, A, R> {
    <M extends L> Comp<M, A> in(Prompt<L, M, R> p);
  }

  /** a HANDLER: {@code ret $ body}, the body at a fresh level under a fresh prompt, made each time the loop enters
   * it. The body's value goes to {@code ret}, a program outside; a {@code shift0} to the prompt has its clause there */
  static <L, A, R> Comp<L, R> handle(Body<L, A, R> body, Function<? super A, ? extends Comp<? super L, R>> ret) {
    return new Nodes.Handle<>(body, ret);
  }

  /** a RESET: a delimiter answering no operation, the body's value its answer, its prompt the body's for
   * {@link Prompt#shift} */
  static <L, R> Comp<L, R> reset(Body<L, R, R> body) {
    return new Nodes.Handle<L, R, R>(body, Comp::pure);
  }

  // -- failures: a function of the program's throwing, or failed(e) — unwound to the nearest guard that takes it

  /** a FAILURE: unwound from where it is, through the delimiters, to the nearest guard — {@link #recover},
   * {@link #ensuring}, the kernel's — or out of the run, thrown by {@link #run} */
  static <L, A> Comp<L, A> failed(Throwable e) {
    return new Kernel.Fail<>(e);
  }

  /** a GUARD: a failure in {@code body} given to {@code h}, its program in the body's place. Not a guard against an
   * abort or a cancellation of the kernel's: those unwind through it. A {@code shift0} across it keeps it in
   * {@code k}, put back each time {@code k} is resumed. {@link VirtualMachineError}s and {@link LinkageError}s are
   * not failures: never caught */
  static <L, A> Comp<L, A> recover(Comp<L, A> body, Function<? super Throwable, ? extends Comp<? super L, A>> h) {
    return new Nodes.Handle<L, A, A>(Nodes.plain(body), Machine.SAME, new Kernel.KRecover(h));
  }

  /** a GUARD: {@code fin} after {@code body}, whatever way it is left — its value, a failure, an abort or a
   * cancellation of the kernel's — then on the same way. A {@code shift0} across it keeps it in {@code k}: {@code fin}
   * once a resumption. A failure of {@code fin} replaces the failure */
  static <L, A> Comp<L, A> ensuring(Comp<L, A> body, Comp<? super L, ?> fin) {
    Function<A, Comp<?, ?>> ret = a -> fin.then(Comp.pure(a));
    return new Nodes.Handle<L, A, A>(Nodes.plain(body), ret, new Kernel.KEnsuring(() -> fin));
  }

  /** a REGION a cancellation of the kernel's waits for: a handed-out operation's {@code k.cancel} inside it is
   * answered when it ends — nothing of okay-java's own cancels */
  static <L, A> Comp<L, A> uncancelable(Comp<L, A> body) {
    return new Nodes.Handle<L, A, A>(Nodes.plain(body), Machine.SAME, Kernel.Mark.UNCANCELABLE);
  }

  /** a program at the top, its value; a failure no guard took, thrown. Refused ({@link NestedRun}) while a program
   * runs on this thread: from a program's function or a clause */
  static <A> A run(Comp<Top, A> p) {
    return Machine.run(p);
  }

  /** the same, inside a running program too, ON PURPOSE: a closed program run to its end as a run of its own — a
   * machine of its own on the stack, which sees none of the program's handlers. For a conversion, a pure helper
   * evaluated whole, a run started by a callback; not at each step of a loop */
  static <A> A runIsolated(Comp<Top, A> p) {
    return Machine.runIsolated(p);
  }
}
