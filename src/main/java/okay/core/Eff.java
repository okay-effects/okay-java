package okay.core;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A PROGRAM WITH EFFECTS AT A LEVEL, {@code Eff<L, A>}: of value {@code A}, run under the handlers of the level
 * {@code L} — {@link Cont0}'s levels, its nested answer types, kept as a phantom: nothing at run time. A handler's
 * body is at a level of its own, FRESH, a type variable {@code M extends L} nobody outside the body can name (runST's
 * rank-2 trick); its capability is at {@code M}. So a capability cannot leave its handler: out of the body its type
 * is at a level no program is at — not a value of the top, not one of another handler's body. Statically, and at no
 * cost: the levels are types only, the run is {@link Cont0}'s machine with the answer types erased into prompts.
 *
 * <p>Two primitives, all the rest is a library: {@link #handle} — {@code ret $ body} under a fresh {@link Prompt}, the
 * body given it; {@link Prompt#shift0} — the rest up to that prompt's delimiter, the delimiter included, as k, the
 * clause run OUTSIDE it, at the level outside. An operation goes to its handler by prompt, whatever handlers are
 * nearer. An effect is an interface at a level, a handler an implementation of it over a prompt.
 *
 * <p>A program of a level outside runs inside as it is — a level in is a subtype, {@code M extends L} — so a bind's
 * function may give one ({@code Eff<? super L, B>}); at the head of a chain, {@link #at} moves it in. The other way
 * does not compile.
 *
 * <p>A program of okay-java is a program of okay-kernel ({@link okay.kernel.Prog}) as it is: {@link Kernel#EFFECTS}
 * builds the kernel's programs in these nodes, and the machine runs the kernel's operations itself
 * ({@code docs/kernel.md}).
 */
public sealed interface Eff<L, A> extends okay.kernel.Prog<A>
    permits Nodes.Node {

  /** the level outside every handler: {@link #run}'s */
  interface Top {}

  default <B> Eff<L, B> flatMap(Function<? super A, ? extends Eff<? super L, B>> f) {
    return new Nodes.Bind<>(this, f);
  }

  default <B> Eff<L, B> map(Function<? super A, ? extends B> f) {
    return new Nodes.Map<>(this, f);
  }

  /** the next program after this one, this one's value dropped */
  default <B> Eff<L, B> then(Eff<? super L, B> next) {
    return new Nodes.Bind<>(this, _ -> next);
  }

  /** this program at a level further in: a program of a level outside runs inside, its handlers in force there */
  @SuppressWarnings("unchecked")
  default <M extends L> Eff<M, A> at() {
    return (Eff<M, A>) this;
  }

  static <L, A> Eff<L, A> pure(A a) {
    return new Nodes.Return<>(a);
  }

  /** the value of {@code a}, computed when the loop gets to it: an operation answered in place reads a cell so */
  static <L, A> Eff<L, A> sync(Supplier<? extends A> a) {
    return new Nodes.Sync<>(a);
  }

  /** the program {@code c}, built when the loop gets to it: a tail call in constant stack */
  static <L, A> Eff<L, A> delay(Supplier<? extends Eff<? super L, A>> c) {
    return new Nodes.Delay<>(c);
  }

  /** a handler's body: at a level of its own, {@code M}, fresh — given the delimiter's prompt */
  interface Body<L, A, R> {
    <M extends L> Eff<M, A> in(Prompt<L, M, R> p);
  }

  /** a HANDLER: {@code ret $ body}, the body at a fresh level under a fresh prompt, made each time the loop enters
   * it. The body's value goes to {@code ret}, a program outside; a {@code shift0} to the prompt has its clause there */
  static <L, A, R> Eff<L, R> handle(Body<L, A, R> body, Function<? super A, ? extends Eff<? super L, R>> ret) {
    return new Nodes.Handle<>(body, ret);
  }

  /** a RESET: a delimiter answering no operation, the body's value its answer, its prompt the body's for
   * {@link Prompt#shift} */
  static <L, R> Eff<L, R> reset(Body<L, R, R> body) {
    return new Nodes.Handle<L, R, R>(body, Eff::pure);
  }

  /** a program at the top, its value */
  static <A> A run(Eff<Top, A> p) {
    return Machine.run(p);
  }
}
