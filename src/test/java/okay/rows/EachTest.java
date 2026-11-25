package okay.rows;

import static okay.rows.Effects.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;
import okay.effect.Eff;
import okay.effect.Operation;
import okay.effect.Pure;
import org.junit.jupiter.api.Test;

/** A block's loops and branches BY THE ANSWERS: {@code each} over an answer that is a list, {@code when} by an
 * answer — written when the answers are known, their questions asked in the block's rounds, those of one handler
 * at once for all the elements together. The N+1 queries of a nested read become one query a level. */
class EachTest {

  record Order(int id, String customer, List<String> items) {}

  sealed interface Shop<T> extends Operation<T, Shop<?>> {
    record Orders(String day) implements Shop<List<Integer>> {}
    record OrderOf(int id) implements Shop<Order> {}
    record CustomerName(String customer) implements Shop<String> {}
    record Price(String item) implements Shop<Long> {}
    record Discount(String customer) implements Shop<Long> {}
  }

  sealed interface Flip<T> extends Operation<T, Flip<?>> {
    record Coin() implements Flip<Boolean> {}
  }

  static final Map<Integer, Order> ORDERS = Map.of(
      1, new Order(1, "ann", List.of("tea", "cup")),
      2, new Order(2, "bob", List.of("kettle")),
      3, new Order(3, "ann", List.of("tea", "tea", "spoon")));
  static final Map<String, Long> PRICES = Map.of("tea", 4L, "cup", 11L, "kettle", 120L, "spoon", 2L);

  static Object answer(Shop<?> op) {
    return switch (op) {
      case Shop.Orders o -> List.of(1, 2, 3);
      case Shop.OrderOf o -> ORDERS.get(o.id());
      case Shop.CustomerName c -> c.customer().toUpperCase();
      case Shop.Price p -> PRICES.get(p.item());
      case Shop.Discount d -> d.customer().equals("ann") ? 1L : 0L;
    };
  }

  /** answers a set by one query — each repeated operation once — and notes its queries */
  static <A, R> Comp<A, R> shop(List<String> queries, Comp<A, ? super Eff<Shop<?>, R>> body) {
    return handle((op, k) -> {
      queries.add("one: " + op);
      return switch (op) {
        case Shop.Orders o -> k.resume(o, List.of(1, 2, 3));
        case Shop.OrderOf o -> k.resume(o, ORDERS.get(o.id()));
        case Shop.CustomerName c -> k.resume(c, c.customer().toUpperCase());
        case Shop.Price p -> k.resume(p, PRICES.get(p.item()));
        case Shop.Discount d -> k.resume(d, d.customer().equals("ann") ? 1L : 0L);
      };
    }, (ops, k) -> {
      TreeSet<String> asked = new TreeSet<>();
      for (Shop<?> op : ops) {
        asked.add(op.toString());
        switch (op) {
          case Shop.Orders o -> k.answer(o, List.of(1, 2, 3));
          case Shop.OrderOf o -> k.answer(o, ORDERS.get(o.id()));
          case Shop.CustomerName c -> k.answer(c, c.customer().toUpperCase());
          case Shop.Price p -> k.answer(p, PRICES.get(p.item()));
          case Shop.Discount d -> k.answer(d, d.customer().equals("ann") ? 1L : 0L);
        }
      }
      queries.add(asked.size() + " of " + ops.size() + ": " + asked.first().replaceAll("\\[.*", ""));
      return k.resume();
    }, body);
  }

  /** the day's orders, each with its customer's name and its total: lists in lists, written as nested loops */
  static Comp<List<String>, Eff<Shop<?>, ?>> report(String day) {
    return together(t -> {
      Var<List<Integer>> ids = t.perform(new Shop.Orders(day));
      Var<List<String>> lines = t.each(ids, id -> {
        Var<Order> order = t.perform(new Shop.OrderOf(id));
        Var<String> name = t.ask(order, o -> perform(new Shop.CustomerName(o.customer())));
        Var<List<Long>> prices = t.each(t.let(order, Order::items), item -> t.perform(new Shop.Price(item)));
        return t.let(name, prices, (n, ps) -> n + " " + ps.stream().mapToLong(Long::longValue).sum());
      });
      return t.yield(lines, ls -> ls);
    });
  }

  /** the same report, the names going on by themselves: map, then, with */
  static Comp<List<String>, Eff<Shop<?>, ?>> fluent(String day) {
    return together(t -> {
      Var<List<Integer>> ids = t.perform(new Shop.Orders(day));
      Var<List<String>> lines = t.each(ids, id -> {
        Var<Order> order = t.perform(new Shop.OrderOf(id));
        Var<String> name = order.then(o -> t.perform(new Shop.CustomerName(o.customer())));
        Var<List<Long>> prices = t.each(order.map(Order::items), item -> t.perform(new Shop.Price(item)));
        return name.with(prices, (n, ps) -> n + " " + ps.stream().mapToLong(Long::longValue).sum());
      });
      return t.yield(lines);
    });
  }

