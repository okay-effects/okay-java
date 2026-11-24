import static okay.rows.Effects.*;

import okay.effect.Eff;
import okay.effect.Operation;
import okay.rows.Comp;

/** A clause that does not answer every operation of its effect: the GADT is sealed, the {@code switch} is whole. */
final class ClauseMissingCase {
  sealed interface Prices<T> extends Operation<T, Prices<?>> {
    record Price(String item) implements Prices<Long> {}
    record InStock(String item) implements Prices<Boolean> {}
  }

  static <A, R> Comp<A, R> priced(Comp<A, ? super Eff<Prices<?>, R>> p) {
    return handle((op, k) -> switch (op) { // refused: no case for an InStock
      case Prices.Price price -> k.resume(price, 4L);
    }, p);
  }
}
