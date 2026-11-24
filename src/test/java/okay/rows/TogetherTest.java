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

/** {@link Together}: a comprehension — questions written one after another, their answers names, the code nested
 * only where a question depends on an answer; the questions of a block asked at once. */
class TogetherTest {

  sealed interface Prices<T> extends Operation<T, Prices<?>> {
    record Price(String item) implements Prices<Long> {}
    record InStock(String item) implements Prices<Boolean> {}
    record Discount(long sum) implements Prices<Long> {}
  }

  sealed interface Flip<T> extends Operation<T, Flip<?>> {
    record Coin() implements Flip<Boolean> {}
  }

  static final Map<String, Long> TABLE = Map.of("tea", 4L, "cup", 11L, "kettle", 120L);

  /** answers a set by one query, and notes its queries */
  static <A, R> Comp<A, R> shop(List<String> queries, Comp<A, ? super Eff<Prices<?>, R>> body) {
    return handle((op, k) -> switch (op) {
      case Prices.Price p -> { queries.add("price " + p.item()); yield k.resume(p, TABLE.get(p.item())); }
      case Prices.InStock s -> { queries.add("stock " + s.item()); yield k.resume(s, TABLE.containsKey(s.item())); }
      case Prices.Discount d -> { queries.add("discount " + d.sum()); yield k.resume(d, d.sum() / 10); }
    }, (ops, k) -> {
      TreeSet<String> asked = new TreeSet<>();
      for (Prices<?> op : ops) switch (op) {
        case Prices.Price p -> { asked.add("price " + p.item()); k.answer(p, TABLE.get(p.item())); }
        case Prices.InStock s -> { asked.add("stock " + s.item()); k.answer(s, TABLE.containsKey(s.item())); }
        case Prices.Discount d -> { asked.add("discount " + d.sum()); k.answer(d, d.sum() / 10); }
      }
      queries.add("all of " + asked);
      return k.resume();
    }, body);
  }

  /** two questions of two types, one value: one query */
  static Comp<String, Eff<Prices<?>, ?>> quote(String item) {
    return together(t -> {
      Var<Long> price = t.ask(perform(new Prices.Price(item)));
      Var<Boolean> stock = t.ask(perform(new Prices.InStock(item)));
      return t.yield(() -> stock.get() ? item + " " + price.get() : item + " is out");
    });
  }

  @Test
  void questionsOfTwoTypesInOneQuery() {
    List<String> queries = new ArrayList<>();
    assertEquals("cup 11", run(shop(queries, quote("cup"))));
    assertEquals(List.of("all of [price cup, stock cup]"), queries);
  }

  /** a question that depends on answers: a block in the block, and a second query */
  static Comp<Long, Eff<Prices<?>, ?>> total(String first, String second) {
    return together(t -> {
      Var<Long> a = t.ask(perform(new Prices.Price(first)));
      Var<Long> b = t.ask(perform(new Prices.Price(second)));
      return t.then(() -> {
        long sum = a.get() + b.get();
        return together(u -> {
          Var<Long> off = u.ask(perform(new Prices.Discount(sum)));
          return u.yield(() -> sum - off.get());
        });
      });
    });
  }

  @Test
  void aBlockInABlockWhereAQuestionDepends() {
    List<String> queries = new ArrayList<>();
    assertEquals(118L, run(shop(queries, total("kettle", "cup"))));
    assertEquals(List.of("all of [price cup, price kettle]", "discount 131"), queries);
  }

  /** a loop in the block: the names in a list */
  @Test
  void aLoopInTheBlock() {
    List<String> queries = new ArrayList<>();
    List<String> cart = List.of("tea", "kettle", "cup");
    Comp<Long, Eff<Prices<?>, ?>> sum = together(t -> {
      List<Var<Long>> prices = new ArrayList<>();
      for (String item : cart) prices.add(t.ask(perform(new Prices.Price(item))));
      Var<Boolean> kettle = t.ask(perform(new Prices.InStock("kettle")));
      return t.yield(() -> kettle.get() ? prices.stream().mapToLong(Var::get).sum() : 0L);
    });
    assertEquals(135L, run(shop(queries, sum)));
    assertEquals(1, queries.size());
  }

