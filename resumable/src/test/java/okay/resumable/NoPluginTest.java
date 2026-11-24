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
    Comp<String, Pure> p = okay.rows.Effects.<Prices<?>, Integer, Long, String, Pure>handle(0,
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

  /** a clause that does more than answer takes the rest: the method's frames would have to be saved */
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
