import static okay.rows.Effects.*;

import okay.rows.Comp;
import okay.rows.Eff;
import okay.rows.Handler;
import okay.rows.K;
import okay.rows.std.State;

/** A handler that does not answer every operation of its effect: the family is sealed, the {@code switch} is whole. */
final class MissingCase {
  static <A, R> Comp<A, R> readOnly(Comp<A, ? super Eff<State<Integer>, R>> p) {
    return handle(State.class, new Handler<State<Integer>, A, A, R>() {
      public Comp<A, R> ret(A a) { return pure(a); }
      public Comp<A, R> on(State<Integer> op, K<A, R> k) {
        return switch (op) { // refused: no case for a Put
          case State.Get<Integer> get -> k.resume(get, 0);
        };
      }
    }, p);
  }
}
