package okay.rows.std;

import static okay.rows.Effects.*;

import java.util.function.Function;
import okay.rows.Comp;
import okay.rows.Eff;
import okay.rows.Handler;
import okay.rows.K;
import okay.rows.Op;

/** THROWS: the rest of the program is left — unwound, its finalizers run — before the failure is answered. */
public sealed interface Throws<E> {
  /** answered by nothing: by an {@code X} of any type, for there will be none */
  record Raise<E, X>(E error) implements Throws<E>, Op<Throws<E>, X, Raise<E, X>> {}

  static <E, X> Comp<X, Eff<Throws<E>, ?>> raise(E error) { return perform(new Raise<>(error)); }

  sealed interface Result<E, A> {}
  record Ok<E, A>(A value) implements Result<E, A> {}
  record Err<E, A>(E error) implements Result<E, A> {}

  static <E, A, R> Comp<Result<E, A>, R> run(Comp<A, ? super Eff<Throws<E>, R>> p) {
    return handle(Throws.class, new Handler<Throws<E>, A, Result<E, A>, R>() {
      public Comp<Result<E, A>, R> ret(A a) { return pure(new Ok<>(a)); }
      public Comp<Result<E, A>, R> on(Throws<E> op, K<Result<E, A>, R> k) {
        return switch (op) {
          case Raise<E, ?> raise -> k.unwind().then(() -> pure(new Err<>(raise.error())));
        };
      }
    }, p);
  }

  /** the program, or what {@code rescue} makes of its failure */
  static <E, A, R> Comp<A, R> recover(
      Function<E, ? extends Comp<A, ? super R>> rescue, Comp<A, ? super Eff<Throws<E>, R>> p) {
    return handle(Throws.class, new Handler<Throws<E>, A, A, R>() {
      public Comp<A, R> ret(A a) { return pure(a); }
      public Comp<A, R> on(Throws<E> op, K<A, R> k) {
        return switch (op) {
          case Raise<E, ?> raise -> k.unwind().then(() -> rescue.apply(raise.error()));
        };
      }
    }, p);
  }
}
