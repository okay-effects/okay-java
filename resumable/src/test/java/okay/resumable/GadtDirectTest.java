package okay.resumable;

import static okay.rows.Effects.handle;
import static okay.rows.Effects.run;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import okay.effect.Eff;
import okay.effect.Pure;
import okay.rows.Comp;
import org.junit.jupiter.api.Test;

/** DIRECT STYLE OVER EFFECTS WRITTEN AS GADTs: a plain method, its effects in its {@code throws}, its operations
 * records; a {@code for} with {@code perform} in it, its answers in locals, the loop going by them. The program is an
 * {@code okay.rows} program of the same effects: the same handlers answer it — once, twice, or in a set. */
class GadtDirectTest {

  sealed interface Prices<T> extends Performed<T, Prices<?>, Prices.Fx> {
    final class Fx extends Suspend implements Marks<Prices<?>> {}
    record Price(String item) implements Prices<Long> {}
    record InStock(String item) implements Prices<Boolean> {}
  }

  sealed interface Ask<T> extends Performed<T, Ask<?>, Ask.Fx> {
    final class Fx extends Suspend implements Marks<Ask<?>> {}
    record Confirm(String question) implements Ask<Boolean> {}
  }

  sealed interface Flip<T> extends Performed<T, Flip<?>, Flip.Fx> {
    final class Fx extends Suspend implements Marks<Flip<?>> {}
    record Coin() implements Flip<Boolean> {}
  }

  static final Map<String, Long> TABLE = Map.of("tea", 4L, "cup", 11L, "kettle", 120L);

  // ---- programs: plain Java

  /** a for with perform in it: the answers are locals, the loop goes by them */
  static long total(List<String> cart) throws Prices.Fx {
    long sum = 0;
    for (String item : cart) {
      if (!Direct.perform(new Prices.InStock(item))) continue;
      long price = Direct.perform(new Prices.Price(item));
      if (price > 100) break;
      sum += price;
    }
    return sum;
  }

  /** two effects, a method that calls a method */
  static String shop(List<String> cart) throws Prices.Fx, Ask.Fx {
    long sum = total(cart);
    if (sum > 10 && !Direct.perform(new Ask.Confirm("pay " + sum + "?"))) return "declined";
    return "paid " + sum;
  }

  // ---- handlers: okay.rows' own, one function each

  static <A, R> Comp<A, R> priced(List<String> queries, Comp<A, ? super Eff<Prices<?>, R>> body) {
    return handle((op, k) -> switch (op) {
      case Prices.Price p -> { queries.add("price " + p.item()); yield k.resume(p, TABLE.getOrDefault(p.item(), 0L)); }
      case Prices.InStock s -> { queries.add("stock " + s.item()); yield k.resume(s, TABLE.containsKey(s.item())); }
    }, body);
  }

  static <R> Comp<String, R> asking(boolean yes, Comp<String, ? super Eff<Ask<?>, R>> body) {
    return handle((op, k) -> switch (op) {
      case Ask.Confirm c -> k.resume(c, yes).map(r -> r + " (asked: " + c.question() + ")");
    }, body);
  }

  @Test
  void aForWithPerformInIt() {
    List<String> queries = new ArrayList<>();
    Comp<Long, Eff<Prices<?>, Pure>> p =
        Direct.effects(Prices.Fx.class).of(() -> total(List.of("tea", "spoon", "cup", "kettle", "tea")));
    assertEquals(15L, run(priced(queries, p)));
    assertEquals(List.of("stock tea", "price tea", "stock spoon", "stock cup", "price cup", "stock kettle",
        "price kettle"), queries);
  }

  @Test
  void twoEffectsAndAHandlerThatGoesOnAfterTheRest() {
    List<String> queries = new ArrayList<>();
    Comp<String, Eff<Prices<?>, Eff<Ask<?>, Pure>>> p =
        Direct.effects(Prices.Fx.class, Ask.Fx.class).of(() -> shop(List.of("tea", "cup")));
    assertEquals("paid 15 (asked: pay 15?)", run(asking(true, priced(queries, p))));
    assertEquals("declined (asked: pay 15?)", run(asking(false, priced(queries, p))));
  }

  /** a continuation resumed twice: the frames of the plain method are entered again, each path its own locals */
  static List<String> flips() throws Flip.Fx {
    String seen = "";
    for (int i = 0; i < 2; i++) seen += Direct.perform(new Flip.Coin()) ? "H" : "T";
    return List.of(seen);
  }

