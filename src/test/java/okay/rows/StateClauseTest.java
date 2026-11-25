package okay.rows;

import static okay.rows.Effects.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.stream.Stream;
import okay.effect.Eff;
import okay.effect.Operation;
import okay.effect.Pure;
import okay.rows.std.Choose;
import org.junit.jupiter.api.Test;

/** A handler with a state as one function ({@link StateClause}): the state a value of each path. */
class StateClauseTest {

  sealed interface Counter<T> extends Operation<T, Counter<?>> {
    record Get() implements Counter<Integer> {}
    record Put(int value) implements Counter<Void> {}
  }

  sealed interface Flip<T> extends Operation<T, Flip<?>> {
    record Coin() implements Flip<Boolean> {}
  }

  static <A, R> Comp<A, R> counting(int from, Comp<A, ? super Eff<Counter<?>, R>> body) {
    return handle(from, (state, op, k) -> switch (op) {
      case Counter.Get g -> k.resume(g, state);
      case Counter.Put p -> k.resume(p, null, p.value());
    }, body);
  }

  record Final<A>(int state, A value) {}

  static <A, R> Comp<Final<A>, R> counted(int from, Comp<A, ? super Eff<Counter<?>, R>> body) {
    return handle(from, (state, op, k) -> switch (op) {
      case Counter.Get g -> k.resume(g, state);
      case Counter.Put p -> k.resume(p, null, p.value());
    }, Final::new, body);
  }

  static <A, R> Comp<List<A>, R> both(Comp<List<A>, ? super Eff<Flip<?>, R>> body) {
    return handle((op, k) -> switch (op) {
      case Flip.Coin c -> k.resume(c, true).flatMap(a -> k.resume(c, false).map(b ->
          Stream.concat(a.stream(), b.stream()).toList()));
    }, body);
  }

  static Comp<Integer, Eff<Counter<?>, ?>> countdown(int steps) {
    return perform(new Counter.Get()).flatMap(n ->
        n == 0 ? pure(steps) : perform(new Counter.Put(n - 1)).flatMap(done -> countdown(steps + 1)));
  }

  @Test
  void theStateGoesThroughTheProgram() {
    assertEquals(2_000_000, run(counting(2_000_000, countdown(0))));
  }

  @Test
  void theLastStateAndTheValue() {
    Comp<String, Eff<Counter<?>, ?>> p =
        perform(new Counter.Put(41)).flatMap(done -> perform(new Counter.Get())).flatMap(n ->
            perform(new Counter.Put(n + 1)).map(done -> "done"));
    assertEquals(new Final<>(42, "done"), run(counted(0, p)));
  }

  static Comp<List<Integer>, Eff<Counter<?>, Eff<Flip<?>, ?>>> ways() {
    return up(perform(new Flip.Coin())).flatMap(a ->
        perform(new Counter.Put(a ? 10 : 20)).flatMap(x ->
            up(perform(new Flip.Coin())).flatMap(b ->
                perform(new Counter.Get()).flatMap(n ->
                    perform(new Counter.Put(n + (b ? 1 : 2))).flatMap(y ->
                        perform(new Counter.Get()).map(List::of))))));
  }

  /** the state inside the choice: a rest taken across the handler keeps its state for each path */
  @Test
  void aStatePerPathInsideAChoice() {
    assertEquals(List.of(11, 12, 21, 22), run(both(counting(0, ways()))));
  }

  /** the state outside the choice: one state through every path */
  @Test
  void oneStateOutsideAChoice() {
    Comp<List<Integer>, Eff<Flip<?>, Eff<Counter<?>, ?>>> p =
        perform(new Flip.Coin()).flatMap(a ->
            up(perform(new Counter.Get())).flatMap(n ->
                up(perform(new Counter.Put(n + 1))).map(done -> List.of(n))));
    assertEquals(new Final<>(2, List.of(0, 1)), run(counted(0, both(p))));
  }

  /** a clause with a state that takes the rest: it resumes twice, each time with a state of its own */
  @Test
  void aClauseResumesTwiceWithTwoStates() {
    Comp<List<Integer>, Pure> p = handle(0, (state, op, k) -> switch (op) {
      case Counter.Get g -> k.resume(g, state);
      case Counter.Put put -> k.resume(put, null, put.value()).flatMap(a ->
          k.resume(put, null, put.value() * 10).map(b -> Stream.concat(a.stream(), b.stream()).toList()));
    }, perform(new Counter.Put(1)).flatMap(done ->
        perform(new Counter.Get()).flatMap(n ->
            perform(new Counter.Put(n + 1)).flatMap(again -> perform(new Counter.Get()).map(List::of)))));
    assertEquals(List.of(2, 20, 11, 110), run(p));
  }

  /** resumed without a state after the rest was taken: the state it was asked in, not what a path left */
  @Test
  void aClauseGoesOnWithTheStateItWasAskedIn() {
    Comp<List<Integer>, Pure> p = handle(5, (state, op, k) -> switch (op) {
      case Counter.Get g -> k.resume(g, state);
      case Counter.Put put -> k.resume(put, null, put.value()).flatMap(a ->
          k.resume(put, null).map(b -> Stream.concat(a.stream(), b.stream()).toList()));
    }, perform(new Counter.Put(7)).flatMap(done -> perform(new Counter.Get()).map(List::of)));
    assertEquals(List.of(7, 5), run(p));
  }

  /** with the standard choice outside: the library's own general handler takes the rest across the clause */
  @Test
  void underTheStandardChoice() {
    Comp<Integer, Eff<Counter<?>, Eff<Choose, ?>>> p =
        up(Choose.among(List.of(1, 2, 3))).flatMap(x ->
            perform(new Counter.Get()).flatMap(n ->
                perform(new Counter.Put(n + x)).flatMap(done -> perform(new Counter.Get()))));
    assertEquals(List.of(101, 102, 103), run(Choose.all(counting(100, p))));
  }
}
