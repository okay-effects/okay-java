package okay.resumable;

import static okay.rows.Effects.handle;
import static okay.rows.Effects.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import okay.effect.Eff;
import okay.effect.Pure;
import okay.resumable.UntransformedGadt.Ask;
import okay.resumable.UntransformedGadt.Prices;
import okay.rows.Cell;
import okay.rows.Comp;
import okay.rows.InPlace;
import org.junit.jupiter.api.Test;

/** WITHOUT THE PLUGIN: a method that was not transformed performs all the same where its handler answers in place
 * — a handler in place, a clause that only resumes, a clause with a state: nothing of the method has to be saved.
 * Where the handler takes the rest of the program, it is refused, and told why. */
class NoPluginTest {
  static final Map<String, Long> TABLE = Map.of("tea", 4L, "cup", 11L, "kettle", 120L);
  static final List<String> CART = List.of("tea", "spoon", "cup", "kettle", "tea");

  static Comp<Long, Eff<Prices<?>, Pure>> total() {
    return Direct.effects(Prices.Fx.class).of(() -> UntransformedGadt.total(CART));
  }

  @Test
  void underAClauseThatOnlyResumes() {
    List<String> queries = new ArrayList<>();
    Comp<Long, Pure> p = handle((op, k) -> switch (op) {
      case Prices.Price price -> {
        queries.add("price " + price.item());
        yield k.resume(price, TABLE.get(price.item()));
      }
      case Prices.InStock s -> { queries.add("stock " + s.item()); yield k.resume(s, TABLE.containsKey(s.item())); }
    }, total());
    assertEquals(15L, run(p));
    assertEquals(7, queries.size());
  }

  @Test
  void underAClauseWithAState() {
    Comp<String, Pure> p = handle(0,
        (asked, op, k) -> switch (op) {
          case Prices.Price price -> k.resume(price, TABLE.get(price.item()), asked + 1);
          case Prices.InStock s -> k.resume(s, TABLE.containsKey(s.item()), asked + 1);
        }, (asked, sum) -> sum + " in " + asked, total());
    assertEquals("15 in 7", run(p));
  }

  @Test
  void underAHandlerInPlace() {
    Comp<Long, Pure> p = handle(Prices.class, new InPlace<Prices<?>, Void, Long, Long, Pure>() {
      public Comp<Long, Pure> ret(Void state, Long a) { return okay.rows.Effects.pure(a); }
      public Cell.Answered answer(Cell<Void> state, Prices<?> op) {
        return switch (op) {
          case Prices.Price price -> state.answer(price, TABLE.get(price.item()));
          case Prices.InStock s -> state.answer(s, TABLE.containsKey(s.item()));
        };
      }
    }, null, total());
    assertEquals(15L, run(p));
  }

  @Test
  void aLongLoop() {
    Comp<Long, Eff<Prices<?>, Pure>> body = Direct.effects(Prices.Fx.class).of(() -> UntransformedGadt.count(300_000));
    Comp<Long, Pure> p = handle((op, k) -> switch (op) {
      case Prices.Price price -> k.resume(price, 4L);
      case Prices.InStock s -> k.resume(s, true);
    }, body);
    assertEquals(1_200_000L, run(p));
  }

  /** which stack a body that waits is on here: the build runs these tests twice, with the JVM's continuations
   * opened (the plugin's flags) and with nothing (the task testNoFlags), where it is a virtual thread */
  @Test
  void theStackABodyWaitsOn() {
    String expected = System.getProperty("okay.resumable.expect", "continuations");
    assertEquals(expected.equals("threads"), !Direct.continuations().equals("the JVM's continuations"),
        Direct.continuations());
  }

  // ---- a handler that takes the rest, with nothing transformed: the body waits on a stack of its own

  static <A, R> Comp<A, R> priced(List<String> queries, Comp<A, ? super Eff<Prices<?>, R>> body) {
    return handle((op, k) -> switch (op) {
      case Prices.Price price -> {
        queries.add("price " + price.item());
        yield k.resume(price, TABLE.get(price.item()));
      }
      case Prices.InStock s -> k.resume(s, TABLE.containsKey(s.item()));
    }, body);
  }

