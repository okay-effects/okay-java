import okay.resumable.Direct;
import okay.resumable.Marks;
import okay.resumable.Performed;
import okay.resumable.Suspend;

/** An effect written as a GADT, in direct style: an answer has the type its record names. */
final class GadtWrongAnswer {
  sealed interface Prices<T> extends Performed<T, Prices<?>, Prices.Fx> {
    final class Fx extends Suspend implements Marks<Prices<?>> {}
    record Price(String item) implements Prices<Long> {}
  }

  static String wrongAnswer(String item) throws Prices.Fx {
    String price = Direct.perform(new Prices.Price(item)); // refused: a Price is answered by a Long
    return price;
  }
}
