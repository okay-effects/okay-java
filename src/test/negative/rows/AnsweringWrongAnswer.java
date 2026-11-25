import static okay.rows.Effects.*;

import okay.effect.Eff;
import okay.effect.Operation;
import okay.rows.Comp;

/** A handler that only answers: an operation's answer is of the type its record names, and every operation of the
 * sealed effect has one. */
final class AnsweringWrongAnswer {
  sealed interface Prices<T> extends Operation<T, Prices<?>> {
    record Price(String item) implements Prices<Long> {}
    record InStock(String item) implements Prices<Boolean> {}
  }

  static <A, R> Comp<A, R> priced(Comp<A, ? super Eff<Prices<?>, R>> p) {
    return answering(op -> switch (op) {
      case Prices.Price price -> price.answer("four"); // refused: a Price is answered by a Long
      case Prices.InStock in -> in.answer(true);
    }, p);
  }
}
