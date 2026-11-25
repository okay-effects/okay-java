package okay.rows;

import static okay.rows.Effects.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;
import okay.effect.Eff;
import okay.effect.Operation;
import okay.effect.Pure;
import okay.rows.std.State;
import org.junit.jupiter.api.Test;

/** A set of operations asked at once: a handler with a clause for sets ({@link Batch}) takes them together, any
 * other answers them one by one — the program is the same. */
class BatchTest {

  sealed interface Prices<T> extends Operation<T, Prices<?>> {
    record Price(String item) implements Prices<Long> {}
    record InStock(String item) implements Prices<Boolean> {}
  }

  sealed interface Flip<T> extends Operation<T, Flip<?>> {
    record Coin() implements Flip<Boolean> {}
  }

  static final Map<String, Long> TABLE = Map.of("tea", 4L, "cup", 11L, "kettle", 120L);
  static final List<String> CART = List.of("tea", "kettle", "tea", "cup");

  /** the program: a plain loop, the prices of a cart */
  static Comp<Long, Eff<Prices<?>, ?>> total(List<String> cart) {
    Comp<List<Long>, Eff<Prices<?>, ?>> prices = gather(g -> {
      for (String item : cart) g.perform(new Prices.Price(item));
    });
    return prices.map(ps -> ps.stream().mapToLong(Long::longValue).sum());
  }

  /** one by one: every operation a query */
  static <A, R> Comp<A, R> oneByOne(List<String> queries, Comp<A, ? super Eff<Prices<?>, R>> body) {
    return handle((op, k) -> switch (op) {
      case Prices.Price p -> { queries.add("price " + p.item()); yield k.resume(p, TABLE.get(p.item())); }
      case Prices.InStock s -> { queries.add("stock " + s.item()); yield k.resume(s, TABLE.containsKey(s.item())); }
    }, body);
  }

  /** a set at once: ONE query for the items of the set, each repeated one once */
  static <A, R> Comp<A, R> batched(List<String> queries, Comp<A, ? super Eff<Prices<?>, R>> body) {
    return handle((op, k) -> switch (op) {
      case Prices.Price p -> { queries.add("price " + p.item()); yield k.resume(p, TABLE.get(p.item())); }
      case Prices.InStock s -> { queries.add("stock " + s.item()); yield k.resume(s, TABLE.containsKey(s.item())); }
    }, (ops, k) -> {
      TreeSet<String> items = new TreeSet<>();
      for (Prices<?> op : ops) switch (op) {
        case Prices.Price p -> items.add(p.item());
        case Prices.InStock s -> items.add(s.item());
      }
      queries.add("all of " + items);
      for (Prices<?> op : ops) switch (op) {
        case Prices.Price p -> k.answer(p, TABLE.get(p.item()));
        case Prices.InStock s -> k.answer(s, TABLE.containsKey(s.item()));
      }
      return k.resume();
    }, body);
  }

  @Test
  void theSameProgramOneByOneAndAtOnce() {
    List<String> queries = new ArrayList<>();
    assertEquals(139L, run(oneByOne(queries, total(CART))));
    assertEquals(List.of("price tea", "price kettle", "price tea", "price cup"), queries);
    queries.clear();
    assertEquals(139L, run(batched(queries, total(CART))));
    assertEquals(List.of("all of [cup, kettle, tea]"), queries);
  }

  @Test
  void anOperationAloneGoesToItsClause() {
    List<String> queries = new ArrayList<>();
    Comp<Long, Eff<Prices<?>, ?>> one = perform(new Prices.Price("cup"));
    assertEquals(11L, run(batched(queries, one)));
    assertEquals(List.of("price cup"), queries);
  }

  /** the answers are the operations', in their order, each of its own type */
  @Test
  void theAnswersInTheOperationsOrder() {
    List<String> queries = new ArrayList<>();
    Comp<List<Boolean>, Eff<Prices<?>, ?>> stock =
        performAll(Stream.of("cup", "spoon", "tea").map(Prices.InStock::new).toList());
    assertEquals(List.of(true, false, true), run(batched(queries, stock)));
    assertEquals(1, queries.size());
  }

