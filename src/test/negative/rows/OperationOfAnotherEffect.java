import static okay.rows.Effects.*;

import okay.effect.Eff;
import okay.effect.Operation;
import okay.rows.Comp;

/** A GADT that names another effect as its own: its records are not of that effect, and {@code perform} refuses
 * them. */
final class OperationOfAnotherEffect {
  sealed interface Clock<T> extends Operation<T, Clock<?>> {
    record Now() implements Clock<Long> {}
  }

  sealed interface Prices<T> extends Operation<T, Clock<?>> {
    record Price(String item) implements Prices<Long> {}
  }

  static Comp<Long, Eff<Clock<?>, ?>> price() {
    return perform(new Prices.Price("tea")); // refused: a Price is not a Clock
  }
}
