package okay.rows;

import static okay.rows.Effects.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import okay.effect.Eff;
import okay.effect.Operation;
import okay.rows.std.State;
import org.junit.jupiter.api.Test;

/** {@code gather}: a plain loop of questions — a word of a block ({@code t.gather}), and the block that is nothing
 * but one loop ({@code Effects.gather}). One mechanism: the questions of a loop are questions of the block. */
class GatherTest {

  sealed interface Prices<T> extends Operation<T, Prices<?>> {
    record Price(String item) implements Prices<Long> {}
    record InStock(String item) implements Prices<Boolean> {}
    record Discount(long sum) implements Prices<Long> {}
  }

  static final Map<String, Long> TABLE = Map.of("tea", 4L, "cup", 11L, "kettle", 120L);
  static final List<String> CART = List.of("tea", "kettle", "cup");

  /** answers a set by one query, and notes its queries */
  static <A, R> Comp<A, R> shop(List<String> queries, Comp<A, ? super Eff<Prices<?>, R>> body) {
    return handle((op, k) -> switch (op) {
      case Prices.Price p -> { queries.add("price " + p.item()); yield k.resume(p, TABLE.getOrDefault(p.item(), 0L)); }
      case Prices.InStock s -> { queries.add("stock " + s.item()); yield k.resume(s, TABLE.containsKey(s.item())); }
      case Prices.Discount d -> { queries.add("discount " + d.sum()); yield k.resume(d, d.sum() / 10); }
    }, (ops, k) -> {
      TreeSet<String> asked = new TreeSet<>();
      for (Prices<?> op : ops) switch (op) {
        case Prices.Price p -> { asked.add("price " + p.item()); k.answer(p, TABLE.getOrDefault(p.item(), 0L)); }
        case Prices.InStock s -> { asked.add("stock " + s.item()); k.answer(s, TABLE.containsKey(s.item())); }
        case Prices.Discount d -> { asked.add("discount " + d.sum()); k.answer(d, d.sum() / 10); }
      }
      queries.add("all of " + asked);
      return k.resume();
    }, body);
  }

  /** the block that is one loop: the list of the answers */
  @Test
  void aPlainForWithPerformInIt() {
    List<String> queries = new ArrayList<>();
    Comp<List<Long>, Eff<Prices<?>, ?>> prices = gather(g -> {
      for (String item : CART) g.perform(new Prices.Price(item));
    });
    assertEquals(List.of(4L, 120L, 11L), run(shop(queries, prices)));
    assertEquals(List.of("all of [price cup, price kettle, price tea]"), queries);
  }

  /** the loop is Java's: its own conditions and nesting decide what is asked */
  @Test
  void theLoopIsJavas() {
    List<String> queries = new ArrayList<>();
    Comp<List<Long>, Eff<Prices<?>, ?>> prices = gather(g -> {
      for (String item : CART) {
        if (item.startsWith("c")) continue;
        for (int copy = 0; copy < 2; copy++) g.perform(new Prices.Price(item));
      }
    });
    assertEquals(List.of(4L, 4L, 120L, 120L), run(shop(queries, prices)));
  }

  /** a loop in a block with other questions: one name among the names, one round, one query — and a question made
   * of the loop's answers in the next */
  @Test
  void aLoopAmongTheQuestionsOfABlock() {
    List<String> queries = new ArrayList<>();
    Comp<String, Eff<Prices<?>, ?>> p = together(t -> {
      Var<List<Long>> prices = t.gather(g -> {
        for (String item : CART) g.perform(new Prices.Price(item));
      });
      Var<Boolean> kettle = t.perform(new Prices.InStock("kettle"));
      Var<Long> sum = prices.map(ps -> ps.stream().mapToLong(Long::longValue).sum());
      Var<Long> off = t.ask(sum, s -> perform(new Prices.Discount(s)));
      return t.yield(sum, off, kettle, (s, o, in) -> (s - o) + (in ? " with a kettle" : ""));
    });
    assertEquals("122 with a kettle", run(shop(queries, p)));
    assertEquals(List.of("all of [price cup, price kettle, price tea, stock kettle]", "discount 135"), queries);
  }

  /** two loops of two types in one block: still one query */
  @Test
  void twoLoopsOfTwoTypes() {
    List<String> queries = new ArrayList<>();
    Comp<Long, Eff<Prices<?>, ?>> p = together(t -> {
      Var<List<Long>> prices = t.gather(g -> {
        for (String item : CART) g.perform(new Prices.Price(item));
      });
      Var<List<Boolean>> stock = t.gather(g -> {
        for (String item : CART) g.perform(new Prices.InStock(item));
      });
      return t.yield(prices, stock, (ps, in) -> {
        long sum = 0;
        for (int i = 0; i < ps.size(); i++) if (in.get(i)) sum += ps.get(i);
        return sum;
      });
    });
    assertEquals(135L, run(shop(queries, p)));
    assertEquals(1, queries.size());
  }

  /** a computation made of operations asked in the loop: a question as any other */
  @Test
  void aComputationAskedInTheLoop() {
    List<String> queries = new ArrayList<>();
    Comp<List<Long>, Eff<Prices<?>, ?>> doubled = gather(g -> {
      for (String item : CART) g.ask(perform(new Prices.Price(item)).map(p -> p * 2));
    });
    assertEquals(List.of(8L, 240L, 22L), run(shop(queries, doubled)));
    assertEquals(3, queries.size());
  }

  /** an effect with type parameters of its own, and another effect of the row */
  @Test
  void otherEffects() {
    Comp<List<Void>, Eff<State<Integer>, ?>> puts = gather(g -> {
      for (int i = 1; i <= 3; i++) g.perform(new State.Put<>(i));
    });
    assertEquals(3, run(State.run(0, puts)).state());
    List<String> queries = new ArrayList<>();
    Comp<List<Integer>, Eff<Prices<?>, Eff<State<Integer>, ?>>> counts = gather(g -> {
      for (int i = 0; i < 2; i++) g.ask(up(State.get()));
    });
    assertEquals(List.of(5, 5), run(State.run(5, shop(queries, counts))).value());
  }

  /** NOT together: computations asked one after another, as far as the answers say */
  @Test
  void theFirstWantedAndNoFurther() {
    List<String> queries = new ArrayList<>();
    List<Comp<Long, Eff<Prices<?>, ?>>> prices = CART.stream().map(i -> perform(new Prices.Price(i))).toList();
    Comp<Optional<Long>, Eff<Prices<?>, ?>> big = first(prices, p -> p > 100);
    assertEquals(Optional.of(120L), run(shop(queries, big)));
    assertEquals(List.of("price tea", "price kettle"), queries);
    queries.clear();
    Comp<Boolean, Eff<Prices<?>, ?>> cheap = all(prices, p -> p < 100);
    assertEquals(false, run(shop(queries, cheap)));
    assertEquals(2, queries.size());
    queries.clear();
    Comp<Boolean, Eff<Prices<?>, ?>> free = any(prices, p -> p == 0);
    assertEquals(false, run(shop(queries, free)));
    assertEquals(3, queries.size());
  }
}
