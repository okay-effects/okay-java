package okay.rows;

import static okay.rows.Sets.*;
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

/** Every example of docs/start.md, compiled and run: the page's code is this file's. */
class StartTest {

  // ---- 1. an effect

  sealed interface Prices {
    record Price(String item) implements Prices, Operation<Long, Prices> {}
    record InStock(String item) implements Prices, Operation<Boolean, Prices> {}
    record Discount(long sum) implements Prices, Operation<Long, Prices> {}
  }

  sealed interface Ask {
    record Confirm(String question) implements Ask, Operation<Boolean, Ask> {}
    record Fail<T>(String why) implements Ask, Operation<T, Ask> {}
  }

  sealed interface Counter {
    record Next() implements Counter, Operation<Integer, Counter> {}
  }

  // ---- 2. programs

  /** a block: questions one after another, each answer a name */
  static <R extends Prices> Comp<String, R> quote(String item) {
    return together(t -> {
      Var<Long> price = t.perform(new Prices.Price(item));
      Var<Boolean> stock = t.perform(new Prices.InStock(item));
      Var<Long> off = price.then(p -> t.perform(new Prices.Discount(p)));
      return t.yield(price, stock, off, (p, in, o) -> in ? item + " " + (p - o) : item + " is out");
    });
  }

  /** a block with a plain loop in it */
  static <R extends Prices> Comp<Long, R> total(List<String> cart) {
    return together(t -> {
      Var<List<Long>> prices = t.gather(g -> {
        for (String item : cart) g.perform(new Prices.Price(item));
      });
      Var<Long> sum = prices.map(ps -> ps.stream().mapToLong(Long::longValue).sum());
      Var<Long> off = sum.then(s -> t.perform(new Prices.Discount(s)));
      return t.yield(sum, off, (s, o) -> s - o);
    });
  }

  /** a chain: each step goes by the answer before it */
  static <R extends Prices> Comp<Long, R> inStockTotal(List<String> cart) {
    return fold(cart, 0L, (sum, item) ->
        perform(new Prices.InStock(item)).flatMap(in ->
            in ? perform(new Prices.Price(item)).map(p -> sum + p) : pure(sum)));
  }

  /** two effects: the row is the set of both; a program of one of them is used as it is */
  static <R extends Prices & Ask> Comp<String, R> checkout(List<String> cart) {
    return total(cart).flatMap(sum ->
        sum <= 100 ? pure("paid " + sum)
            : perform(new Ask.Confirm("pay " + sum + "?")).flatMap(yes ->
                yes ? pure("paid " + sum) : perform(new Ask.Fail<String>("declined"))));
  }

  /** a row named once, for a class of programs */
  static final class Till<R extends Prices & Ask & Counter> {
    Comp<String, R> receipt(List<String> cart) {
      return StartTest.<R>checkout(cart).flatMap(paid -> perform(new Counter.Next()).map(n -> "#" + n + " " + paid));
    }
  }

  // ---- 3. handlers

  static final Map<String, Long> TABLE = Map.of("tea", 4L, "cup", 11L, "kettle", 120L);

  /** a handler that only answers: one function from the operation to its answer */
  static <A, R> Comp<A, R> priced(Comp<A, ? super Eff<Prices, R>> body) {
    return answering(op -> switch (op) {
      case Prices.Price p -> p.answer(TABLE.getOrDefault(p.item(), 0L));
      case Prices.InStock s -> s.answer(TABLE.containsKey(s.item()));
      case Prices.Discount d -> d.answer(d.sum() / 10);
    }, body);
  }

  /** a handler that needs the rest of the program: the operation and the continuation */
  static <R> Comp<String, R> asking(boolean yes, Comp<String, ? super Eff<Ask, R>> body) {
    return handle((op, k) -> switch (op) {
      case Ask.Confirm c -> k.resume(c, yes).map(result -> result + " (asked: " + c.question() + ")");
      case Ask.Fail<?> f -> k.unwind().map(left -> "failed: " + f.why());
    }, body);
  }