  @Test
  void aContinuationResumedTwice() {
    Comp<List<String>, Eff<Flip<?>, Pure>> p = Direct.effects(Flip.Fx.class).of(GadtDirectTest::flips);
    Comp<List<String>, Pure> all = handle((op, k) -> switch (op) {
      case Flip.Coin c -> k.resume(c, true).flatMap(a -> k.resume(c, false).map(b ->
          Stream.concat(a.stream(), b.stream()).toList()));
    }, p);
    assertEquals(List.of("HH", "HT", "TH", "TT"), run(all));
  }

  /** a long loop: no stack is taken by the operations */
  static long count(int n) throws Prices.Fx {
    long sum = 0;
    for (int i = 0; i < n; i++) sum += Direct.perform(new Prices.Price("tea"));
    return sum;
  }

  @Test
  void aLongLoop() {
    Comp<Long, Eff<Prices<?>, Pure>> p = Direct.effects(Prices.Fx.class).of(() -> count(200_000));
    Comp<Long, Pure> handled = handle((op, k) -> switch (op) {
      case Prices.Price price -> k.resume(price, 4L);
      case Prices.InStock s -> k.resume(s, true);
    }, p);
    assertEquals(800_000L, run(handled));
  }

  /** a handler with a state, one function: its state goes through the plain loop, in place */
  @Test
  void aHandlerWithAStateUnderAPlainLoop() {
    Comp<Long, Eff<Prices<?>, Pure>> p = Direct.effects(Prices.Fx.class).of(() -> count(100_000));
    Comp<String, Pure> counted = okay.rows.Effects.<Prices<?>, Integer, Long, String, Pure>handle(0,
        (asked, op, k) -> switch (op) {
          case Prices.Price price -> k.resume(price, 4L, asked + 1);
          case Prices.InStock s -> k.resume(s, true);
        }, (asked, sum) -> sum + " in " + asked, p);
    assertEquals("400000 in 100000", run(counted));
  }

  /** a clause that fails: the program's failure, the method's finally run */
  static String guarded(List<String> log) throws Prices.Fx {
    try {
      return "price " + Direct.perform(new Prices.Price("tea"));
    } finally {
      log.add("finally");
    }
  }

  @Test
  void aClauseThatFails() {
    List<String> log = new ArrayList<>();
    Comp<String, Eff<Prices<?>, Pure>> p = Direct.effects(Prices.Fx.class).of(() -> guarded(log));
    okay.rows.Clause<Prices<?>, String, Pure> failing = (op, k) -> { throw new IllegalStateException("no prices"); };
    Comp<String, Pure> handled = handle(failing, p);
    assertEquals("no prices",
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> run(handled)).getMessage());
  }

  /** a set asked from a plain method: a program of okay.rows embedded, its questions one query */
  static long batched(List<String> cart) throws Prices.Fx {
    List<Long> prices = Direct.effects(Prices.Fx.class).embed(
        okay.rows.Effects.performAll(cart.stream().map(Prices.Price::new).toList()));
    long sum = 0;
    for (long price : prices) sum += price;
    return sum > 100 ? sum - Direct.perform(new Prices.Price("tea")) : sum;
  }

  @Test
  void aSetAskedFromAPlainMethod() {
    List<String> queries = new ArrayList<>();
    Comp<Long, Eff<Prices<?>, Pure>> p =
        Direct.effects(Prices.Fx.class).of(() -> batched(List.of("tea", "cup", "kettle")));
    Comp<Long, Pure> handled = handle((op, k) -> switch (op) {
      case Prices.Price price -> {
        queries.add("price " + price.item());
        yield k.resume(price, TABLE.get(price.item()));
      }
      case Prices.InStock s -> k.resume(s, true);
    }, (ops, k) -> {
      queries.add("all of " + ops.size());
      for (Prices<?> op : ops) switch (op) {
        case Prices.Price price -> k.answer(price, TABLE.get(price.item()));
        case Prices.InStock s -> k.answer(s, true);
      }
      return k.resume();
    }, p);
    assertEquals(131L, run(handled));
    assertEquals(List.of("all of 3", "price tea"), queries);
  }

  /** the same operations asked by a program written as data: one handler for both ways */
  @Test
  void theSameOperationsAsData() {
    List<String> queries = new ArrayList<>();
    Comp<Long, Eff<Prices<?>, ?>> data = okay.rows.Effects.perform(new Prices.Price("cup"));
    assertEquals(11L, run(priced(queries, data)));
  }

}
