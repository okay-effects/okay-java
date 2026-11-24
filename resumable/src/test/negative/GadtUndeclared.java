import java.util.List;
import okay.effect.Eff;
import okay.effect.Pure;
import okay.resumable.Direct;
import okay.resumable.Marks;
import okay.resumable.Performed;
import okay.resumable.Suspend;
import okay.rows.Comp;

/** Effects written as GADTs, in direct style: a method lists in its {@code throws} the effects it uses, and a row
 * has every effect its body performs. */
final class GadtUndeclared {
  sealed interface Prices<T> extends Performed<T, Prices<?>, Prices.Fx> {
    final class Fx extends Suspend implements Marks<Prices<?>> {}
    record Price(String item) implements Prices<Long> {}
  }

  sealed interface Clock<T> extends Performed<T, Clock<?>, Clock.Fx> {
    final class Fx extends Suspend implements Marks<Clock<?>> {}
    record Now() implements Clock<Long> {}
  }

  static long undeclared(String item) throws Prices.Fx {
    long price = Direct.perform(new Prices.Price(item));
    return price + Direct.perform(new Clock.Now()); // refused: Clock.Fx is not in the throws
  }

  static long both(String item) throws Prices.Fx, Clock.Fx {
    return Direct.perform(new Prices.Price(item)) + Direct.perform(new Clock.Now());
  }

  static Comp<Long, Eff<Prices<?>, Pure>> wrongRow() {
    return Direct.effects(Prices.Fx.class).of(() -> both("tea")); // refused: the body performs Clock too
  }
}
