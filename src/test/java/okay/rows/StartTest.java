package okay.rows;

import static okay.rows.Effects.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import okay.effect.Eff;
import okay.effect.Operation;
import org.junit.jupiter.api.Test;

/** Every example of docs/start.md, compiled and run: the page's code is this file's. */
class StartTest {

  // ---- 1. an effect

  sealed interface Prices<T> extends Operation<T, Prices<?>> {
    record Price(String item) implements Prices<Long> {}
    record InStock(String item) implements Prices<Boolean> {}
    record Discount(long sum) implements Prices<Long> {}
  }

  sealed interface Ask<T> extends Operation<T, Ask<?>> {
    record Confirm(String question) implements Ask<Boolean> {}
    record Fail<T>(String why) implements Ask<T> {}
  }

  sealed interface Counter<T> extends Operation<T, Counter<?>> {
    record Next() implements Counter<Integer> {}
  }

  // ---- 2. programs

  /** a block: questions one after another, each answer a name */
  static Comp<String, Eff<Prices<?>, ?>> quote(String item) {
    return together(t -> {
      Var<Long> price = t.perform(new Prices.Price(item));
      Var<Boolean> stock = t.perform(new Prices.InStock(item));
      Var<Long> off = price.then(p -> t.perform(new Prices.Discount(p)));
      return t.yield(price, stock, off, (p, in, o) -> in ? item + " " + (p - o) : item + " is out");
    });
  }