  /** a handler whose value is another: the program's, or none where it failed */
  static <A, R> Comp<Optional<A>, R> attempt(Comp<A, ? super Eff<Ask, R>> body) {
    return handle(a -> pure(Optional.of(a)), (op, k) -> switch (op) {
      case Ask.Confirm c -> k.resume(c, true);
      case Ask.Fail<?> f -> k.unwind().map(left -> Optional.empty());
    }, body);
  }

  /** a handler with a state: the state of this path first, resumed with the next */
  static <A, R> Comp<A, R> counting(int from, Comp<A, ? super Eff<Counter, R>> body) {
    return handle(from, (n, op, k) -> switch (op) {
      case Counter.Next next -> k.resume(next, n, n + 1);
    }, body);
  }

  /** a handler that answers a SET by one query: a second clause, given all the operations before any is answered */
  static <A, R> Comp<A, R> pricedByOneQuery(List<String> queries, Comp<A, ? super Eff<Prices, R>> body) {
    return handle((op, k) -> {
      queries.add("one: " + op);
      return switch (op) {
        case Prices.Price p -> k.resume(p, TABLE.getOrDefault(p.item(), 0L));
        case Prices.InStock s -> k.resume(s, TABLE.containsKey(s.item()));
        case Prices.Discount d -> k.resume(d, d.sum() / 10);
      };
    }, (ops, k) -> {
      TreeSet<String> items = new TreeSet<>();
      for (Prices op : ops) if (op instanceof Prices.Price p) items.add(p.item());
      queries.add("all of: " + items);                                    // one query for the whole set
      for (Prices op : ops) switch (op) {
        case Prices.Price p -> k.answer(p, TABLE.getOrDefault(p.item(), 0L));
        case Prices.InStock s -> k.answer(s, TABLE.containsKey(s.item()));
        case Prices.Discount d -> k.answer(d, d.sum() / 10);
      }
      return k.resume();
    }, body);
  }

  // ---- 4. where the handlers are put

  @Test
  void aBlock() {
    var shop = row(of(Prices.class));
    assertEquals("kettle 108", run(priced(shop.of(quote("kettle")))));
    assertEquals("spoon is out", run(priced(shop.of(quote("spoon")))));
  }

  @Test
  void aLoopInABlockAndAChain() {
    var shop = row(of(Prices.class));
    assertEquals(122L, run(priced(shop.of(total(List.of("tea", "cup", "kettle"))))));
    assertEquals(15L, run(priced(shop.of(inStockTotal(List.of("tea", "spoon", "cup"))))));
  }

  @Test
  void twoEffects() {
    var till = row(of(Prices.class, Ask.class));
    assertEquals("paid 14", run(asking(true, priced(till.of(checkout(List.of("tea", "cup")))))));
    assertEquals("paid 122 (asked: pay 122?)",
        run(asking(true, priced(till.of(checkout(List.of("tea", "cup", "kettle")))))));
    assertEquals("failed: declined (asked: pay 122?)",
        run(asking(false, priced(till.of(checkout(List.of("tea", "cup", "kettle")))))));
  }

  @Test
  void theListSaysTheOrderOfTheHandlers() {
    // the same program, the handlers the other way round: the list is theirs, not the program's
    var till = row(of(Ask.class, Prices.class));
    assertEquals("paid 14", run(priced(asking(true, till.of(checkout(List.of("tea", "cup")))))));
  }

  @Test
  void aRowNamedByAClassOfPrograms() {
    var all = row(of(Prices.class, Ask.class, Counter.class));
    assertEquals("#7 paid 14",
        run(counting(7, asking(true, priced(all.of(new Till<>().receipt(List.of("tea", "cup"))))))));
  }

  @Test
  void aState() {
    var one = row(of(Counter.class));
    Comp<List<Integer>, Eff<Counter, okay.effect.Pure>> three = one.of(gather(g -> {
      for (int i = 0; i < 3; i++) g.perform(new Counter.Next());
    }));
    assertEquals(List.of(7, 8, 9), run(counting(7, three)));
  }

