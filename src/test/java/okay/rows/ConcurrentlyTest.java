package okay.rows;

import static okay.rows.Effects.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import okay.effect.Eff;
import okay.effect.Operation;
import org.junit.jupiter.api.Test;

/** The questions of a round answered at the same time: the handler's choice, the program the same. */
class ConcurrentlyTest {

  sealed interface Prices<T> extends Operation<T, Prices<?>> {
    record Price(String item) implements Prices<Long> {}
    record InStock(String item) implements Prices<Boolean> {}
  }

  static final Map<String, Long> TABLE = Map.of("tea", 4L, "cup", 11L, "kettle", 120L, "spoon", 2L);
  static final long SLOW = 150;

  /** a slow service: every call waits */
  static long price(String item) {
    try { Thread.sleep(SLOW); } catch (InterruptedException e) { throw new IllegalStateException(e); }
    return TABLE.get(item);
  }

  /** the program: a block, eight questions of two types */
  static Comp<Long, Eff<Prices<?>, ?>> total(List<String> cart) {
    return together(t -> {
      Var<List<Long>> prices = t.gather(g -> {
        for (String item : cart) g.perform(new Prices.Price(item));
      });
      Var<List<Boolean>> stock = t.gather(g -> {
        for (String item : cart) g.perform(new Prices.InStock(item));
      });
      return t.yield(prices, stock, (ps, in) -> {
        long sum = 0;
        for (int i = 0; i < ps.size(); i++) if (in.get(i)) sum += ps.get(i);
        return sum;
      });
    });
  }

  /** how one operation is answered: written once, used by every handler below */
  static <A, R> Batch.One<Prices<?>, A, R> service(Map<String, Thread> threads) {
    return (op, k) -> {
      switch (op) {
        case Prices.Price p -> {
          threads.put("price " + p.item(), Thread.currentThread());
          k.answer(p, price(p.item()));
        }
        case Prices.InStock s -> {
          threads.put("stock " + s.item(), Thread.currentThread());
          k.answer(s, price(s.item()) > 0);
        }
      }
    };
  }

  static <A, R> Clause<Prices<?>, A, R> alone() {
    return (op, k) -> switch (op) {
      case Prices.Price p -> k.resume(p, price(p.item()));
      case Prices.InStock s -> k.resume(s, price(s.item()) > 0);
    };
  }

  static final List<String> CART = List.of("tea", "cup", "kettle", "spoon");

  @Test
  void theSameProgramInTurnAndAtTheSameTime() {
    Map<String, Thread> threads = new ConcurrentHashMap<>();
    long start = System.nanoTime();
    // a handler without a clause for sets: the operations of a set one by one, on the program's thread
    Long inTurn = run(handle(alone(), total(CART)));
    long turnMs = (System.nanoTime() - start) / 1_000_000;
    assertEquals(137L, inTurn);
    assertEquals(0, threads.size());
    assertTrue(turnMs >= 8 * SLOW, "in turn: " + turnMs + " ms");

    threads.clear();
    start = System.nanoTime();
    Long atOnce = run(handle(alone(), Batch.concurrently(service(threads)), total(CART)));
    long onceMs = (System.nanoTime() - start) / 1_000_000;
    assertEquals(137L, atOnce);
    assertEquals(8, threads.size());
    assertTrue(threads.values().stream().noneMatch(t -> t == Thread.currentThread()));
    assertTrue(onceMs < 4 * SLOW, "at the same time: " + onceMs + " ms, in turn " + turnMs + " ms");
  }

  /** a round after a round: each at the same time, the second when the first is answered */
  @Test
  void roundsOneAfterAnother() {
    AtomicInteger sets = new AtomicInteger();
    Comp<Long, Eff<Prices<?>, ?>> p = together(t -> {
      Var<Long> tea = t.perform(new Prices.Price("tea"));
      Var<Long> cup = t.perform(new Prices.Price("cup"));
      Var<Boolean> a = t.ask(tea, x -> perform(new Prices.InStock(x > 3 ? "kettle" : "spoon")));
      Var<Boolean> b = t.ask(cup, x -> perform(new Prices.InStock("spoon")));
      return t.yield(tea, cup, a, (x, y, in) -> in ? x + y : 0L);
    });
    Batch<Prices<?>, Long, okay.effect.Pure> counted = (ops, k) -> {
      sets.incrementAndGet();
      return Batch.<Prices<?>, Long, okay.effect.Pure>concurrently(service(new ConcurrentHashMap<>())).on(ops, k);
    };
    long start = System.nanoTime();
    assertEquals(15L, run(handle(alone(), counted, p)));
    long ms = (System.nanoTime() - start) / 1_000_000;
    assertEquals(2, sets.get());
    assertTrue(ms >= 2 * SLOW && ms < 4 * SLOW, ms + " ms");
  }

  @Test
  void aFailureOfOneIsTheClauses() {
    Batch.One<Prices<?>, Long, okay.effect.Pure> failing = (op, k) -> {
      if (op instanceof Prices.Price p && p.item().equals("cup")) throw new IllegalArgumentException("no cup");
      ConcurrentlyTest.<Long, okay.effect.Pure>service(new ConcurrentHashMap<>()).answer(op, k);
    };
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> run(handle(alone(), Batch.concurrently(failing), total(CART))));
    assertEquals("no cup", e.getMessage());
  }
}