  /** under a handler without a clause for sets: the same block, its questions one by one */
  @Test
  void theSameBlockOneByOne() {
    List<String> queries = new ArrayList<>();
    Comp<String, Pure> p = handle((op, k) -> {
      queries.add(op.toString());
      return switch (op) {
        case Prices.Price price -> k.resume(price, 1L);
        case Prices.InStock s -> k.resume(s, false);
        case Prices.Discount d -> k.resume(d, 0L);
      };
    }, quote("cup"));
    assertEquals("cup is out", run(p));
    assertEquals(2, queries.size());
  }

  /** questions of two effects in one block: each effect's operations to its own handler, a set each */
  @Test
  void questionsOfTwoEffects() {
    List<String> queries = new ArrayList<>();
    Comp<String, Eff<Prices<?>, Eff<State<Integer>, ?>>> p = together(t -> {
      Var<Long> tea = t.perform(new Prices.Price("tea"));
      Var<Integer> count = t.ask(up(State.get()));
      Var<Long> cup = t.perform(new Prices.Price("cup"));
      Var<Void> counted = t.ask(up(State.put(7)));
      return t.yield(() -> tea.get() + cup.get() + " of " + count.get());
    });
    State.Final<Integer, String> f = run(State.run(3, shop(queries, p)));
    assertEquals("15 of 3", f.value());
    assertEquals(7, f.state());
    assertEquals(List.of("all of [price cup, price tea]"), queries);
  }

  /** a computation that is not one operation: a question as any other, run in its turn */
  @Test
  void aComputationAsAQuestion() {
    List<String> queries = new ArrayList<>();
    Comp<String, Eff<Prices<?>, ?>> p = together(t -> {
      Var<String> cup = t.ask(quote("cup"));
      Var<String> spoon = t.ask(quote("spoon"));
      return t.yield(() -> cup.get() + ", " + spoon.get());
    });
    assertEquals("cup 11, spoon is out", run(shop(queries, p)));
    assertEquals(2, queries.size());
  }

  /** a handler that resumes twice at a question: each path its own answers */
  @Test
  void everyPathItsOwnAnswers() {
    Comp<List<String>, Eff<Flip<?>, ?>> p = together(t -> {
      Var<Boolean> a = t.ask(perform(new Flip.Coin()));
      Var<Boolean> b = t.ask(perform(new Flip.Coin()));
      return t.yield(() -> List.of(a.get() + "/" + b.get()));
    });
    Comp<List<String>, Pure> all = handle((op, k) -> switch (op) {
      case Flip.Coin c -> k.resume(c, true).flatMap(x -> k.resume(c, false).map(y ->
          Stream.concat(x.stream(), y.stream()).toList()));
    }, p);
    assertEquals(List.of("true/true", "true/false", "false/true", "false/false"), run(all));
  }

  /** FLAT: a question made of answers says which, and is asked when they are known — no block inside */
  static Comp<Long, Eff<Prices<?>, ?>> flat(String first, String second) {
    return together(t -> {
      Var<Long> a = t.perform(new Prices.Price(first));
      Var<Long> b = t.perform(new Prices.Price(second));
      Var<Long> off = t.ask(a, b, (x, y) -> perform(new Prices.Discount(x + y)));
      return t.yield(a, b, off, (x, y, o) -> x + y - o);
    });
  }

  @Test
  void aQuestionMadeOfAnswersIsFlat() {
    List<String> queries = new ArrayList<>();
    assertEquals(118L, run(shop(queries, flat("kettle", "cup"))));
    assertEquals(List.of("all of [price cup, price kettle]", "discount 131"), queries);
  }

  /** the rounds are found by what each question needs, not by where it is written: two questions that each need
   * one answer are of one round, one set */
  @Test
  void theRoundsFollowTheNeeds() {
    List<String> queries = new ArrayList<>();
    Comp<String, Eff<Prices<?>, ?>> p = together(t -> {
      Var<Long> tea = t.perform(new Prices.Price("tea"));
      Var<Long> teaOff = t.ask(tea, x -> perform(new Prices.Discount(x * 100)));
      Var<Long> kettle = t.perform(new Prices.Price("kettle"));
      Var<Long> kettleOff = t.ask(kettle, x -> perform(new Prices.Discount(x)));
      Var<Boolean> cup = t.perform(new Prices.InStock("cup"));
      Var<Long> both = t.ask(teaOff, kettleOff, (x, y) -> perform(new Prices.Discount(x + y)));
      return t.yield(cup, both, (in, off) -> in + " " + off);
    });
    assertEquals("true 5", run(shop(queries, p)));
    assertEquals(List.of("all of [price kettle, price tea, stock cup]", "all of [discount 120, discount 400]",
        "discount 52"), queries);
  }

