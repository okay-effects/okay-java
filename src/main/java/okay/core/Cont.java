package okay.core;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * THE MONAD OF DELIMITED CONTINUATIONS: {@code Cont<I, O, A>} is a program of value {@code A} which, with a
 * continuation answering {@code I}, answers {@code O} — Danvy–Filinski's {@code (A -> I) -> O}, the answer type
 * modified (Asai–Kameyama). A program is a tree, not a function: {@code Bind} composes the answers end to end;
 * {@code Return} and {@code Map} keep them; {@code Shift} and {@code Reset} move them. Nothing runs when a tree
 * is built — {@link #run} and {@link #step} read it, in constant stack, with a stack of frames of their own.
 *
 * <p>The types are checked at the API; the loop inside works on the erased tree, since Java cannot refine a
 * type parameter by a pattern the way a GADT match does.
 */
public sealed interface Cont<I, O, A>
    permits Cont.Return, Cont.Bind, Cont.Map, Cont.Shift, Cont.Reset, Push {

  /** a value: the continuation applied to it, the answer unchanged */
  record Return<S, A>(A value) implements Cont<S, S, A> {}

  /** the answers composed end to end: {@code m} from {@code T} to {@code O}, {@code f}'s from {@code I} to
   * {@code T}. A tail call is {@code Bind(Return(null), _ -> c)}, read at once — {@link #delay} */
  record Bind<I, T, O, A, B>(Cont<T, O, A> m, Function<? super A, ? extends Cont<I, T, B>> f) implements Cont<I, O, B> {}

  /** {@code m.map(f)}: one node, where {@code m.flatMap(a -> pure(f(a)))} is a bind, a closure and a return */
  record Map<I, O, A, B>(Cont<I, O, A> m, Function<? super A, ? extends B> f) implements Cont<I, O, B> {}

  /** {@code shift(k -> body)}: {@code k} is the context up to the nearest delimiter, under a delimiter of its own,
   * delivering the answer at the hole, {@code I}; the body runs under the delimiter in place of the context, at
   * its final answer {@code O}, and its value is that answer */
  record Shift<I, O, A>(Function<Function<A, Cont<O, O, I>>, Cont<O, O, O>> f) implements Cont<I, O, A> {}

  /** the delimiter: the body's value to its initial answer {@code S} by {@code ret}, its final answer {@code O}
   * the delimiter's value outside, at the answer {@code Q} there */
  record Reset<Q, S, O, A>(Cont<S, O, A> body, Function<? super A, ? extends S> ret) implements Cont<Q, Q, O> {}

  default <I2, B> Cont<I2, O, B> flatMap(Function<? super A, ? extends Cont<I2, I, B>> f) {
    return new Bind<>(this, f);
  }

  default <B> Cont<I, O, B> map(Function<? super A, ? extends B> f) {
    return new Map<>(this, f);
  }

  /** the next program after this one, this one's value dropped */
  default <I2, B> Cont<I2, O, B> then(Cont<I2, I, B> next) {
    return new Bind<>(this, _ -> next);
  }

  /** {@code body.reset(ret)}: this program under a delimiter, its value to the initial answer by {@code ret} */
  default <Q> Cont<Q, Q, O> reset(Function<? super A, ? extends I> ret) {
    return new Reset<>(this, ret);
  }

  static <S, A> Cont<S, S, A> pure(A a) {
    return new Return<>(a);
  }

  /** {@code shift(k -> body)}, see {@link Shift} */
  static <I, O, A> Cont<I, O, A> shift(Function<Function<A, Cont<O, O, I>>, Cont<O, O, O>> f) {
    return new Shift<>(f);
  }

  /** {@code reset(body)}: the body's value is its initial answer, the delimiter's value its final one */
  static <Q, S, O> Cont<Q, Q, O> reset(Cont<S, O, S> body) {
    return new Reset<>(body, Function.identity());
  }

  /** {@code delay(() -> c)}: the program {@code c}, built when the loop gets to it — a tail call in constant
   * stack, and a program built afresh on every run */
  static <I, O, A> Cont<I, O, A> delay(Supplier<? extends Cont<I, O, A>> c) {
    return new Bind<I, O, O, Object, A>(new Return<>(null), _ -> c.get());
  }

  /** a program at the top, its answer its value: the value. The top is a delimiter, so a {@code shift} with no
   * {@code reset} captures up to it */
  @SuppressWarnings({"unchecked", "rawtypes"})
  static <A> A run(Cont<A, A, A> c) {
    Cont cur = c;
    while (true) {
      switch (Loop.step(cur)) {
        case Head.Done d -> { return (A) d.value(); }
        case Head.Cut cut -> cur = (Cont) cut.f().apply(Loop.under(cut.k(), Function.identity()));
      }
    }
  }
}