  @Test
  void theNamesGoOnByThemselves() {
    List<String> queries = new ArrayList<>();
    assertEquals(List.of("ANN 15", "BOB 120", "ANN 10"), run(shop(queries, fluent("monday"))));
    assertEquals(List.of("one: Orders[day=monday]", "3 of 3: OrderOf", "6 of 9: CustomerName"), queries);
  }

  /** a chain of questions each needing the one before, a value of three answers */
  @Test
  void aChainAndAValueOfThree() {
    List<String> queries = new ArrayList<>();
    Comp<String, Eff<Shop<?>, ?>> p = together(t -> {
      Var<Order> order = t.perform(new Shop.OrderOf(2));
      Var<String> name = order.then(o -> t.perform(new Shop.CustomerName(o.customer())));
      Var<Long> off = order.then(o -> t.perform(new Shop.Discount(o.customer())));
      Var<Long> price = order.map(o -> o.items().get(0)).then(item -> t.perform(new Shop.Price(item)));
      return t.yield(name.with(price, off, (n, p1, o) -> n + " " + (p1 - o)));
    });
    assertEquals("BOB 120", run(shop(queries, p)));
    assertEquals(List.of("one: OrderOf[id=2]", "3 of 3: CustomerName"), queries);
  }

  @Test
  void nestedLoopsAskALevelAtATime() {
    List<String> queries = new ArrayList<>();
    assertEquals(List.of("ANN 15", "BOB 120", "ANN 10"), run(shop(queries, report("monday"))));
    // one query a level, whatever the number of orders and items: the orders, the three orders, then the two
    // customers' names and the four items' prices together
    assertEquals(List.of("one: Orders[day=monday]", "3 of 3: OrderOf", "6 of 9: CustomerName"), queries);
  }

  /** under a handler without a clause for sets the same block asks one by one: the program is the same */
  @Test
  void theSameBlockOneByOne() {
    List<String> queries = new ArrayList<>();
    Comp<List<String>, Pure> p = handle((op, k) -> {
      queries.add(op.toString());
      return k.resume(witness(op), answer(op));
    }, report("monday"));
    assertEquals(List.of("ANN 15", "BOB 120", "ANN 10"), run(p));
    assertEquals(1 + 3 + 3 + 6, queries.size());
  }

  @SuppressWarnings("unchecked") // the test's own answers are of each operation's type
  static <T> Operation<T, Shop<?>> witness(Shop<?> op) {
    return (Operation<T, Shop<?>>) op;
  }

  /** a branch chosen by an answer: only its questions are asked */
  static Comp<Long, Eff<Shop<?>, ?>> charge(int id) {
    return together(t -> {
      Var<Order> order = t.perform(new Shop.OrderOf(id));
      Var<List<Long>> prices = t.each(t.let(order, Order::items), item -> t.perform(new Shop.Price(item)));
      Var<Long> sum = t.let(prices, ps -> ps.stream().mapToLong(Long::longValue).sum());
      Var<Long> off = t.when(t.let(sum, s -> s > 12),
          () -> t.ask(order, o -> perform(new Shop.Discount(o.customer()))),
          () -> t.value(0L));
      return t.yield(sum, off, (s, o) -> s - o);
    });
  }

  @Test
  void aBranchChosenByAnAnswer() {
    List<String> queries = new ArrayList<>();
    assertEquals(14L, run(shop(queries, charge(1))));
    assertEquals(List.of("one: OrderOf[id=1]", "2 of 2: Price", "one: Discount[customer=ann]"), queries);
    queries.clear();
    assertEquals(10L, run(shop(queries, charge(3))));
    assertEquals(List.of("one: OrderOf[id=3]", "2 of 3: Price"), queries);
  }

  // ---- a loop by the answers

  record Page(List<String> items, Integer next) {}

  sealed interface Catalog<T> extends Operation<T, Catalog<?>> {
    record Fetch(int page) implements Catalog<Page> {}
    record Price(String item) implements Catalog<Long> {}
  }

  record Read(List<String> items, Integer next) {}

