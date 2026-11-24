import okay.effect.Eff;
import okay.effect.Operation;
import okay.rows.Comp;
import okay.rows.Gather;

/** A gather of the answers of one type: an operation answered by another type, or of another effect, asked in its
 * body does not compile. */
final class GatherWrongOperation {
  sealed interface Prices<T> extends Operation<T, Prices<?>> {
    record Price(String item) implements Prices<Long> {}
    record InStock(String item) implements Prices<Boolean> {}
  }

  sealed interface Clock<T> extends Operation<T, Clock<?>> {
    record Now() implements Clock<Long> {}
  }

  static Iterable<Comp<Long, Eff<Prices<?>, ?>>> asked() {
    return new Gather<>() {{
      perform(new Prices.Price("tea"));
      perform(new Prices.InStock("tea")); // refused: answered by a Boolean, a Long is gathered
      perform(new Clock.Now()); // refused: an operation of another effect
    }};
  }
}
