package sample;

import static okay.rows.Sets.*;

import java.util.List;
import java.util.Map;
import okay.effect.Eff;
import okay.effect.Operation;
import okay.resumable.Direct;
import okay.resumable.Direct.Asks;
import okay.rows.Comp;
import okay.rows.Sets;

/** A till in plain Java, its rows SETS: a method says the effects it asks in its type, nothing is declared for an
 * effect, no {@code throws}; the handlers are functions, put once, at the boundary the class {@link Desk} names.
 * It needs no plugin (the rest of such a body is resumed once); {@link Shop} is the plugin's own kind. */
public final class Till {
  private Till() {}

  // ---- effects: a sealed interface, each operation naming its answer and its effect
  public sealed interface Prices {
    record Price(String item) implements Prices, Operation<Long, Prices> {}
    record InStock(String item) implements Prices, Operation<Boolean, Prices> {}
  }
  public sealed interface Ask {
    record Confirm(String question) implements Ask, Operation<Boolean, Ask> {}
  }

  // ---- programs: plain methods
  static <R extends Prices> long total(Asks<R> d, List<String> cart) {
    long sum = 0;
    for (String item : cart) if (d.perform(new Prices.InStock(item))) sum += d.perform(new Prices.Price(item));
    return sum;
  }

  static <R extends Prices & Ask> Comp<String, R> checkout(List<String> cart) {
    return Direct.body(d -> {
      long sum = total(d, cart);
      if (sum > 100 && !d.perform(new Ask.Confirm("pay " + sum + "?"))) return "declined";
      return "paid " + sum;
    });
  }

  // ---- handlers: one function each
  static <A, R> Comp<A, R> priced(Map<String, Long> table, Comp<A, ? super Eff<Prices, R>> body) {
    return answering(op -> switch (op) {
      case Prices.Price p -> p.answer(table.getOrDefault(p.item(), 0L));
      case Prices.InStock s -> s.answer(table.containsKey(s.item()));
    }, body);
  }

  static <A, R> Comp<A, R> asking(boolean yes, Comp<A, ? super Eff<Ask, R>> body) {
    return answering(op -> switch (op) { case Ask.Confirm c -> c.answer(yes); }, body);
  }

  /** the boundary, written once: its type parameter names the set of the effects it handles */
  public static final class Desk<V extends Prices & Ask> {
    private final Map<String, Long> table;

    public Desk(Map<String, Long> table) { this.table = table; }

    public <A> A run(boolean yes, Comp<A, ? super V> program) {
      var row = Sets.<V>exactly(Desk.class).row(of(Prices.class, Ask.class));
      return Sets.run(asking(yes, priced(table, row.of(program))));
    }
  }

  static final Desk<?> DESK = new Desk<>(Map.of("tea", 4L, "cup", 11L, "kettle", 120L));

  public static void main(String[] args) {
    System.out.println(DESK.run(true, checkout(List.of("tea", "cup"))));
  }
}