  /** a block with a plain loop in it */
  static Comp<Long, Eff<Prices<?>, ?>> total(List<String> cart) {
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
  static Comp<Long, Eff<Prices<?>, ?>> inStockTotal(List<String> cart) {
    return fold(cart, 0L, (sum, item) ->
        perform(new Prices.InStock(item)).flatMap(in ->
            in ? perform(new Prices.Price(item)).map(p -> sum + p) : pure(sum)));
  }

  /** two effects: the row lists them in the order of their handlers; up(..) asks the second */
  static Comp<String, Eff<Prices<?>, Eff<Ask<?>, ?>>> checkout(List<String> cart) {
    return total(cart).flatMap(sum ->
        sum <= 100 ? pure("paid " + sum)
            : up(perform(new Ask.Confirm("pay " + sum + "?"))).flatMap(yes ->
                yes ? pure("paid " + sum) : up(perform(new Ask.Fail<String>("declined")))));
  }

  // ---- 3. handlers

  static final Map<String, Long> TABLE = Map.of("tea", 4L, "cup", 11L, "kettle", 120L);

  /** a handler that only answers: one function from the operation to its answer */
  static <A, R> Comp<A, R> priced(Comp<A, ? super Eff<Prices<?>, R>> body) {
    return answering(op -> switch (op) {
      case Prices.Price p -> p.answer(TABLE.getOrDefault(p.item(), 0L));
      case Prices.InStock s -> s.answer(TABLE.containsKey(s.item()));
      case Prices.Discount d -> d.answer(d.sum() / 10);
    }, body);
  }

  /** a handler that needs the rest of the program: the operation and the continuation */
  static <R> Comp<String, R> asking(boolean yes, Comp<String, ? super Eff<Ask<?>, R>> body) {
    return handle((op, k) -> switch (op) {
      case Ask.Confirm c -> k.resume(c, yes).map(result -> result + " (asked: " + c.question() + ")");
      case Ask.Fail<?> f -> k.unwind().map(left -> "failed: " + f.why());
    }, body);
  }

  /** a handler with a state: the state of this path first, resumed with the next */
  static <A, R> Comp<A, R> counting(int from, Comp<A, ? super Eff<Counter<?>, R>> body) {
    return handle(from, (n, op, k) -> switch (op) {
      case Counter.Next next -> k.resume(next, n, n + 1);
    }, body);
  }

  /** a handler that answers a SET by one query: a second clause, given all the operations before any is answered */
  static <A, R> Comp<A, R> pricedByOneQuery(List<String> queries, Comp<A, ? super Eff<Prices<?>, R>> body) {
    return handle((op, k) -> {
      queries.add("one: " + op);
      return switch (op) {
        case Prices.Price p -> k.resume(p, TABLE.getOrDefault(p.item(), 0L));
        case Prices.InStock s -> k.resume(s, TABLE.containsKey(s.item()));
        case Prices.Discount d -> k.resume(d, d.sum() / 10);
      };
    }, (ops, k) -> {
      TreeSet<String> items = new TreeSet<>();
      for (Prices<?> op : ops) if (op instanceof Prices.Price p) items.add(p.item());
      queries.add("all of: " + items);                                    // one query for the whole set
      for (Prices<?> op : ops) switch (op) {
        case Prices.Price p -> k.answer(p, TABLE.getOrDefault(p.item(), 0L));
        case Prices.InStock s -> k.answer(s, TABLE.containsKey(s.item()));
        case Prices.Discount d -> k.answer(d, d.sum() / 10);
      }
      return k.resume();
    }, body);
  }

  // ---- 4. run

  @Test
  void aBlock() {
    assertEquals("kettle 108", run(priced(quote("kettle"))));
    assertEquals("spoon is out", run(priced(quote("spoon"))));
  }

  @Test
  void aLoopInABlockAndAChain() {
    assertEquals(122L, run(priced(total(List.of("tea", "cup", "kettle")))));
    assertEquals(15L, run(priced(inStockTotal(List.of("tea", "spoon", "cup")))));
  }

  @Test
  void twoEffects() {
    assertEquals("paid 14", run(asking(true, priced(checkout(List.of("tea", "cup"))))));
    assertEquals("paid 122 (asked: pay 122?)", run(asking(true, priced(checkout(List.of("tea", "cup", "kettle"))))));
    assertEquals("failed: declined (asked: pay 122?)",
        run(asking(false, priced(checkout(List.of("tea", "cup", "kettle"))))));
  }

  @Test
  void aState() {
    Comp<List<Integer>, Eff<Counter<?>, ?>> three = gather(g -> {
      for (int i = 0; i < 3; i++) g.perform(new Counter.Next());
    });
    assertEquals(List.of(7, 8, 9), run(counting(7, three)));
  }

  /** the same program, a handler that answers a set at once: one query for the three prices */
  @Test
  void aSetByOneQuery() {
    List<String> queries = new ArrayList<>();
    assertEquals(122L, run(pricedByOneQuery(queries, total(List.of("tea", "cup", "kettle")))));
    assertEquals(List.of("all of: [cup, kettle, tea]", "one: Discount[sum=135]"), queries);
  }

  // ---- 5. between a program and its handler

  @Test
  void eachOperationOnceAndTheRoundsAsked() {
    List<String> queries = new ArrayList<>();
    List<Integer> seen = new ArrayList<>();
    List<String> cart = List.of("tea", "tea", "cup", "tea");
    assertEquals(21L, run(pricedByOneQuery(queries, rounds(ops -> seen.add(ops.size()), cached(total(cart))))));
    assertEquals(List.of(2, 1), seen);                   // four prices asked: two different ones go on, in one set
    assertEquals(List.of("all of: [cup, tea]", "one: Discount[sum=23]"), queries);
  }

  // ---- many effects: the row named

  record Booked(String ref, String key, long at, String name) {}

  sealed interface Store<T> extends Operation<T, Store<?>> {
    record Book(Booked booking) implements Store<Boolean> {}
  }
  sealed interface Mail<T> extends Operation<T, Mail<?>> {
    record Send(String to, String text) implements Mail<Boolean> {}
  }
  sealed interface Ids<T> extends Operation<T, Ids<?>> {
    record Ref(String prefix) implements Ids<String> {}
    record Secret() implements Ids<String> {}
  }
  sealed interface Clock<T> extends Operation<T, Clock<?>> {
    record Now() implements Clock<Long> {}
  }

  interface App<R> extends Eff<Store<?>, Eff<Mail<?>, Eff<Ids<?>, Eff<Clock<?>, R>>>> {}

  @SuppressWarnings("unchecked") // a row is only a type, and App<R> is this row by its declaration
  static <A, R> Comp<A, Eff<Store<?>, Eff<Mail<?>, Eff<Ids<?>, Eff<Clock<?>, R>>>>> open(Comp<A, ? super App<R>> c) {
    return (Comp<A, Eff<Store<?>, Eff<Mail<?>, Eff<Ids<?>, Eff<Clock<?>, R>>>>>) (Comp<A, ?>) c;
  }

  static <T> Comp<T, App<?>> store(Store<T> op) { return fit(perform(op)); }
  static <T> Comp<T, App<?>> mail(Mail<T> op) { return fit(up(perform(op))); }
  static <T> Comp<T, App<?>> ids(Ids<T> op) { return fit(up(up(perform(op)))); }
  static <T> Comp<T, App<?>> clock(Clock<T> op) { return fit(up(up(up(perform(op))))); }

  static Comp<String, App<?>> reserve(String name) {
    return together(t -> {
      Var<String> ref = t.ask(ids(new Ids.Ref("B")));
      Var<String> key = t.ask(ids(new Ids.Secret()));
      Var<Long> now = t.ask(clock(new Clock.Now()));
      Var<Booked> booked = ref.with(key, now, (r, k, at) -> new Booked(r, k, at, name));
      Var<Boolean> made = booked.then(b -> t.ask(store(new Store.Book(b))));
      Var<Boolean> told = booked.then(b -> t.ask(mail(new Mail.Send(b.name(), "booked " + b.ref()))));
      return t.yield(ref, made, told, (r, m, s) -> m ? r : "taken");
    });
  }

  static <A, R> Comp<A, R> stored(List<Object> log, Comp<A, ? super Eff<Store<?>, R>> body) {
    return answering(op -> switch (op) {
      case Store.Book b -> { log.add(b); yield b.answer(true); }
    }, body);
  }

  static <A, R> Comp<A, R> mailed(List<Object> log, Comp<A, ? super Eff<Mail<?>, R>> body) {
    return answering(op -> switch (op) {
      case Mail.Send m -> { log.add(m); yield m.answer(true); }
    }, body);
  }

  static <A, R> Comp<A, R> identified(Comp<A, ? super Eff<Ids<?>, R>> body) {
    return answering(op -> switch (op) {
      case Ids.Ref r -> r.answer(r.prefix() + "1");
      case Ids.Secret s -> s.answer("key");
    }, body);
  }

  static <A, R> Comp<A, R> clocked(Comp<A, ? super Eff<Clock<?>, R>> body) {
    return answering(op -> switch (op) {
      case Clock.Now n -> n.answer(1000L);
    }, body);
  }

  @Test
  void aNamedRow() {
    List<Object> log = new ArrayList<>();
    Comp<String, okay.effect.Pure> p = clocked(identified(mailed(log, stored(log, open(reserve("Ann"))))));
    assertEquals("B1", run(p));
    assertEquals(List.of(new Store.Book(new Booked("B1", "key", 1000L, "Ann")), new Mail.Send("Ann", "booked B1")),
        log);
  }
}
