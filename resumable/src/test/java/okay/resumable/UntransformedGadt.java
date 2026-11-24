package okay.resumable;

import java.util.List;

/** Code built WITHOUT the transformation, over an effect written as a GADT: the build copies this class as javac
 * wrote it, as a project that does not use the plugin would have it */
public final class UntransformedGadt {
  private UntransformedGadt() {}

  public sealed interface Prices<T> extends Performed<T, Prices<?>, Prices.Fx> {
    final class Fx extends Suspend implements Marks<Prices<?>> {}
    record Price(String item) implements Prices<Long> {}
    record InStock(String item) implements Prices<Boolean> {}
  }

  /** a for with perform in it, the loop going by the answers */
  public static long total(List<String> cart) throws Prices.Fx {
    long sum = 0;
    for (String item : cart) {
      if (!Direct.perform(new Prices.InStock(item))) continue;
      long price = Direct.perform(new Prices.Price(item));
      if (price > 100) break;
      sum += price;
    }
    return sum;
  }

  public static long count(int n) throws Prices.Fx {
    long sum = 0;
    for (int i = 0; i < n; i++) sum += Direct.perform(new Prices.Price("tea"));
    return sum;
  }
}
