package okay.std;

import java.util.function.Function;
import okay.core.Eff;
import okay.core.Prompt;

/** a failure of type {@code E}: {@code raise} drops k, the handler's recovery in its place — run outside the
 * handler, so a recovery that raises again raises to the handler further out */
@FunctionalInterface
public interface Throws<E> {
  <X> Eff<X> raise(E e);

  /** the value, or the failure: okay-core's {@code Either} */
  sealed interface Result<E, A> {
    record Ok<E, A>(A value) implements Result<E, A> {}

    record Failed<E, A>(E error) implements Result<E, A> {}
  }

  /** okay-core's {@code throws}: the value as {@code Ok}, or the first {@code raise} as {@code Failed} */
  static <E, A> Eff<Result<E, A>> attempt(Function<Throws<E>, Eff<A>> body) {
    return Throws.<E, Result<E, A>>catching(t -> body.apply(t).map(Result.Ok::new), e -> Eff.pure(new Result.Failed<>(e)));
  }

  static <E, A> Eff<A> catching(Function<Throws<E>, Eff<A>> body,
                                Function<? super E, ? extends Eff<A>> recover) {
    return Eff.handle((Prompt<A> p) -> body.apply(new Throws<E>() {
      public <X> Eff<X> raise(E e) {
        return p.shift0(_ -> recover.apply(e));
      }
    }));
  }
}