  /** a set, then a choice made by its answers, then another set: two queries */
  @Test
  void setsBetweenDecisions() {
    List<String> queries = new ArrayList<>();
    Comp<Long, Eff<Prices<?>, ?>> p =
        performAll(CART.stream().map(Prices.InStock::new).toList())
            .flatMap(in -> total(in.contains(false) ? List.of() : List.of("cup", "kettle")));
    assertEquals(131L, run(batched(queries, p)));
    assertEquals(List.of("all of [cup, kettle, tea]", "all of [cup, kettle]"), queries);
  }

  /** an observer on the way sees every operation: the set is asked one by one */
  @Test
  void underAnObserverOneByOne() {
    List<String> queries = new ArrayList<>();
    List<Object> seen = new ArrayList<>();
    Comp<Long, Eff<Prices<?>, ?>> observed = observe(Prices.class, op -> {
      seen.add(op);
      return pure(null);
    }, total(CART));
    assertEquals(139L, run(batched(queries, observed)));
    assertEquals(4, seen.size());
    assertEquals(List.of("price tea", "price kettle", "price tea", "price cup"), queries);
  }

  /** a set of an effect further out in the row: it goes to its place */
  @Test
  void aSetAtItsPlaceInTheRow() {
    List<String> queries = new ArrayList<>();
    Comp<Long, Eff<Flip<?>, Eff<Prices<?>, ?>>> p =
        perform(new Flip.Coin()).flatMap(heads -> up(total(heads ? CART : List.of("cup"))));
    Comp<Long, Eff<Prices<?>, ?>> flipped = handle((op, k) -> switch (op) {
      case Flip.Coin c -> k.resume(c, true);
    }, p);
    assertEquals(139L, run(batched(queries, flipped)));
    assertEquals(List.of("all of [cup, kettle, tea]"), queries);
  }

  /** a handler that takes the rest at each operation of a set asked one by one: every path its own answers */
  @Test
  void aSetUnderAHandlerThatResumesTwice() {
    Comp<List<Boolean>, Eff<Flip<?>, ?>> coins = performAll(List.of(new Flip.Coin(), new Flip.Coin()));
    Comp<List<List<Boolean>>, Pure> all = handle((op, k) -> switch (op) {
      case Flip.Coin c -> k.resume(c, true).flatMap(a -> k.resume(c, false).map(b ->
          Stream.concat(a.stream(), b.stream()).toList()));
    }, coins.map(List::of));
    assertEquals(List.of(List.of(true, true), List.of(true, false), List.of(false, true), List.of(false, false)),
        run(all));
  }

  /** a handler in place of the library's: a set of its operations, one by one */
  @Test
  void aSetUnderAHandlerInPlace() {
    Comp<List<Void>, Eff<State<Integer>, ?>> puts =
        performAll(Stream.of(1, 2, 3).map(State.Put<Integer>::new).toList());
    assertEquals(3, run(State.run(0, puts)).state());
  }

  @Test
  void anOperationOfTheSetLeftUnanswered() {
    Comp<List<Long>, Eff<Prices<?>, ?>> two = performAll(List.of(new Prices.Price("tea"), new Prices.Price("cup")));
    Comp<List<Long>, Pure> p = handle((op, k) -> switch (op) {
      case Prices.Price price -> k.resume(price, 1L);
      case Prices.InStock s -> k.resume(s, true);
    }, (ops, k) -> {
      if (ops.get(0) instanceof Prices.Price first) k.answer(first, 4L);
      return k.resume();
    }, two);
    assertThrows(IllegalStateException.class, () -> run(p));
  }

  /** the clause of a set asks an effect of its own before it answers: a count of the queries, in a state outside */
  @Test
  void aBatchClauseThatAsks() {
    Comp<Long, Eff<Prices<?>, Eff<State<Integer>, ?>>> program = fit(total(CART));
    Comp<Long, Eff<State<Integer>, ?>> counted = handle((op, k) -> switch (op) {
      case Prices.Price p -> k.resume(p, TABLE.get(p.item()));
      case Prices.InStock s -> k.resume(s, true);
    }, (ops, k) -> State.<Integer>get().flatMap(n -> State.put(n + 1)).flatMap(done -> {
      for (Prices<?> op : ops) switch (op) {
        case Prices.Price p -> k.answer(p, TABLE.get(p.item()));
        case Prices.InStock s -> k.answer(s, true);
      }
      return k.resume();
    }), program);
    State.Final<Integer, Long> f = run(State.run(0, counted));
    assertEquals(139L, f.value());
    assertEquals(1, f.state());
  }
}
