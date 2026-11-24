package okay.rows;

import static okay.rows.Effects.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import okay.effect.Eff;
import okay.effect.Operation;
import okay.rows.std.State;
import org.junit.jupiter.api.Test;

/** Effects as GADTs ({@link Operation}) and handlers as one function of the operation and the continuation
 * ({@link Clause}): typed by the continuation, answered in place where the clause only resumes. */
class ClauseTest {

  sealed interface Prices<T> extends Operation<T, Prices<?>> {
    record Price(String item) implements Prices<Long> {}
    record InStock(String item) implements Prices<Boolean> {}
  }

  sealed interface Ask<T> extends Operation<T, Ask<?>> {
    record Confirm(String question) implements Ask<Boolean> {}
    record Fail<T>(String why) implements Ask<T> {}
  }

  sealed interface Choose<T> extends Operation<T, Choose<?>> {
    record Flip() implements Choose<Boolean> {}
  }

  static Comp<Long, Eff<Prices<?>, ?>> total(List<String> cart, int i, long sum) {
    if (i == cart.size()) return pure(sum);
    String item = cart.get(i);
    return perform(new Prices.InStock(item)).flatMap(in ->
        !in ? total(cart, i + 1, sum)
            : perform(new Prices.Price(item)).flatMap(p -> total(cart, i + 1, sum + p)));
  }

  static Comp<String, Eff<Prices<?>, Eff<Ask<?>, ?>>> shop(List<String> cart) {
    return total(cart, 0, 0).flatMap(sum ->
        sum <= 100 ? pure("paid " + sum)
            : up(perform(new Ask.Confirm("pay " + sum + "?"))).flatMap(yes ->
                yes ? pure("paid " + sum) : up(perform(new Ask.Fail<String>("declined")))));
  }

  static <A, R> Comp<A, R> priced(Map<String, Long> table, Comp<A, ? super Eff<Prices<?>, R>> body) {
    return handle((op, k) -> switch (op) {
      case Prices.Price p -> k.resume(p, table.getOrDefault(p.item(), 0L));
      case Prices.InStock s -> k.resume(s, table.containsKey(s.item()));
    }, body);
  }

  static <R> Comp<String, R> asking(boolean yes, Comp<String, ? super Eff<Ask<?>, R>> body) {
    return handle((op, k) -> switch (op) {
      case Ask.Confirm c -> k.resume(c, yes).map(r -> r + " (asked: " + c.question() + ")");
      case Ask.Fail<?> f -> k.unwind().map(left -> "failed: " + f.why());
    }, body);
  }

  static <A, R> Comp<List<A>, R> every(Comp<List<A>, ? super Eff<Choose<?>, R>> body) {
    return handle((op, k) -> switch (op) {
      case Choose.Flip f -> k.resume(f, true).flatMap(a -> k.resume(f, false).map(b ->
          Stream.concat(a.stream(), b.stream()).toList()));
    }, body);
  }

  static final Map<String, Long> TABLE = Map.of("tea", 4L, "cup", 11L, "kettle", 120L);

  @Test
  void aClauseThatOnlyResumesAnswers() {
    assertEquals("paid 15", run(asking(true, priced(TABLE, shop(List.of("tea", "cup", "spoon"))))));
  }

  @Test
  void aClauseGoesOnAfterTheRest() {
    assertEquals("paid 124 (asked: pay 124?)", run(asking(true, priced(TABLE, shop(List.of("tea", "kettle"))))));
  }

  @Test
  void aClauseThatDoesNotResumeLeavesTheRest() {
    assertEquals("failed: declined (asked: pay 124?)",
        run(asking(false, priced(TABLE, shop(List.of("tea", "kettle"))))));
  }

  @Test
  void theRestOfADroppedProgramIsUnwound() {
    List<String> log = new ArrayList<>();
    Comp<String, Eff<Ask<?>, ?>> body =
        Effects.<String, Eff<Ask<?>, ?>>fit(perform(new Ask.Fail<String>("no"))).ensuring(() -> {
          log.add("released");
          return pure(null);
        });
    assertEquals("failed: no", run(asking(true, body)));
    assertEquals(List.of("released"), log);
  }

  @Test
  void aContinuationResumedTwice() {
    Comp<List<String>, Eff<Choose<?>, Eff<Prices<?>, Eff<Ask<?>, ?>>>> ways =
        perform(new Choose.Flip()).flatMap(kettle ->
            perform(new Choose.Flip()).flatMap(cup ->
                up(shop(List.of(kettle ? "kettle" : "tea", cup ? "cup" : "spoon"))).map(List::of)));
    Comp<List<String>, Eff<Ask<?>, ?>> priced = priced(TABLE, every(ways));
    Comp<List<String>, okay.effect.Pure> asked = handle((op, k) -> switch (op) {
      case Ask.Confirm c -> k.resume(c, true);
      case Ask.Fail<?> f -> k.unwind().map(left -> List.of("failed"));
    }, priced);
    assertEquals(List.of("paid 131", "paid 120", "paid 15", "paid 4"), run(asked));
  }

  /** a state in place inside a choice: each way its own, the clause of the choice taking the rest */
  @Test
  void aStatePerPathUnderAClause() {
    Comp<List<Integer>, Eff<State<Integer>, Eff<Choose<?>, ?>>> ways =
        up(perform(new Choose.Flip())).flatMap(a ->
            State.<Integer>put(a ? 10 : 20).flatMap(x ->
                up(perform(new Choose.Flip())).flatMap(b ->
                    State.<Integer>get().flatMap(n ->
                        State.<Integer>put(n + (b ? 1 : 2)).flatMap(y ->
                            State.<Integer>get().map(List::of))))));
    assertEquals(List.of(11, 12, 21, 22), run(every(State.run(0, ways).map(f -> f.value()))));
  }

  sealed interface Counter<T> extends Operation<T, Counter<?>> {
    record Next() implements Counter<Integer> {}
  }

  static Comp<Integer, Eff<Counter<?>, ?>> count(int left, int sum) {
    return left == 0 ? pure(sum) : perform(new Counter.Next()).flatMap(n -> count(left - 1, sum + n));
  }

  /** a tail-recursive program under a clause that only resumes: in place, constant stack */
  @Test
  void tailRecursionUnderAClause() {
    int n = 2_000_000;
    Comp<Integer, okay.effect.Pure> p = handle((op, k) -> switch (op) {
      case Counter.Next next -> k.resume(next, 1);
    }, count(n, 0));
    assertEquals(n, run(p));
  }

  /** the same, the clause going on after the rest each time: the rest taken, the stack still the machine's */
  @Test
  void aClauseAfterTheRestManyTimes() {
    int n = 100_000;
    Comp<Integer, okay.effect.Pure> p = handle((op, k) -> switch (op) {
      case Counter.Next next -> k.resume(next, 1).map(r -> r + 1);
    }, count(n, 0));
    assertEquals(2 * n, run(p));
  }

  @Test
  void theOperationIsTheWitness() {
    Comp<Integer, okay.effect.Pure> p = handle((op, k) -> switch (op) {
      case Counter.Next next -> k.resume(new Counter.Next(), 1);
    }, count(1, 0));
    assertThrows(IllegalArgumentException.class, () -> run(p));
  }

  @Test
  void aFailingClauseFailsOutsideItsHandler() {
    Clause<Counter<?>, Integer, okay.effect.Pure> failing = (op, k) -> { throw new IllegalStateException("clause"); };
    Comp<Integer, okay.effect.Pure> p = handle(failing, count(1, 0));
    assertEquals("clause", assertThrows(IllegalStateException.class, () -> run(p)).getMessage());
  }
}