  /** the same program, a handler that answers a set at once: one query for the three prices */
  @Test
  void aSetByOneQuery() {
    List<String> queries = new ArrayList<>();
    var shop = row(of(Prices.class));
    assertEquals(122L, run(pricedByOneQuery(queries, shop.of(total(List.of("tea", "cup", "kettle"))))));
    assertEquals(List.of("all of: [cup, kettle, tea]", "one: Discount[sum=135]"), queries);
  }

  /** a handler inside a program: Ask answered here, Prices stays in the row */
  static <R extends Prices> Comp<Optional<String>, R> tried(List<String> cart) {
    return attempt(Sets.<R>keep().row(of(Ask.class)).of(checkout(cart)));
  }

  @Test
  void aHandlerInsideAProgram() {
    var shop = row(of(Prices.class));
    assertEquals(Optional.of("paid 14"), run(priced(shop.of(tried(List.of("tea", "cup"))))));
  }

  /** the boundary of an application, written once: a class whose type parameter names the set */
  static final class Shop<V extends Prices & Ask> {
    String checkout(boolean yes, Comp<String, ? super V> p) {
      var row = Sets.<V>exactly(Shop.class).row(of(Prices.class, Ask.class));
      return run(asking(yes, priced(row.of(p))));
    }
  }

  static final Shop<?> SHOP = new Shop<>();

  @Test
  void theBoundaryKeptInAField() {
    assertEquals("paid 14", SHOP.checkout(true, checkout(List.of("tea", "cup"))));
    assertEquals("kettle 108", SHOP.checkout(true, quote("kettle")));     // a program of fewer effects
  }

  /** the library's effects are asked and handled the same way */
  static <R extends State<Integer> & Prices> Comp<Long, R> spend(String item) {
    return perform(new Prices.Price(item)).flatMap(price ->
        perform(new State.Get<Integer>()).flatMap(left ->
            perform(new State.Put<>(left - price.intValue())).map(done -> price)));
  }

  @Test
  void theLibrarysEffects() {
    var wallet = row(of(Prices.class, Sets.<State<Integer>>the(State.class)));
    assertEquals(new State.Final<>(89, 11L), run(State.run(100, priced(wallet.of(spend("cup"))))));
  }

  // ---- 5. between a program and its handler

  @Test
  void eachOperationOnceAndTheRoundsAsked() {
    List<String> queries = new ArrayList<>();
    List<Integer> seen = new ArrayList<>();
    List<String> cart = List.of("tea", "tea", "cup", "tea");
    var shop = row(of(Prices.class));
    assertEquals(21L,
        run(pricedByOneQuery(queries, rounds(ops -> seen.add(ops.size()), cached(shop.of(total(cart)))))));
    assertEquals(List.of(2, 1), seen);                   // four prices asked: two different ones go on, in one set
    assertEquals(List.of("all of: [cup, tea]", "one: Discount[sum=23]"), queries);
  }

  // ---- a clause that asks another effect

  sealed interface Mail { record Send(String to) implements Mail, Operation<Void, Mail> {} }
  sealed interface Clock { record Now() implements Clock, Operation<Long, Clock> {} }

  final List<String> sent = new ArrayList<>();

  <A, R> Comp<A, Eff<Clock, R>> audited(Comp<A, ? super Eff<Mail, Eff<Clock, R>>> c) {
    return handle((op, k) -> switch (op) {
      case Mail.Send s -> ask(k, new Clock.Now()).flatMap(now -> {
        sent.add(s.to() + " at " + now);
        return k.resume(s, null);
      });
    }, c);
  }

  static <A, R> Comp<A, R> clocked(long at, Comp<A, ? super Eff<Clock, R>> c) {
    return answering(op -> switch (op) { case Clock.Now n -> n.answer(at); }, c);
  }

  @Test
  void aClauseThatAsksAnotherEffect() {
    var post = row(of(Mail.class, Clock.class));
    assertEquals("told", run(clocked(9, audited(post.of(perform(new Mail.Send("ann")).map(done -> "told"))))));
    assertEquals(List.of("ann at 9"), sent);
  }
}
