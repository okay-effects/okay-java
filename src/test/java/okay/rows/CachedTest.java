package okay.rows;

import static okay.rows.Effects.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import okay.effect.Eff;
import okay.effect.Operation;
import okay.effect.Pure;
import org.junit.jupiter.api.Test;

/** {@code cached}: each operation asked once in a run — a handler of any effect, between the program and the
 * effect's own handler; and a handler with a state that takes a set ({@link StateBatch}). */
class CachedTest {

  sealed interface Prices<T> extends Operation<T, Prices<?>> {
    record Price(String item) implements Prices<Long> {}
    record InStock(String item) implements Prices<Boolean> {}
  }

  sealed interface Flip<T> extends Operation<T, Flip<?>> {
    record Coin() implements Flip<Boolean> {}
  }

  static final Map<String, Long> TABLE = Map.of("tea", 4L, "cup", 11L, "kettle", 120L);
  static final List<String> CART = List.of("tea", "kettle", "tea", "cup", "tea", "kettle");

  /** a handler with a state and a clause for sets: its state the number of queries it made */
  static <A, R> Comp<String, R> counted(List<String> queries, Comp<A, ? super Eff<Prices<?>, R>> body) {
    return handle(0, (made, op, k) -> {
      queries.add(op.toString());
      return switch (op) {
        case Prices.Price p -> k.resume(p, TABLE.get(p.item()), made + 1);
        case Prices.InStock s -> k.resume(s, TABLE.containsKey(s.item()), made + 1);
      };
    }, (made, ops, k) -> {
      queries.add("all of " + ops);
      for (Prices<?> op : ops) switch (op) {
        case Prices.Price p -> k.answer(p, TABLE.get(p.item()));
        case Prices.InStock s -> k.answer(s, TABLE.containsKey(s.item()));
      }
      return k.resume(made + 1);
    }, (made, value) -> value + " in " + made, body);
  }

  /** one by one, a loop that goes by the answers */
  static Comp<Long, Eff<Prices<?>, ?>> loop(List<String> cart) {
    return fold(cart, 0L, (sum, item) -> perform(new Prices.InStock(item)).flatMap(in ->
        in ? perform(new Prices.Price(item)).map(p -> sum + p) : pure(sum)));
  }

  @Test
  void aHandlerWithAStateTakesASet() {
    List<String> queries = new ArrayList<>();
    Comp<List<Long>, Eff<Prices<?>, ?>> prices = gather(g -> {
      for (String item : List.of("tea", "cup")) g.perform(new Prices.Price(item));
    });
    assertEquals("[4, 11] in 1", run(counted(queries, prices)));
    assertEquals(List.of("all of [Price[item=tea], Price[item=cup]]"), queries);
  }

  @Test
  void eachOperationAskedOnce() {
    List<String> queries = new ArrayList<>();
    assertEquals("263 in 12", run(counted(queries, loop(CART))));
    queries.clear();
    assertEquals("263 in 6", run(counted(queries, cached(loop(CART)))));
    assertEquals(List.of("InStock[item=tea]", "Price[item=tea]", "InStock[item=kettle]", "Price[item=kettle]",
        "InStock[item=cup]", "Price[item=cup]"), queries);
  }

  /** a set goes on as the set of what is not known, each once; a set all known asks nothing */
  @Test
  void aSetGoesOnAsWhatIsNotKnown() {
    List<String> queries = new ArrayList<>();
    Comp<Long, Eff<Prices<?>, ?>> p = together(t -> {
      Var<Long> tea = t.perform(new Prices.Price("tea"));
      Var<List<Long>> all = t.ask(tea, x -> gather(g -> {
        for (String item : CART) g.perform(new Prices.Price(item));
      }));
      Var<List<Long>> again = t.ask(all, xs -> gather(g -> {
        for (String item : CART) g.perform(new Prices.Price(item));
      }));
      return t.yield(all, again, (a, b) -> a.stream().mapToLong(Long::longValue).sum() + b.size());
    });
    assertEquals("269 in 2", run(counted(queries, cached(p))));
    assertEquals(List.of("Price[item=tea]", "all of [Price[item=kettle], Price[item=cup]]"), queries);
  }

  /** each run of the program starts with nothing remembered */
  @Test
  void eachRunItsOwn() {
    List<String> queries = new ArrayList<>();
    Comp<String, Pure> p = counted(queries, cached(loop(List.of("tea", "tea"))));
    assertEquals("8 in 2", run(p));
    assertEquals("8 in 2", run(p));
    assertEquals(4, queries.size());
  }

  /** the effect stays in the row: a handler between the cache and the effect's own sees what is really asked */
  @Test
  void theEffectStaysInTheRow() {
    List<String> queries = new ArrayList<>();
    List<Object> seen = new ArrayList<>();
    Comp<Long, Eff<Prices<?>, ?>> watched = observe(Prices.class, op -> {
      seen.add(op);
      return pure(null);
    }, cached(loop(List.of("tea", "tea", "tea"))));
    assertEquals("12 in 2", run(counted(queries, watched)));
    assertEquals(2, seen.size());
  }

  /** under a handler that resumes twice further out: what is known is shared by the paths */
  @Test
  void pathsShareWhatIsKnown() {
    List<String> queries = new ArrayList<>();
    Comp<List<Long>, Eff<Prices<?>, Eff<Flip<?>, ?>>> p =
        up(perform(new Flip.Coin())).flatMap(heads ->
            perform(new Prices.Price(heads ? "tea" : "cup")).flatMap(a ->
                perform(new Prices.Price("tea")).map(b -> List.of(a + b))));
    Comp<String, Eff<Flip<?>, ?>> counted = counted(queries, cached(p));
    Comp<String, Pure> all = handle((op, k) -> switch (op) {
      case Flip.Coin c -> k.resume(c, true).flatMap(a -> k.resume(c, false).map(b -> a + " | " + b));
    }, counted);
    assertEquals("[8] in 1 | [15] in 1", run(all));
    assertEquals(List.of("Price[item=tea]", "Price[item=cup]"), queries);
  }

  /** a long loop of one operation: one query, constant stack */
  @Test
  void aLongLoop() {
    List<String> queries = new ArrayList<>();
    List<String> many = Stream.generate(() -> "tea").limit(100_000).toList();
    Comp<Long, Eff<Prices<?>, ?>> sum =
        fold(many, 0L, (acc, item) -> perform(new Prices.Price(item)).map(p -> acc + p));
    assertEquals("400000 in 1", run(counted(queries, cached(sum))));
  }
}
