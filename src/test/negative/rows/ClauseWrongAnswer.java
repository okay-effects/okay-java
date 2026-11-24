import static okay.rows.Effects.*;

import okay.effect.Eff;
import okay.effect.Operation;
import okay.rows.Comp;

/** A clause that answers an operation of a GADT with a value of another type: the continuation takes the
 * operation and an answer of ITS type, the index of the record. */
final class ClauseWrongAnswer {
  sealed interface Prices<T> extends Operation<T, Prices<?>> {
    record Price(String item) implements Prices<Long> {}
    record InStock(String item) implements Prices<Boolean> {}
  }

  static <A, R> Comp<A, R> priced(Comp<A, ? super Eff<Prices<?>, R>> p) {
    return handle((op, k) -> switch (op) {
      case Prices.Price price -> k.resume(price, "four"); // refused: a Price is answered by a Long
      case Prices.InStock in -> k.resume(in, true);
    }, p);
  }
}
