package okay.sugar;

import static okay.rows.Effects.*;
import static okay.sugar.CheckoutOps.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import okay.effect.Eff;
import okay.effect.Operation;
import okay.rows.Comp;
import org.junit.jupiter.api.Test;

/** The sugar, used as a consumer would: effects written by hand, their functions and handlers generated. */
class ShopTest {
  /** an effect, written as okay.rows has it: PricesOps is generated beside it */
  @Effect
  sealed interface Prices<T> extends Operation<T, Prices<?>> {
    record Price(String item) implements Prices<Long> {}
    record InStock(String item) implements Prices<Boolean> {}
    record Seen(String item) implements Prices<Void> {}
  }

  /** an effect with an operation of any answer: AskOps is generated, its fail(..) generic */
  @Effect
  sealed interface Ask<T> extends Operation<T, Ask<?>> {
    record Confirm(String question) implements Ask<Boolean> {}
    record Fail<T>(String why) implements Ask<T> {}
  }

  /** programs of one row, named once: CheckoutOps is generated, every operation at its place */
  @Uses({Prices.class, Ask.class})
  static final class Checkout {
    static Comp<Long, Eff<Prices<?>, Eff<Ask<?>, ?>>> total(List<String> cart) {
      return fold(cart, 0L, (sum, item) ->
          seen(item).flatMap(done -> inStock(item)).flatMap(in ->
              in ? price(item).map(p -> sum + p) : fail(item + " is out")));
    }

    static Comp<String, Eff<Prices<?>, Eff<Ask<?>, ?>>> checkout(List<String> cart) {
      return total(cart).flatMap(sum ->
          sum <= 100 ? pure("paid " + sum)
              : confirm("pay " + sum + "?").map(yes -> yes ? "paid " + sum : "declined"));
    }
  }

  /** a handler that only answers is an implementation of the generated interface */
  static final class Table implements PricesOps.Handler {
    final Map<String, Long> table;
    final List<String> seen = new ArrayList<>();
    Table(Map<String, Long> table) { this.table = table; }
    public long price(String item) { return table.get(item); }
    public boolean inStock(String item) { return table.containsKey(item); }
    public void seen(String item) { seen.add(item); }
  }

  /** a handler that needs the rest is written as ever: a switch over the effect's own records */
  static <R> Comp<String, R> asking(boolean yes, Comp<String, ? super Eff<Ask<?>, R>> body) {
    return handle((op, k) -> switch (op) {
      case Ask.Confirm c -> k.resume(c, yes);
      case Ask.Fail<?> f -> k.unwind().map(left -> "failed: " + f.why());
    }, body);
  }

  static String shop(Table table, boolean yes, List<String> cart) {
    return run(asking(yes, PricesOps.handledBy(table, Checkout.checkout(cart))));
  }

  @Test
  void anImplementationOfTheGeneratedInterfaceIsTheHandler() {
    Table table = new Table(Map.of("tea", 4L, "cup", 11L));
    assertEquals("paid 15", shop(table, true, List.of("tea", "cup")));
    assertEquals(List.of("tea", "cup"), table.seen);
  }

  @Test
  void theSecondEffectOfTheRowIsAskedWithNoUp() {
    Table table = new Table(Map.of("kettle", 120L));
    assertEquals("declined", shop(table, false, List.of("kettle")));
    assertEquals("paid 120", shop(table, true, List.of("kettle")));
  }

  @Test
  void anOperationOfAnyAnswerIsAGenericFunction() {
    Table table = new Table(Map.of("tea", 4L));
    assertEquals("failed: gold is out", shop(table, true, List.of("tea", "gold", "cup")));
    assertEquals(List.of("tea", "gold"), table.seen);
  }

  @Test
  void theFunctionsOfOneEffectAreThereWithoutARow() {
    Comp<Long, Eff<Prices<?>, ?>> twice =
        PricesOps.price("tea").flatMap(p -> PricesOps.price("tea").map(q -> p + q));
    assertEquals(8L, run(PricesOps.handledBy(new Table(Map.of("tea", 4L)), twice)));
  }

  @Test
  void aGeneralHandlerSwitchesOverTheSameRecords() {
    var counted = handle(0L, (n, op, k) -> switch (op) {
      case Prices.Price p -> k.resume(p, 1L, n + 1);
      case Prices.InStock s -> k.resume(s, true, n + 1);
      case Prices.Seen s -> k.resume(s, null, n + 1);
    }, (n, sum) -> n, Checkout.total(List.of("a", "b")));
    assertEquals("6", run(asking(true, counted.map(n -> "" + n))));
  }
}
