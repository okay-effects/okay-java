package okay.rows;

import static okay.rows.Effects.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import okay.effect.Eff;
import okay.effect.Op;
import okay.effect.Operation;
import org.junit.jupiter.api.Test;

/** What a {@link Gather} makes of what it gathered: a computation of all, of the first wanted, of one batch. */
class GatherPipelineTest {

  sealed interface Prices<T> extends Operation<T, Prices<?>> {
    record Price(String item) implements Prices<Long> {}
    record PriceAll(List<String> items) implements Prices<List<Long>> {}
  }

  /** answers by the length of the name, and notes every operation it was asked */
  static <A, R> Comp<A, R> priced(List<Object> seen, Comp<A, ? super Eff<Prices<?>, R>> body) {
    return handle((op, k) -> {
      seen.add(op);
      return switch (op) {
        case Prices.Price p -> k.resume(p, (long) p.item().length());
        case Prices.PriceAll all -> k.resume(all, all.items().stream().map(i -> (long) i.length()).toList());
      };
    }, body);
  }

  static final List<String> CART = List.of("tea", "kettle", "cup", "spoon");

  static Gather<Long, Prices<?>> prices() {
    return gather(g -> {
      for (String item : CART) g.perform(new Prices.Price(item));
    });
  }

  @Test
  void allOfThem() {
    List<Object> seen = new ArrayList<>();
    assertEquals(List.of(3L, 6L, 3L, 5L), run(priced(seen, prices().toList())));
    assertEquals(17L, run(priced(seen, prices().reduce(0L, Long::sum))));
  }

  /** the first wanted: the operations after it are never asked */
  @Test
  void theFirstWantedAndNoFurther() {
    List<Object> seen = new ArrayList<>();
    assertEquals(Optional.of(6L), run(priced(seen, prices().first(p -> p > 4))));
    assertEquals(List.of(new Prices.Price("tea"), new Prices.Price("kettle")), seen);
  }

  @Test
  void anyAndAll() {
    List<Object> seen = new ArrayList<>();
    assertEquals(true, run(priced(seen, prices().any(p -> p == 6))));
    assertEquals(2, seen.size());
    seen.clear();
    assertEquals(false, run(priced(seen, prices().all(p -> p < 6))));
    assertEquals(2, seen.size());
    seen.clear();
    assertEquals(true, run(priced(seen, prices().all(p -> p > 0))));
    assertEquals(4, seen.size());
    assertEquals(Optional.empty(), run(priced(seen, prices().first(p -> p > 100))));
  }

  @Test
  void eachValueThroughAFunction() {
    List<Object> seen = new ArrayList<>();
    assertEquals(List.of("3", "6", "3", "5"), run(priced(seen, prices().map(String::valueOf).toList())));
  }

  /** the operations as data, before anything runs: all the prices asked in ONE operation */
  @Test
  void theOperationsAskedInOneBatch() {
    List<Object> seen = new ArrayList<>();
    List<String> items = new ArrayList<>();
    for (Op<Prices<?>, Long, ?> op : prices().operations()) {
      if (op instanceof Prices.Price p && !items.contains(p.item())) items.add(p.item());
    }
    Comp<List<Long>, Eff<Prices<?>, ?>> batch = perform(new Prices.PriceAll(items));
    assertEquals(List.of(3L, 6L, 3L, 5L), run(priced(seen, batch)));
    assertEquals(List.of(new Prices.PriceAll(CART)), seen);
  }

  @Test
  void aComputationMadeOfOperationsIsNotOneOperation() {
    Gather<Long, Prices<?>> made = gather(g -> g.add(g.ask(new Prices.Price("tea")).map(p -> p * 2)));
    assertThrows(IllegalStateException.class, made::operations);
    assertThrows(IllegalStateException.class, () -> prices().map(p -> p).operations());
  }
}