  /** page after page while there is a next one, then the prices of everything read */
  static Comp<Long, Eff<Catalog<?>, ?>> catalogTotal() {
    return together(t -> {
      Var<Read> first = t.let(t.perform(new Catalog.Fetch(0)), p -> new Read(p.items(), p.next()));
      Var<Read> all = t.loop(first, read -> read.next() != null, read ->
          t.let(t.perform(new Catalog.Fetch(read.next())), p -> new Read(
              Stream.concat(read.items().stream(), p.items().stream()).toList(), p.next())));
      Var<List<Long>> prices = t.each(t.let(all, Read::items), item -> t.perform(new Catalog.Price(item)));
      return t.yield(prices, ps -> ps.stream().mapToLong(Long::longValue).sum());
    });
  }

  @Test
  void aLoopByTheAnswers() {
    List<Page> pages = List.of(new Page(List.of("tea", "cup"), 1), new Page(List.of("kettle"), 2),
        new Page(List.of("spoon"), null));
    List<String> seen = new ArrayList<>();
    Comp<Long, Eff<Catalog<?>, ?>> shown = rounds(ops -> seen.add(ops.size() + ": " + ops.get(0)), catalogTotal());
    Comp<Long, Pure> p = handle((op, k) -> switch (op) {
      case Catalog.Fetch f -> k.resume(f, pages.get(f.page()));
      case Catalog.Price price -> k.resume(price, PRICES.get(price.item()));
    }, (ops, k) -> {
      for (Catalog<?> op : ops) switch (op) {
        case Catalog.Fetch f -> k.answer(f, pages.get(f.page()));
        case Catalog.Price price -> k.answer(price, PRICES.get(price.item()));
      }
      return k.resume();
    }, shown);
    assertEquals(137L, run(p));
    // the pages one after another, each needing the one before; then all the prices at once
    assertEquals(List.of("1: Fetch[page=0]", "1: Fetch[page=1]", "1: Fetch[page=2]", "4: Price[item=tea]"), seen);
  }

  /** the rounds a program asks in, shown as they go on: a set stays a set */
  @Test
  void theRoundsOfAProgram() {
    List<String> queries = new ArrayList<>();
    List<Integer> sizes = new ArrayList<>();
    Comp<List<String>, Eff<Shop<?>, ?>> shown = rounds(ops -> sizes.add(ops.size()), report("monday"));
    assertEquals(List.of("ANN 15", "BOB 120", "ANN 10"), run(shop(queries, shown)));
    assertEquals(List.of(1, 3, 9), sizes);
    assertEquals(3, queries.size());
  }

  /** an empty list, and a list of lists of nothing */
  @Test
  void nothingToAsk() {
    List<String> queries = new ArrayList<>();
    Comp<List<List<Long>>, Eff<Shop<?>, ?>> p = together(t -> {
      Var<List<List<String>>> lists = t.value(List.of(List.of(), List.<String>of()));
      Var<List<List<Long>>> prices = t.each(lists, items -> t.each(t.value(items), i -> t.perform(new Shop.Price(i))));
      return t.yield(prices, ps -> ps);
    });
    assertEquals(List.of(List.of(), List.of()), run(shop(queries, p)));
    assertEquals(List.of(), queries);
  }

  /** a handler that resumes twice at a question inside an each: each path writes its own elements */
  @Test
  void everyPathItsOwnElements() {
    Comp<List<String>, Eff<Flip<?>, ?>> p = together(t -> {
      Var<Boolean> two = t.perform(new Flip.Coin());
      Var<List<Integer>> ns = t.let(two, b -> b ? List.of(1, 2) : List.of(1));
      Var<List<Boolean>> coins = t.each(ns, n -> t.perform(new Flip.Coin()));
      return t.yield(coins, cs -> List.of(cs.toString()));
    });
    Comp<List<String>, Pure> all = handle((op, k) -> switch (op) {
      case Flip.Coin c -> k.resume(c, true).flatMap(a -> k.resume(c, false).map(b ->
          Stream.concat(a.stream(), b.stream()).toList()));
    }, p);
    assertEquals(List.of("[true, true]", "[true, false]", "[false, true]", "[false, false]", "[true]", "[false]"),
        run(all));
  }

  /** many elements: the block's rounds, not the stack */
  @Test
  void aLongList() {
    List<Integer> many = Stream.iterate(0, i -> i + 1).limit(20_000).toList();
    Comp<Long, Eff<Shop<?>, ?>> p = together(t -> {
      Var<List<Long>> prices = t.each(t.value(many), i -> t.perform(new Shop.Price("tea")));
      return t.yield(prices, ps -> ps.stream().mapToLong(Long::longValue).sum());
    });
    List<String> queries = new ArrayList<>();
    assertEquals(80_000L, run(shop(queries, p)));
    assertEquals(List.of("1 of 20000: Price"), queries);
  }
}
