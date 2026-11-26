import static okay.rows.Effects.*;

import okay.effect.Eff;
import okay.effect.Op;
import okay.effect.Pure;
import okay.rows.Comp;
import okay.rows.Var;

/** A block of a row of places asks operations of its first effect only: not one of an effect that effect is a
 * part of — its handler would be given an operation that is not its own. */
final class TogetherSuperEffect {
  sealed interface Shop permits Prices, Shop.Close {
    record Close() implements Shop, Op<Shop, Void, Close> {}
  }
  sealed interface Prices extends Shop {
    record Price(String item) implements Prices, Op<Prices, Long, Price> {}
  }

  static Comp<Long, Eff<Prices, Pure>> quote() {
    return together(t -> {
      Var<Long> price = t.perform(new Prices.Price("tea"));
      t.perform(new Shop.Close()); // refused: a Close is not of Prices
      return t.yield(price);
    });
  }
}
