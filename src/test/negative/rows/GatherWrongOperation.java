import static okay.rows.Effects.*;

import okay.effect.Eff;
import okay.effect.Operation;
import okay.rows.Comp;

/** A loop of questions: an operation answered by another type, or of another effect, asked through the gather does
 * not compile. */
final class GatherWrongOperation {
  sealed interface Prices<T> extends Operation<T, Prices<?>> {
    record Price(String item) implements Prices<Long> {}
    record InStock(String item) implements Prices<Boolean> {}
  }

  sealed interface Clock<T> extends Operation<T, Clock<?>> {
    record Now() implements Clock<Long> {}
  }

  static Comp<java.util.List<Long>, Eff<Prices<?>, ?>> asked() {
    return gather(g -> {
      g.perform(new Prices.Price("tea"));
      g.perform(new Prices.InStock("tea")); // refused: answered by a Boolean, a Long is gathered
      g.perform(new Clock.Now()); // refused: an operation of another effect
    });
  }
}
