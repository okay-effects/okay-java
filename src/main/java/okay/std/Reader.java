package okay.std;

import okay.core.Eff;

/** an environment to ask. Its one operation is answered in place, {@code shift0 k. k(env)} is {@code env}: the
 * capability is the handler, no prompt, no capture — and at the body's fresh level, so it does not leave it */
@FunctionalInterface
public interface Reader<L, E> {
  Eff<L, E> ask();

  /** this capability at a level further in */
  @SuppressWarnings("unchecked")
  default <N extends L> Reader<N, E> at() {
    return (Reader<N, E>) (Reader<?, E>) this;
  }

  /** the body of {@link #reader}: at a fresh level, given the capability there */
  interface Body<L, E, A> {
    <M extends L> Eff<M, A> in(Reader<M, E> r);
  }

  static <L, E, A> Eff<L, A> reader(E env, Body<L, E, A> body) {
    return Eff.delay(() -> {
      Eff<L, E> value = Eff.pure(env);
      return body.<L>in(() -> value);
    });
  }
}
