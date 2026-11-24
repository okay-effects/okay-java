package okay.rows;

import static okay.rows.Effects.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import okay.effect.Eff;
import okay.effect.Operation;
import okay.effect.Pure;
import okay.rows.std.State;
import org.junit.jupiter.api.Test;

/** {@code gather}: perform written in a plain loop, the computations it made taken out of the loop. */
class GatherTest {

  sealed interface Prices<T> extends Operation<T, Prices<?>> {
    record Price(String item) implements Prices<Long> {}
    record InStock(String item) implements Prices<Boolean> {}
  }

  static <A, R> Comp<A, R> priced(Comp<A, ? super Eff<Prices<?>, R>> body) {
    return handle((op, k) -> switch (op) {
      case Prices.Price p -> k.resume(p, (long) p.item().length());
      case Prices.InStock s -> k.resume(s, !s.item().isEmpty());
    }, body);
  }

  static final List<String> CART = List.of("tea", "cup", "kettle");

  @Test
  void aPlainForWithPerformInIt() {
    List<Comp<Long, Eff<Prices<?>, ?>>> asked = gather(Prices.Price.class, () -> {
      for (String item : CART) perform(new Prices.Price(item));
    });
    assertEquals(3, asked.size());
    Comp<List<Long>, Eff<Prices<?>, ?>> all = sequence(asked);
    assertEquals(List.of(3L, 3L, 6L), run(priced(all)));
    Comp<Long, Eff<Prices<?>, ?>> total = reduce(asked, 0L, Long::sum);
    assertEquals(12L, run(priced(total)));
  }

  /** the loop is Java's: its own conditions, breaks and nesting decide what is asked */
  @Test
  void theLoopIsJavas() {
    List<Comp<Long, Eff<Prices<?>, ?>>> asked = gather(Prices.Price.class, () -> {
      for (String item : CART) {
        if (item.startsWith("c")) continue;
        for (int copy = 0; copy < 2; copy++) perform(new Prices.Price(item + copy));
      }
    });
    Comp<List<Long>, Eff<Prices<?>, ?>> all = sequence(asked);
    assertEquals(List.of(4L, 4L, 7L, 7L), run(priced(all)));
  }

  @Test
  void anOperationOfAnotherClassIsRefused() {
    IllegalStateException e = assertThrows(IllegalStateException.class, () ->
        gather(Prices.Price.class, () -> perform(new Prices.InStock("tea"))));
    assertTrue(e.getMessage().contains("InStock"), e.getMessage());
  }

  @Test
  void nothingIsGatheredOutsideOrAfter() {
    perform(new Prices.Price("before"));
    assertThrows(IllegalStateException.class, () ->
        gather(Prices.Price.class, () -> { throw new IllegalStateException("body"); }));
    perform(new Prices.Price("after a failed gather"));
    assertEquals(0, gather(Prices.Price.class, () -> {}).size());
  }

  /** a gather inside a gather: each takes what is asked in its own body */
  @Test
  void gathersNest() {
    List<Comp<Boolean, Eff<Prices<?>, ?>>> outer = gather(Prices.InStock.class, () -> {
      perform(new Prices.InStock("tea"));
      List<Comp<Long, Eff<Prices<?>, ?>>> inner = gather(Prices.Price.class, () -> perform(new Prices.Price("cup")));
      assertEquals(1, inner.size());
      perform(new Prices.InStock(""));
    });
    Comp<List<Boolean>, Eff<Prices<?>, ?>> all = sequence(outer);
    assertEquals(List.of(true, false), run(priced(all)));
  }

  /** an effect with type parameters of its own: its operation's class gathers too */
  @Test
  void anOperationWithTypeParameters() {
    List<Comp<Void, Eff<State<Integer>, ?>>> puts = Effects.<State<Integer>, Void, State.Put<Integer>>gather(
        cast(State.Put.class), () -> {
          for (int i = 1; i <= 3; i++) State.put(i);
        });
    Comp<List<Void>, Eff<State<Integer>, ?>> all = sequence(puts);
    Comp<State.Final<Integer, List<Void>>, Pure> p = State.run(0, all);
    assertEquals(3, run(p).state());
  }

  @SuppressWarnings("unchecked")
  static <T> Class<T> cast(Class<?> c) { return (Class<T>) c; }

  /** only the thread that gathers: a perform on another thread is not its */
  @Test
  void anotherThreadsPerformIsNotGathered() throws InterruptedException {
    List<Comp<Long, Eff<Prices<?>, ?>>> asked = gather(Prices.Price.class, () -> {
      Thread t = Thread.ofVirtual().start(() -> perform(new Prices.InStock("elsewhere")));
      try { t.join(); } catch (InterruptedException e) { throw new IllegalStateException(e); }
      perform(new Prices.Price("here"));
    });
    assertEquals(1, asked.size());
  }
}
