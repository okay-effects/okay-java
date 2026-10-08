package okay.core;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A PROGRAM WITH EFFECTS, of value {@code A}: {@link Cont0}'s machine with the answer types erased into the
 * delimiters. Two primitives, all the rest is a library:
 *
 * <ul>
 *   <li>{@link #handle(Function, Function)} — {@code ret $ body}, the delimiter under a FRESH {@link Prompt}, the
 *       body given it;</li>
 *   <li>{@link Prompt#shift0} — the rest up to that prompt's delimiter, the delimiter included, as k; the clause run
 *       OUTSIDE it, in its place.</li>
 * </ul>
 *
 * <p>An effect is an interface, a handler an implementation of it over a prompt, handed to the body: a
 * capability. An operation goes to ITS handler, by the prompt, whatever handlers are nearer — each crossed by a
 * lift, one {@code shift0} more, okay-core's {@code Perform} by identity, not by a path in a row. So nothing in
 * a program's type says which effects it performs: what it can perform is what it holds. A capability used after
 * its handler is gone has no delimiter to reach: {@link #run} says so.
 */
public sealed interface Eff<A>
    permits EffLoop.Return, EffLoop.Bind, EffLoop.Map, EffLoop.Shift0, EffLoop.Suspend, EffLoop.Handle, EffLoop.Push, EffLoop.Resume {

  default <B> Eff<B> flatMap(Function<? super A, ? extends Eff<B>> f) {
    return new EffLoop.Bind<>(this, f);
  }

  default <B> Eff<B> map(Function<? super A, ? extends B> f) {
    return new EffLoop.Map<>(this, f);
  }

  /** the next program after this one, this one's value dropped */
  default <B> Eff<B> then(Eff<B> next) {
    return new EffLoop.Bind<>(this, _ -> next);
  }

  static <A> Eff<A> pure(A a) {
    return new EffLoop.Return<>(a);
  }

  /** the program {@code c}, built when the loop gets to it: a tail call in constant stack */
  static <A> Eff<A> delay(Supplier<? extends Eff<A>> c) {
    return new EffLoop.Bind<>(EffLoop.UNIT, _ -> c.get());
  }

  /** a HANDLER: {@code ret $ body}, the body given the delimiter's prompt, fresh each time the loop enters it.
   * The body's value goes to {@code ret}, a program outside; a {@code shift0} to the prompt has its clause there */
  static <A, R> Eff<R> handle(Function<? super Prompt<R>, ? extends Eff<A>> body,
                              Function<? super A, ? extends Eff<R>> ret) {
    return new EffLoop.Handle<>(body, ret);
  }

  /** a handler whose body's value is its answer */
  static <R> Eff<R> handle(Function<? super Prompt<R>, ? extends Eff<R>> body) {
    return new EffLoop.Handle<R, R>(body, Eff::pure);
  }

  /** a program at the top, its value. An operation reaching the top had no handler in force: its capability
   * escaped the handler it came from */
  static <A> A run(Eff<A> p) {
    return EffLoop.run(p);
  }
}
