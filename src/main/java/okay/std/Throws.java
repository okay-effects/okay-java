package okay.std;

import java.util.function.Function;
import okay.core.Comp;
import okay.core.Prompt;

/** a failure of type {@code E}: {@code raise} drops k, the handler's recovery in its place — run outside the
 * handler, so a recovery that raises again raises to the handler further out */
public interface Throws<L, E> {
  <X> Comp<X, L> raise(E e);

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
    <M extends L> Comp<A, M> in(Throws<M, E> t);
  }

  /** okay-core's {@code throws}: the value as {@code Ok}, or the first {@code raise} as {@code Failed} — a handler of
   * its own, {@code Ok} its return, {@code Failed} its clause: no recovery, no map in between */
  static <L, E, A> Comp<Result<E, A>, L> attempt(Body<L, E, A> body) {
    return Comp.handle(new Comp.Body<L, A, Result<E, A>>() {
      public <M extends L> Comp<A, M> in(Prompt<L, M, Result<E, A>> p) {
        return body.in(new Throws<M, E>() {
          public <X> Comp<X, M> raise(E e) {
            return p.shift0(_ -> Comp.pure(new Result.Failed<>(e)));
          }
        });
      }
    }, a -> Comp.pure(new Result.Ok<>(a)));
  }

  static <L, E, A> Comp<A, L> catching(Body<L, E, A> body, Function<? super E, ? extends Comp<A, ? super L>> recover) {
    return Comp.handle(new Comp.Body<L, A, A>() {
      public <M extends L> Comp<A, M> in(Prompt<L, M, A> p) {
        return body.in(new Throws<M, E>() {
          @SuppressWarnings("unchecked")
          public <X> Comp<X, M> raise(E e) {
            return p.shift0(_ -> (Comp<A, L>) recover.apply(e));
          }
        });
      }
    }, Comp::pure);
  }
}
