package okay.core;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * THE MONAD OF DELIMITED CONTINUATIONS with {@code shift0} and {@code $} — Materzok–Biernacki's: {@code Cont0<I, O, A>}
 * is a program of value {@code A} which, with a continuation answering {@code I}, answers {@code O}, as {@link Cont}'s.
 * Not as there: what an answer is. {@code shift0}'s body runs OUTSIDE the delimiter it captured up to, in its place,
 * so the body is a program of the level outside — and the answers are that level's programs, {@code Cont0} again:
 * the levels nested in the answer types, {@code Cont0<Cont0<…>, Cont0<…>, A>}, the stack of delimiters typed.
 *
 * <p>{@code ret $ K[shift k. e]} is {@code id $ e[k := λx. ret $ K[x]]} — the body under a delimiter again;
 * {@code ret $ K[shift0 k. e]} is {@code e[k := λx. ret $ K[x]]} — the body where the delimiter was. So
 * {@code shift k. e = shift0 k. reset0 e}, and a {@code shift0} in a {@code shift0}'s body reaches the next delimiter out.
 *
 * <p>okay-core's {@code Cont0}, without the effects: those are {@link Eff}'s, by prompt, the answer types erased.
 */
public sealed interface Cont0<I, O, A>
    permits Cont0.Return, Cont0.Bind, Cont0.Map, Cont0.Shift0, Cont0.Dollar, Loop0.Push, Loop0.Resume {

  record Return<S, A>(A value) implements Cont0<S, S, A> {}

  /** the answers composed end to end: {@code m} from {@code T} to {@code O}, {@code f}'s from {@code I} to {@code T} */
  record Bind<I, T, O, A, B>(Cont0<T, O, A> m, Function<? super A, ? extends Cont0<I, T, B>> f)
      implements Cont0<I, O, B> {}

  record Map<I, O, A, B>(Cont0<I, O, A> m, Function<? super A, ? extends B> f) implements Cont0<I, O, B> {}

  /** {@code shift0(k -> body)}: {@code k} the context up to the nearest delimiter AND the delimiter — applied, the
   * context under the delimiter again, a program of the level outside; the body runs outside, where the delimiter
   * was, its value the answer there. The answer at the hole is a program, {@code Cont0<I1, O1, X1>}: what k gives */
  record Shift0<I1, O1, X1, O, A>(Function<Function<A, Cont0<I1, O1, X1>>, O> f)
      implements Cont0<Cont0<I1, O1, X1>, O, A> {}

  /** {@code ret $ body}: the delimiter, its return clause {@code ret} — the body's value to its initial answer
   * {@code I}, a program outside; the body's final answer, a program outside, is the delimiter's */
  record Dollar<I, I2, O2, B, A>(Cont0<I, Cont0<I2, O2, B>, A> body, Function<? super A, ? extends I> ret)
      implements Cont0<I2, O2, B> {}

  default <I2, B> Cont0<I2, O, B> flatMap(Function<? super A, ? extends Cont0<I2, I, B>> f) {
    return new Bind<>(this, f);
  }

  default <B> Cont0<I, O, B> map(Function<? super A, ? extends B> f) {
    return new Map<>(this, f);
  }

  static <S, A> Cont0<S, S, A> pure(A a) {
    return new Return<>(a);
  }

  static <I1, O1, X1, O, A> Cont0<Cont0<I1, O1, X1>, O, A> shift0(Function<Function<A, Cont0<I1, O1, X1>>, O> f) {
    return new Shift0<>(f);
  }

  /** {@code ret $ body} */
  static <I, I2, O2, B, A> Cont0<I2, O2, B> dollar(Function<? super A, ? extends I> ret, Cont0<I, Cont0<I2, O2, B>, A> body) {
    return new Dollar<>(body, ret);
  }

  /** {@code pure $ body}: the body's value a program outside answering nothing new */
  static <J, I2, O2, B, A> Cont0<I2, O2, B> reset0(Cont0<Cont0<J, J, A>, Cont0<I2, O2, B>, A> body) {
    return new Dollar<>(body, Return::new);
  }

  /** {@code lift(m)}: {@code m} run one delimiter out, in its place, its value back here — k puts the delimiter back */
  static <I1, T, O, X1, A> Cont0<Cont0<I1, T, X1>, Cont0<I1, O, X1>, A> lift(Cont0<T, O, A> m) {
    return shift0(k -> m.flatMap(k));
  }

  /** the program {@code c}, built when the loop gets to it: a tail call */
  static <I, O, A> Cont0<I, O, A> delay(Supplier<? extends Cont0<I, O, A>> c) {
    return new Bind<I, O, O, Object, A>(new Return<>(null), _ -> c.get());
  }

  /** a program at the top: its value — the top is a delimiter, {@code identity $}; a {@code shift0} up to it has
   * its body's value, a program of the level outside the top, as the program's */
  static <A> A value(Cont0<A, A, A> c) {
    return Loop0.value(c);
  }
}
