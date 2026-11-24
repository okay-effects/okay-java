package okay.resumable;

import java.util.List;
import okay.effect.Eff;
import okay.effect.Pure;
import okay.rows.Comp;

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

  public sealed interface Ask<T> extends Performed<T, Ask<?>, Ask.Fx> {
    final class Fx extends Suspend implements Marks<Ask<?>> {}
    record Confirm(String question) implements Ask<Boolean> {}
    record Fail<T>(String why) implements Ask<T> {}
  }

  /** two effects, a method that calls a method, a finally */
  public static String shop(List<String> cart, List<String> log) throws Prices.Fx, Ask.Fx {
    try {
      long sum = total(cart);
      if (sum > 10 && !Direct.perform(new Ask.Confirm("pay " + sum + "?"))) {
        return Direct.perform(new Ask.Fail<>("declined"));
      }
      return "paid " + sum;
    } finally {
      log.add("left the shop");
    }
  }

  /** THE BOUNDARY written here too: nothing of this program was transformed */
  public static Comp<String, Eff<Prices<?>, Eff<Ask<?>, Pure>>> shopping(List<String> cart, List<String> log) {
    return Direct.effects(Prices.Fx.class, Ask.Fx.class).of(() -> shop(cart, log));
  }

  public static Comp<Long, Eff<Prices<?>, Pure>> totalling(List<String> cart) {
    return Direct.effects(Prices.Fx.class).of(() -> total(cart));
  }

  public static Comp<Long, Eff<Prices<?>, Pure>> counting(int n) {
    return Direct.effects(Prices.Fx.class).of(() -> count(n));
  }

  /** a set asked from the plain method: a program of okay.rows run as a part of it */
  public static long batched(List<String> cart) throws Prices.Fx {
    List<Long> prices = Direct.effects(Prices.Fx.class).embed(
        okay.rows.Effects.performAll(cart.stream().map(Prices.Price::new).toList()));
    long sum = 0;
    for (long price : prices) sum += price;
    return sum + Direct.perform(new Prices.Price("tea"));
  }

  public static Comp<Long, Eff<Prices<?>, Pure>> batching(List<String> cart) {
    return Direct.effects(Prices.Fx.class).of(() -> batched(cart));
  }

  public static long count(int n) throws Prices.Fx {
    long sum = 0;
    for (int i = 0; i < n; i++) sum += Direct.perform(new Prices.Price("tea"));
    return sum;
  }
}
