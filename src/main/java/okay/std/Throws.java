package okay.std;

import java.util.function.Function;
import okay.core.Eff;
import okay.core.Prompt;

/** a failure of type {@code E}: {@code raise} drops k, the handler's recovery in its place — run outside the
 * handler, so a recovery that raises again raises to the handler further out */
public interface Throws<L, E> {
  <X> Eff<L, X> raise(E e);

  /** this capability at a level further in */
  @SuppressWarnings("unchecked")
  default <N extends L> Throws<N, E> at() {
    return (Throws<N, E>) (Throws<?, E>) this;
  }

  /** the value, or the failure: okay-core's {@code Either} */
  sealed interface Result<E, A> {
    record Ok<E, A>(A value) implements Result<E, A> {}

    record Failed<E, A>(E error) implements Result<E, A> {}
  }

  /** the body of a handler of failures: at a fresh level, given the capability there */
  interface Body<L, E, A> {
    <M extends L> Eff<M, A> in(Throws<M, E> t);
  }

  /** okay-core's {@code throws}: the value as {@code Ok}, or the first {@code raise} as {@code Failed} — a handler of
   * its own, {@code Ok} its return, {@code Failed} its clause: no recovery, no map in between */
  static <L, E, A> Eff<L, Result<E, A>> attempt(Body<L, E, A> body) {
    return Eff.handle(new Eff.Body<L, A, Result<E, A>>() {
      public <M extends L> Eff<M, A> in(Prompt<L, M, Result<E, A>> p) {
        return body.in(new Throws<M, E>() {
          public <X> Eff<M, X> raise(E e) {
            return p.shift0(_ -> Eff.pure(new Result.Failed<>(e)));
          }
        });
      }
    }, a -> Eff.pure(new Result.Ok<>(a)));
  }

  static <L, E, A> Eff<L, A> catching(Body<L, E, A> body, Function<? super E, ? extends Eff<? super L, A>> recover) {
    return Eff.handle(new Eff.Body<L, A, A>() {
      public <M extends L> Eff<M, A> in(Prompt<L, M, A> p) {
        return body.in(new Throws<M, E>() {
          @SuppressWarnings("unchecked")
          public <X> Eff<M, X> raise(E e) {
            return p.shift0(_ -> (Eff<L, A>) recover.apply(e));
          }
        });
      }
    }, Eff::pure);
  }
}
