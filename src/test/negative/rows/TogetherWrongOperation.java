import static okay.rows.Effects.*;

import okay.effect.Eff;
import okay.effect.Operation;
import okay.rows.Comp;
import okay.rows.Var;

/** A block of a row: {@code t.perform} takes an operation of the row's first effect, its answer's name of the
 * operation's type; an operation of another effect, or a name of another type, does not compile. */
final class TogetherWrongOperation {
  sealed interface Prices<T> extends Operation<T, Prices<?>> {
    record Price(String item) implements Prices<Long> {}
  }

  sealed interface Clock<T> extends Operation<T, Clock<?>> {
    record Now() implements Clock<Long> {}
  }

  static Comp<Long, Eff<Prices<?>, ?>> block() {
    return together(t -> {
      Var<Long> price = t.perform(new Prices.Price("tea"));
      Var<Long> now = t.perform(new Clock.Now()); // refused: an operation of another effect
      Var<String> named = t.perform(new Prices.Price("cup")); // refused: a Price is answered by a Long
      return t.yield(price, x -> x);
    });
  }
}
