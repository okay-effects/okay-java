package sample;

import static okay.rows.Effects.handle;
import static okay.rows.Effects.pure;
import static okay.rows.Effects.run;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import okay.resumable.Direct;
import okay.resumable.Suspend;
import okay.rows.Cell;
import okay.rows.Comp;
import okay.rows.Eff;
import okay.rows.Handler;
import okay.rows.InPlace;
import okay.rows.K;
import okay.rows.Op;

/** A shop in direct style: plain methods, their effects in throws, answered by okay.rows handlers */
public final class Shop {
  private Shop() {}

  /** prices, answered in place from a price list */
  public abstract static sealed class Prices extends Suspend {
    public static final class Price extends Prices implements Op<Prices, Integer, Price> {
      final String item;

      public Price(String item) { this.item = item; }
    }
  }

  /** a choice of delivery: every option tried */
  public abstract static sealed class Delivery extends Suspend {
    public static final class Choose extends Delivery implements Op<Delivery, String, Choose> {}
  }

  record Quote(String delivery, int total) {}

  static int price(String item) throws Prices {
    return Direct.perform(new Prices.Price(item));
  }

  static Quote quote(List<String> cart) throws Prices, Delivery {
    int total = 0;
    for (String item : cart) total += price(item);
    String delivery = Direct.perform(new Delivery.Choose());
    return new Quote(delivery, delivery.equals("courier") ? total + 15 : total);
  }

  static <A, R> Comp<A, R> priced(Map<String, Integer> list, Comp<A, ? super Eff<Prices, R>> p) {
    return handle(Prices.class, new InPlace<Prices, Map<String, Integer>, A, A, R>() {
      public Comp<A, R> ret(Map<String, Integer> s, A a) { return pure(a); }
      public Cell.Answered answer(Cell<Map<String, Integer>> state, Prices op) {
        return switch (op) {
          case Prices.Price p -> state.answer(p, state.value.get(p.item));
        };
      }
    }, list, p);
  }

  static <A, R> Comp<List<A>, R> everyDelivery(Comp<A, ? super Eff<Delivery, R>> p) {
    return handle(Delivery.class, new Handler<Delivery, A, List<A>, R>() {
      public Comp<List<A>, R> ret(A a) { return pure(List.of(a)); }
      public Comp<List<A>, R> on(Delivery op, K<List<A>, R> k) {
        return switch (op) {
          case Delivery.Choose c -> k.resume(c, "pickup").flatMap(a -> k.resume(c, "courier").map(b -> {
            List<A> all = new ArrayList<>(a);
            all.addAll(b);
            return all;
          }));
        };
      }
    }, p);
  }

  static List<Quote> quotes(List<String> cart) {
    var program = Direct.row(Delivery.class, Prices.class).of(() -> quote(cart));
    return run(priced(Map.of("tea", 4, "cup", 11), everyDelivery(program)));
  }

  public static void main(String[] args) {
    System.out.println(quotes(List.of("tea", "cup", "tea")));
  }
}
