import static okay.rows.Effects.*;

import okay.effect.Eff;
import okay.effect.Operation;
import okay.rows.Comp;

/** A handler that only answers must answer every operation of its effect: the GADT is sealed, the switch whole. */
final class AnsweringMissingCase {
  sealed interface Prices<T> extends Operation<T, Prices<?>> {
    record Price(String item) implements Prices<Long> {}
    record InStock(String item) implements Prices<Boolean> {}
  }

  static <A, R> Comp<A, R> priced(Comp<A, ? super Eff<Prices<?>, R>> p) {
    return answering(op -> switch (op) { // refused: no case for an InStock
      case Prices.Price price -> price.answer(4L);
    }, p);
  }
}