  static <R> Comp<String, R> asking(boolean yes, Comp<String, ? super Eff<Ask<?>, R>> body) {
    return handle((op, k) -> switch (op) {
      case Ask.Confirm c -> k.resume(c, yes).map(r -> r + " (asked: " + c.question() + ")");
      case Ask.Fail<?> f -> k.unwind().map(left -> "failed: " + f.why());
    }, body);
  }

  /** a handler that goes on after the rest, and one that leaves it: the plain method waits, then goes on or is
   * unwound — its finally runs */
  @Test
  void aHandlerThatGoesOnAfterTheRest() {
    List<String> queries = new ArrayList<>();
    List<String> log = new ArrayList<>();
    assertEquals("paid 15 (asked: pay 15?)",
        run(asking(true, priced(queries, UntransformedGadt.shopping(List.of("tea", "cup"), log)))));
    assertEquals(List.of("left the shop"), log);
    log.clear();
    assertEquals("failed: declined (asked: pay 15?)",
        run(asking(false, priced(queries, UntransformedGadt.shopping(List.of("tea", "cup"), log)))));
    assertEquals(List.of("left the shop"), log);
    log.clear();
    assertEquals("paid 4", run(asking(false, priced(queries, UntransformedGadt.shopping(List.of("tea"), log)))));
  }

  /** every operation's handler takes the rest, in a long loop: a turn of the two stacks each, no stack grows */
  @Test
  void aLongLoopUnderAHandlerThatTakesTheRest() {
    int[] after = {0};
    Comp<Long, Pure> p = handle((op, k) -> switch (op) {
      case Prices.Price price -> k.resume(price, 4L).map(sum -> {
        after[0]++;
        return sum;
      });
      case Prices.InStock s -> k.resume(s, true);
    }, UntransformedGadt.counting(20_000));
    assertEquals(80_000L, run(p));
    assertEquals(20_000, after[0]);
  }

  /** a rest that waits on a stack goes on once: a handler that resumes it again is refused, and told why */
  @Test
  void aHandlerThatResumesTwiceIsRefused() {
    Comp<Long, Pure> p = handle((op, k) -> switch (op) {
      case Prices.Price price -> k.resume(price, 4L).flatMap(a -> k.resume(price, 5L).map(b -> a + b));
      case Prices.InStock s -> k.resume(s, true);
    }, UntransformedGadt.totalling(List.of("tea")));
    IllegalStateException e = assertThrows(IllegalStateException.class, () -> run(p));
    assertTrue(e.getMessage().contains("was not transformed"), e.getMessage());
  }

  /** a set asked from a plain method: the part runs in the program while the method waits */
  @Test
  void aSetAskedFromAPlainMethod() {
    List<String> queries = new ArrayList<>();
    Comp<Long, Pure> p = handle((op, k) -> switch (op) {
      case Prices.Price price -> k.resume(price, TABLE.get(price.item()));
      case Prices.InStock s -> k.resume(s, true);
    }, (ops, k) -> {
      queries.add("all of " + ops.size());
      for (Prices<?> op : ops) switch (op) {
        case Prices.Price price -> k.answer(price, TABLE.get(price.item()));
        case Prices.InStock s -> k.answer(s, true);
      }
      return k.resume();
    }, UntransformedGadt.batching(List.of("tea", "cup", "kettle")));
    assertEquals(139L, run(p));
    assertEquals(List.of("all of 3"), queries);
  }

  /** the same program run again: a stack of its own for each run */
  @Test
  void aProgramRunsAgain() {
    List<String> queries = new ArrayList<>();
    Comp<String, Pure> p =
        asking(true, priced(queries, UntransformedGadt.shopping(List.of("tea", "cup"), new ArrayList<>())));
    assertEquals(run(p), run(p));
  }

  /** a clause that does more than answer takes the rest; the BOUNDARY here is this class's, which was transformed,
   * and the method below it was not: its frame cannot be saved between two that are */
  @Test
  void aHandlerThatTakesTheRestIsRefused() {
    Comp<Long, Pure> p = handle((op, k) -> switch (op) {
      case Prices.Price price -> k.resume(price, 4L).map(sum -> sum + 1);
      case Prices.InStock s -> k.resume(s, true);
    }, total());
    IllegalStateException e = assertThrows(IllegalStateException.class, () -> run(p));
    assertTrue(e.getMessage().contains("UntransformedGadt.total"), e.getMessage());
    assertTrue(e.getMessage().contains("takes the rest of the program"), e.getMessage());
  }
}
