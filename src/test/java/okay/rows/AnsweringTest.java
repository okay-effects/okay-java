package okay.rows;

import static okay.rows.Effects.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import okay.effect.Eff;
import okay.effect.Operation;
import okay.effect.Pure;
import okay.rows.std.State;
import org.junit.jupiter.api.Test;

/** {@code answering}: a handler that only answers, one function from the operation to its answer — the same
 * function for an operation asked alone and for each operation of a set. */
class AnsweringTest {

  sealed interface Prices<T> extends Operation<T, Prices<?>> {
    record Price(String item) implements Prices<Long> {}
    record InStock(String item) implements Prices<Boolean> {}
  }

  static final Map<String, Long> TABLE = Map.of("tea", 4L, "cup", 11L, "kettle", 120L);

  /** the whole handler */
  static <A, R> Comp<A, R> priced(List<String> asked, Comp<A, ? super Eff<Prices<?>, R>> body) {
    return answering(op -> {
      asked.add(op.toString());
      return switch (op) {
        case Prices.Price p -> p.answer(TABLE.getOrDefault(p.item(), 0L));
        case Prices.InStock s -> s.answer(TABLE.containsKey(s.item()));
      };
    }, body);
  }

  static Comp<String, Eff<Prices<?>, ?>> quote(String item) {
    return together(t -> {
      Var<Long> price = t.perform(new Prices.Price(item));
      Var<Boolean> stock = t.perform(new Prices.InStock(item));
      return t.yield(price, stock, (p, in) -> in ? item + " " + p : item + " is out");
    });
  }

  @Test
  void anOperationAloneAndASet() {
    List<String> asked = new ArrayList<>();
    Comp<Long, Eff<Prices<?>, ?>> one = perform(new Prices.Price("cup"));
    assertEquals(11L, run(priced(asked, one)));
    assertEquals("kettle 120", run(priced(asked, quote("kettle"))));
    assertEquals("spoon is out", run(priced(asked, quote("spoon"))));
    assertEquals(5, asked.size());
  }

  @Test
  void aLongLoopInPlace() {
    List<String> none = new ArrayList<>() {
      @Override
      public boolean add(String s) { return true; }
    };
    List<String> many = java.util.stream.Stream.generate(() -> "tea").limit(500_000).toList();
    Comp<Long, Eff<Prices<?>, ?>> sum =
        fold(many, 0L, (acc, item) -> perform(new Prices.Price(item)).map(p -> acc + p));
    assertEquals(2_000_000L, run(priced(none, sum)));
  }

  /** the answer of another operation is refused when it is given */
  @Test
  void theAnswerMustBeTheOperationsOwn() {
    Comp<Long, Eff<Prices<?>, ?>> one = perform(new Prices.Price("cup"));
    Comp<Long, Pure> p = answering(op -> new Prices.Price("tea").answer(4L), one);
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> run(p));
    assertTrue(e.getMessage().contains("not the answer of the operation asked"), e.getMessage());
  }

  /** an effect with type parameters of its own, of the library: answered the same way */
  @Test
  void anOperationOfTheOldForm() {
    Comp<Integer, Eff<State<Integer>, ?>> read = State.get();
    Comp<Integer, Pure> p = answering(op -> switch (op) {
      case State.Get<Integer> get -> get.answer(42);
      case State.Put<Integer> put -> put.answer(null);
    }, read);
    assertEquals(42, run(p));
  }

  static long slow(String item) {
    try { Thread.sleep(120); } catch (InterruptedException e) { throw new IllegalStateException(e); }
    return TABLE.get(item);
  }

  /** the same function, the operations of a set answered at the same time */
  @Test
  void aSetAtTheSameTime() {
    Map<String, Thread> threads = new ConcurrentHashMap<>();
    Comp<List<Long>, Eff<Prices<?>, ?>> prices = gather(g -> {
      for (String item : List.of("tea", "cup", "kettle", "tea", "cup", "kettle")) g.perform(new Prices.Price(item));
    });
    long start = System.nanoTime();
    Comp<List<Long>, Pure> p = answeringAtOnce(op -> {
      threads.put(Thread.currentThread().getName() + Thread.currentThread().threadId(), Thread.currentThread());
      return switch (op) {
        case Prices.Price price -> price.answer(slow(price.item()));
        case Prices.InStock s -> s.answer(true);
      };
    }, prices);
    assertEquals(List.of(4L, 11L, 120L, 4L, 11L, 120L), run(p));
    long ms = (System.nanoTime() - start) / 1_000_000;
    assertEquals(6, threads.size());
    assertTrue(ms < 3 * 120, ms + " ms");
  }
}
