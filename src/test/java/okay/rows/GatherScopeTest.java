package okay.rows;

import static okay.rows.Effects.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import okay.effect.Eff;
import okay.effect.Operation;
import okay.rows.std.State;
import org.junit.jupiter.api.Test;

/** {@link Gather}: perform in a plain loop, with no state of the thread, checked by the compiler. */
class GatherScopeTest {

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
    Iterable<Comp<Long, Eff<Prices<?>, ?>>> asked = new Gather<>() {{
      for (String item : CART) perform(new Prices.Price(item));
    }};
    Comp<List<Long>, Eff<Prices<?>, ?>> all = sequence(asked);
    assertEquals(List.of(3L, 3L, 6L), run(priced(all)));
    Comp<Long, Eff<Prices<?>, ?>> total = reduce(asked, 0L, Long::sum);
    assertEquals(12L, run(priced(total)));
  }

  @Test
  void theLoopIsJavas() {
    Iterable<Comp<Long, Eff<Prices<?>, ?>>> asked = new Gather<>() {{
      for (String item : CART) {
        if (item.startsWith("c")) continue;
        for (int copy = 0; copy < 2; copy++) perform(new Prices.Price(item + copy));
      }
    }};
    Comp<List<Long>, Eff<Prices<?>, ?>> all = sequence(asked);
    assertEquals(List.of(4L, 4L, 7L, 7L), run(priced(all)));
  }

  /** a computation made of operations, gathered as it is */
  @Test
  void aComputationMadeOfOperations() {
    Iterable<Comp<Long, Eff<Prices<?>, ?>>> asked = new Gather<>() {{
      for (String item : CART) {
        add(ask(new Prices.InStock(item)).flatMap(in -> ask(new Prices.Price(item)).map(p -> in ? p * 2 : 0L)));
      }
    }};
    Comp<List<Long>, Eff<Prices<?>, ?>> all = sequence(asked);
    assertEquals(List.of(6L, 6L, 12L), run(priced(all)));
  }

  /** an effect with type parameters of its own: nothing to say but the type of what is gathered */
  @Test
  void anEffectWithTypeParameters() {
    Iterable<Comp<Void, Eff<State<Integer>, ?>>> puts = new Gather<>() {{
      for (int i = 1; i <= 3; i++) perform(new State.Put<>(i));
    }};
    Comp<List<Void>, Eff<State<Integer>, ?>> all = sequence(puts);
    assertEquals(3, run(State.run(0, all)).state());
  }

  @Test
  void theListOfWhatWasGathered() {
    Gather<Boolean, Prices<?>> asked = new Gather<>() {{
      perform(new Prices.InStock("tea"));
      perform(new Prices.InStock(""));
    }};
    assertEquals(2, asked.list().size());
  }
}
