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

/** The sugar, used as a consumer would: an effect as a plain interface, a row named once on a class. */
class ShopTest {
  /** an effect written as a plain interface: PricesOp is generated from it */
  @Effect
  interface Prices {
    long price(String item);
    boolean inStock(String item);
    void seen(String item);
  }

  /** an effect written by hand, the light form: its operations are records */
  sealed interface Ask<T> extends Operation<T, Ask<?>> {
    record Confirm(String question) implements Ask<Boolean> {}
  }

  /** programs of one row, named once: CheckoutOps is generated, every operation at its place */
  @Uses({Prices.class, Ask.class})
  static final class Checkout {
    static Comp<Long, Eff<PricesOp<?>, Eff<Ask<?>, ?>>> total(List<String> cart) {
      return fold(cart, 0L, (sum, item) ->
          seen(item).flatMap(done -> inStock(item)).flatMap(in ->
              in ? price(item).map(p -> sum + p) : pure(sum)));
    }

    static Comp<String, Eff<PricesOp<?>, Eff<Ask<?>, ?>>> checkout(List<String> cart) {
      return total(cart).flatMap(sum ->
          sum <= 100 ? pure("paid " + sum)
              : confirm("pay " + sum + "?").map(yes -> yes ? "paid " + sum : "declined"));
    }
  }

  /** a handler is an implementation of the interface */
  static final class Table implements Prices {
    final Map<String, Long> table;
    final List<String> seen = new ArrayList<>();
    Table(Map<String, Long> table) { this.table = table; }
    public long price(String item) { return table.get(item); }
    public boolean inStock(String item) { return table.containsKey(item); }
    public void seen(String item) { seen.add(item); }
  }

  static <A, R> Comp<A, R> asking(boolean yes, Comp<A, ? super Eff<Ask<?>, R>> body) {
    return answering(op -> switch (op) {
      case Ask.Confirm c -> c.answer(yes);
    }, body);
  }

  @Test
  void anImplementationIsTheHandler() {
    Table shop = new Table(Map.of("tea", 4L, "cup", 11L));
    String paid = run(asking(true, PricesOp.handledBy(shop, Checkout.checkout(List.of("tea", "cup", "gold")))));
    assertEquals("paid 15", paid);
    assertEquals(List.of("tea", "cup", "gold"), shop.seen);
  }

  @Test
  void theSecondEffectOfTheRowIsAskedWithNoUp() {
    Table shop = new Table(Map.of("kettle", 120L));
    assertEquals("declined", run(asking(false, PricesOp.handledBy(shop, Checkout.checkout(List.of("kettle"))))));
    assertEquals("paid 120", run(asking(true, PricesOp.handledBy(shop, Checkout.checkout(List.of("kettle"))))));
  }

  @Test
  void theGeneratedRecordsAreTheEffectAGeneralHandlerSwitchesOver() {
    // a handler written by hand over the generated records: it takes the rest, and counts the questions
    var counted = handle(0L, (n, op, k) -> switch (op) {
      case PricesOp.Price p -> k.resume(p, 1L, n + 1);
      case PricesOp.InStock s -> k.resume(s, true, n + 1);
      case PricesOp.Seen s -> k.resume(s, null, n + 1);
    }, (n, sum) -> n, Checkout.total(List.of("a", "b")));
    assertEquals(6L, run(asking(true, counted)));
  }
}