  /** a question that reads the answers above it by get: asked after all of them */
  @Test
  void aQuestionThatReadsWhatIsAboveIt() {
    List<String> queries = new ArrayList<>();
    Comp<Long, Eff<Prices<?>, ?>> p = together(t -> {
      Var<Long> a = t.ask(perform(new Prices.Price("kettle")));
      Var<Long> b = t.ask(perform(new Prices.Price("cup")));
      Var<Long> off = t.ask(() -> perform(new Prices.Discount(a.get() + b.get())));
      return t.yield(() -> a.get() + b.get() - off.get());
    });
    assertEquals(118L, run(shop(queries, p)));
    assertEquals(2, queries.size());
  }

  /** a list of questions under one name, a name for a value, the block going on with a computation */
  @Test
  void aListOfQuestionsAndANameForAValue() {
    List<String> queries = new ArrayList<>();
    List<String> cart = List.of("tea", "kettle", "cup");
    Comp<String, Eff<Prices<?>, ?>> p = together(t -> {
      Var<List<Long>> prices = t.askAll(cart.stream().map(i -> perform(new Prices.Price(i))).toList());
      Var<Long> sum = t.let(prices, ps -> ps.stream().mapToLong(Long::longValue).sum());
      Var<Long> off = t.ask(sum, s -> perform(new Prices.Discount(s)));
      return t.then(sum, off, (s, o) -> quote("cup").map(q -> q + ", total " + (s - o)));
    });
    assertEquals("cup 11, total 122", run(shop(queries, p)));
    assertEquals(List.of("all of [price cup, price kettle, price tea]", "discount 135",
        "all of [price cup, stock cup]"), queries);
  }

  @Test
  void anAnswerOfAnotherBlockIsRefused() {
    List<String> queries = new ArrayList<>();
    Comp<Long, Eff<Prices<?>, ?>> p = together(t -> {
      Var<Long> a = t.ask(perform(new Prices.Price("tea")));
      return t.then(() -> together(u -> u.yield(a, x -> x)));
    });
    assertThrows(IllegalArgumentException.class, () -> run(shop(queries, p)));
    Comp<Long, Eff<Prices<?>, ?>> early = together(t -> {
      Var<Long> a = t.ask(() -> perform(new Prices.Price("tea")));
      Var<Long> b = t.ask(perform(new Prices.Price("cup")));
      Var<Long> c = t.ask(b, x -> perform(new Prices.Discount(x + a.get())));
      return t.yield(c, x -> x);
    });
    assertEquals(1L, run(shop(queries, early)));
  }

  @Test
  void anAnswerReadOutsideItsBlockIsRefused() {
    List<String> queries = new ArrayList<>();
    Comp<Long, Eff<Prices<?>, ?>> early = together(t -> {
      Var<Long> price = t.ask(perform(new Prices.Price("tea")));
      long read = price.get();
      return t.yield(() -> read);
    });
    assertThrows(IllegalStateException.class, () -> run(shop(queries, early)));
    Comp<Long, Eff<Prices<?>, ?>> late = together(t -> {
      Var<Long> price = t.ask(perform(new Prices.Price("tea")));
      return t.then(() -> perform(new Prices.Discount(1)).map(off -> price.get() - off));
    });
    assertThrows(IllegalStateException.class, () -> run(shop(queries, late)));
  }

  /** a block is a program: run again, written again; in a long loop, constant stack */
  @Test
  void blocksInALoop() {
    Comp<Long, Eff<Prices<?>, ?>> sum = fold(() -> Stream.iterate(0, i -> i + 1).limit(200_000).iterator(), 0L,
        (acc, i) -> together(t -> {
          Var<Long> tea = t.ask(perform(new Prices.Price("tea")));
          Var<Long> cup = t.ask(perform(new Prices.Price("cup")));
          return t.yield(() -> acc + tea.get() + cup.get());
        }));
    Comp<Long, Pure> p = handle((op, k) -> switch (op) {
      case Prices.Price price -> k.resume(price, 1L);
      case Prices.InStock s -> k.resume(s, false);
      case Prices.Discount d -> k.resume(d, 0L);
    }, sum);
    assertEquals(400_000L, run(p));
  }
}
