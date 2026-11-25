package okay.rows;

import static okay.rows.Effects.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import okay.effect.Eff;
import okay.effect.Operation;
import okay.effect.Pure;
import okay.rows.std.Choose;
import okay.rows.std.State;
import okay.rows.std.Throws;
import org.junit.jupiter.api.Test;

/** Loops over an {@link Iterable}: the machine is the loop, the place in the source a value. */
class LoopTest {

  sealed interface Prices<T> extends Operation<T, Prices<?>> {
    record Price(String item) implements Prices<Long> {}
  }

  static <A, R> Comp<A, R> priced(Comp<A, ? super Eff<Prices<?>, R>> body) {
    return handle((op, k) -> switch (op) {
      case Prices.Price p -> k.resume(p, (long) p.item().length());
    }, body);
  }

  @Test
  void foldCarriesAValue() {
    Comp<Long, Eff<Prices<?>, ?>> total =
        fold(List.of("tea", "cup", "kettle"), 0L, (sum, item) -> perform(new Prices.Price(item)).map(p -> sum + p));
    assertEquals(12L, run(priced(total)));
  }

  @Test
  void collectKeepsTheOrder() {
    Comp<List<Long>, Eff<Prices<?>, ?>> prices =
        collect(List.of("tea", "kettle", "a"), item -> perform(new Prices.Price(item)));
    assertEquals(List.of(3L, 6L, 1L), run(priced(prices)));
  }

  @Test
  void forEachInConstantStack() {
    int n = 1_000_000;
    Iterable<Integer> numbers = () -> IntStream.range(0, n).iterator();
    Comp<Void, Eff<State<Long>, ?>> sum = forEach(numbers, x -> State.<Long>get().flatMap(s -> State.put(s + x)));
    assertEquals((long) n * (n - 1) / 2, run(State.run(0L, sum)).state());
  }

  /** a source with no end: asked only as far as the loop gets */
  @Test
  void theSourceIsAskedWhenTheLoopGetsThere() {
    List<Integer> pulled = new ArrayList<>();
    Iterable<Integer> naturals = () -> new Iterator<>() {
      int next;
      public boolean hasNext() { return true; }
      public Integer next() { pulled.add(next); return next++; }
    };
    Comp<Void, Eff<Throws<String>, ?>> upTo3 =
        forEach(naturals, x -> x == 3 ? Throws.<String, Void>raise("stop at " + x) : pure(null));
    assertEquals(new Throws.Err<>("stop at 3"), run(Throws.run(upTo3)));
    assertEquals(List.of(0, 1, 2, 3), pulled);
  }

  /** a continuation taken inside the loop and resumed twice: both go through the rest of the same source */
  @Test
  void aChoiceInsideALoop() {
    List<Integer> pulled = new ArrayList<>();
    Iterable<String> letters = () -> {
      Iterator<String> it = List.of("a", "b").iterator();
      return new Iterator<>() {
        public boolean hasNext() { return it.hasNext(); }
        public String next() { pulled.add(1); return it.next(); }
      };
    };
    Comp<String, Eff<Choose, ?>> words =
        fold(letters, "", (word, letter) -> Choose.among(List.of(letter, letter.toUpperCase())).map(l -> word + l));
    assertEquals(List.of("ab", "aB", "Ab", "AB"), run(Choose.all(words)));
    assertEquals(2, pulled.size());
  }

  @Test
  void aLoopRunsAgainFromItsStart() {
    Comp<List<Long>, Pure> prices =
        priced(collect(Stream.of("tea", "cup").toList(), i -> perform(new Prices.Price(i))));
    assertEquals(List.of(3L, 3L), run(prices));
    assertEquals(List.of(3L, 3L), run(prices));
  }

  /** a source of computations: a stream mapped to programs, collected into the program of their values */
  @Test
  void aStreamOfComputations() {
    Comp<List<Long>, Eff<Prices<?>, ?>> prices =
        Stream.of("tea", "kettle").map(item -> perform(new Prices.Price(item))).collect(sequencing());
    assertEquals(List.of(3L, 6L), run(priced(prices)));
  }

  /** built by a plain for: the loop makes the programs, the machine runs them */
  @Test
  void aForThatMakesComputations() {
    List<Comp<Long, Eff<Prices<?>, ?>>> asked = new ArrayList<>();
    for (String item : List.of("tea", "cup", "kettle")) asked.add(perform(new Prices.Price(item)));
    Comp<Long, Eff<Prices<?>, ?>> total = reduce(asked, 0L, Long::sum);
    assertEquals(12L, run(priced(total)));
    Comp<List<Long>, Eff<Prices<?>, ?>> all = sequence(asked);
    assertEquals(List.of(3L, 3L, 6L), run(priced(all)));
  }

  /** the source lazy, its elements programs: asked for the next only when the one before has run */
  @Test
  void aLazySourceOfComputations() {
    List<String> made = new ArrayList<>();
    Iterable<Comp<Long, Eff<Prices<?>, ?>>> lazy = () ->
        Stream.of("a", "bb", "ccc").<Comp<Long, Eff<Prices<?>, ?>>>map(item -> {
      made.add(item);
      return perform(new Prices.Price(item));
    }).iterator();
    Comp<Long, Eff<Prices<?>, Eff<Throws<String>, ?>>> upTo2 = fold(lazy, 0L, (sum, c) -> c.flatMap(p ->
        p == 2 ? up(Throws.<String, Long>raise("stop")) : pure(sum + p)));
    assertEquals(new Throws.Err<>("stop"), run(Throws.run(priced(upTo2))));
    assertEquals(List.of("a", "bb"), made);
  }

  @Test
  void anEmptySource() {
    assertEquals(7, run(fold(List.of(), 7, (acc, x) -> pure(acc + 1))));
  }
}
