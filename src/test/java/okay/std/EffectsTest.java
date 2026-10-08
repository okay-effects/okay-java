package okay.std;

import static okay.core.Eff.pure;
import static okay.core.Eff.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import okay.core.Eff;
import okay.core.Prompt;
import okay.std.State.Final;
import okay.std.Writer.Log;
import org.junit.jupiter.api.Test;

/** okay-core's TestEff on {@link Eff}, an effect an interface, a handler a prompt and an implementation of it — and
 * what a row cannot say: two handlers of one effect at once, a capability out of its handler */
class EffectsTest {

  /** effects of the test's own: an interface each */
  interface Ask {
    Eff<Integer> number();
  }

  interface Say {
    Eff<Void> line(String s);
  }

  /** every {@code number} answered {@code n}: a general handler, a capture each */
  static <A> Eff<A> asking(int n, Function<Ask, Eff<A>> body) {
    return Eff.handle((Prompt<A> p) -> body.apply(() -> p.shift0(k -> k.apply(n))));
  }

  /** the same answered in place: the capability its own handler, no prompt */
  static <A> Eff<A> answering(int n, Function<Ask, Eff<A>> body) {
    Eff<Integer> v = pure(n);
    return body.apply(() -> v);
  }

  /** the lines said, in order */
  static <A> Eff<Log<String, A>> saying(Function<Say, Eff<A>> body) {
    return Writer.<String, A>writer(w -> body.apply(w::tell));
  }

  @Test
  void anOperationAnsweredByItsHandler() {
    assertEquals((Integer) 42, run(asking(41, a -> a.number().map(x -> x + 1))));
    assertEquals((Integer) 42, run(answering(41, a -> a.number().map(x -> x + 1))));
  }

  static Eff<Integer> twoEffects(Ask a, Say s) {
    return a.number().flatMap(n -> s.line(n.toString()).map(_ -> n + 1));
  }

  @Test
  void twoEffectsTheHandlersInEitherOrder() {
    assertEquals(new Log<>(List.of("41"), 42), run(saying(s -> asking(41, a -> twoEffects(a, s)))));
    assertEquals(new Log<>(List.of("41"), 42), run(asking(41, a -> saying(s -> twoEffects(a, s)))));
  }

  static Eff<Integer> chosen(Choose ch, Say s) {
    return ch.among(List.of(1, 2)).flatMap(x -> s.line("x=" + x).map(_ -> x * 10));
  }

  @Test
  void aSayCrossesChooseChooseResumesSaysHandlerInEachPath() {
    assertEquals(new Log<>(List.of("x=1", "x=2"), List.of(10, 20)),
        run(saying(s -> Choose.all(ch -> chosen(ch, s)))));
    assertEquals(List.of(new Log<>(List.of("x=1"), 10), new Log<>(List.of("x=2"), 20)),
        run(Choose.all(ch -> saying(s -> chosen(ch, s)))));
  }

  static Eff<Integer> loop(Ask a, int n, int acc) {
    return n == 0 ? pure(acc) : a.number().flatMap(x -> loop(a, n - 1, acc + x));
  }

  @Test
  void operationsUnderAHandlerAndCrossingTwoInConstantStack() {
    assertEquals((Integer) 100_000, run(asking(1, a -> loop(a, 100_000, 0))));
    assertEquals(new Log<>(List.of(), new Log<>(List.of(), 100_000)),
        run(asking(1, a -> saying(_ -> saying(_ -> loop(a, 100_000, 0))))));
    assertEquals(new Log<>(List.of(), new Log<>(List.of(), 100_000)),
        run(answering(1, a -> saying(_ -> saying(_ -> loop(a, 100_000, 0))))));
  }

  @Test
  void answeredInPlaceUnderMultiShotChooseAndSay() {
    assertEquals(List.of(new Log<>(List.of("15"), 5), new Log<>(List.of("25"), 10)),
        run(answering(5, a -> Choose.all(ch -> saying(s ->
            ch.among(List.of(1, 2)).flatMap(x -> a.number().flatMap(n -> s.line("" + x + n).map(_ -> x * n))))))));
  }

  @Test
  void stateAndChooseTheOrderIsTheMeaning() {
    // state outside: one state threaded through the paths, in turn
    assertEquals(new Final<>(3, List.of(1, 3)),
        run(State.<Integer, List<Integer>>state(0, st -> Choose.all(ch -> ch.among(List.of(1, 2))
            .flatMap(x -> st.modify(s -> s + x)).then(st.get())))));
    // choose outside: a state per path, k resuming the state of its time
    assertEquals(List.of(new Final<>(1, 1), new Final<>(2, 2)),
        run(Choose.<Final<Integer, Integer>>all(ch -> State.state(0, st -> ch.among(List.of(1, 2))
            .flatMap(x -> st.modify(s -> s + x)).then(st.get())))));
  }

  @Test
  void twoHandlersOfOneEffectAtOnceEachOperationToItsOwn() {
    // a row picks the nearest State % Int; a capability names its handler
    Final<Integer, Final<Integer, Integer>> r = run(State.<Integer, Final<Integer, Integer>>state(0, outer ->
        State.state(100, inner -> outer.put(1).then(inner.modify(x -> x + 1))
            .then(outer.get().flatMap(a -> inner.get().map(b -> a + b))))));
    assertEquals(new Final<>(1, new Final<>(101, 102)), r);
  }

  @Test
  void aClauseRunsOutsideItsHandlerARaiseInARecoveryGoesOut() {
    assertEquals("a!", run(Throws.<String, String>catching(outer ->
        Throws.<String, String>catching(inner -> inner.raise("a"), e -> outer.raise(e + "!")), e -> pure(e))));
  }

  @Test
  void anEffectPolymorphicFunctionKnowsNothingOfTheEffects() {
    // traverse: f's effects pass through it, whatever they are — here a raise ends the traversal
    Function<Throws<String>, Eff<List<Integer>>> body = t -> traverse(List.of(1, 2, 3, 4),
        x -> x == 3 ? t.raise("three") : pure(x * 10));
    assertEquals(List.of(-1), run(Throws.catching(body, _ -> pure(List.of(-1)))));
    assertEquals(new Log<>(List.of("1", "2", "3"), List.of(1, 2, 3)),
        run(Writer.<String, List<Integer>>writer(w -> traverse(List.of(1, 2, 3), x -> w.tell(x.toString()).map(_ -> x)))));
  }

  static <X, Y> Eff<List<Y>> traverse(List<X> xs, Function<X, Eff<Y>> f) {
    Eff<List<Y>> acc = pure(new ArrayList<>());
    for (X x : xs) acc = acc.flatMap(l -> f.apply(x).map(y -> { List<Y> r = new ArrayList<>(l); r.add(y); return r; }));
    return acc;
  }

  @Test
  void aCapabilityUsedOutsideItsHandlerHasNoneToReach() {
    State<Integer> leaked = run(State.<Integer, State<Integer>>state(0, Eff::pure)).value();
    IllegalStateException e = assertThrows(IllegalStateException.class, () -> run(leaked.get()));
    assertTrue(e.getMessage().contains("outside the handler"));
  }

  @Test
  void aGeneratorAsALazyIterator() {
    int[] emitted = {0};
    Iterator<Integer> it = Emit.iterator((Emit<Integer> em) -> naturals(em, 0, emitted));
    List<Integer> first = new ArrayList<>();
    for (int i = 0; i < 5; i++) first.add(it.next());
    assertEquals(List.of(0, 1, 2, 3, 4), first);
    // an infinite body, run as far as asked
    assertEquals(5, emitted[0]);
  }

  static Eff<Void> naturals(Emit<Integer> em, int i, int[] emitted) {
    return Eff.delay(() -> { emitted[0]++; return em.emit(i); }).then(Eff.delay(() -> naturals(em, i + 1, emitted)));
  }

  @Test
  void eachEmittedValueToTheContextOutsideAnsweredInPlace() {
    assertEquals(new Log<>(List.of(1, 2, 3), "done"),
        run(Writer.<Integer, String>writer(w -> Emit.forEach(
            (Emit<Integer> em) -> em.emit(1).then(em.emit(2)).then(em.emit(3)).map(_ -> "done"), w::tell))));
    assertEquals(List.of(1, 2, 3), run(Emit.collect((Emit<Integer> em) -> em.emit(1).then(em.emit(2)).then(em.emit(3)))));
  }

  @Test
  void queensOneProgramAllOrTheFirst() {
    assertEquals(92, run(Choose.all(ch -> place(ch, 8, 0, List.of()))).size());
    Optional<List<Integer>> first = run(Choose.first(ch -> place(ch, 8, 0, List.of())));
    assertEquals(Optional.of(List.of(3, 1, 6, 2, 5, 7, 4, 0)), first);
  }

  static Eff<List<Integer>> place(Choose ch, int size, int row, List<Integer> placed) {
    if (row == size) return pure(placed);
    List<Integer> safe = new ArrayList<>();
    for (int col = 0; col < size; col++) if (safe(col, placed)) safe.add(col);
    return ch.among(safe).flatMap(col -> {
      List<Integer> next = new ArrayList<>();
      next.add(col);
      next.addAll(placed);
      return place(ch, size, row + 1, next);
    });
  }

  static boolean safe(int col, List<Integer> placed) {
    for (int d = 0; d < placed.size(); d++) {
      int c = placed.get(d);
      if (c == col || Math.abs(c - col) == d + 1) return false;
    }
    return true;
  }

  @Test
  void readerAsksInPlace() {
    assertEquals(6, run(Reader.<Integer, Integer>reader(5, r -> r.ask().map(x -> x + 1))));
  }

  @Test
  void tailCallsThroughDelayInConstantStack() {
    assertEquals(false, run(even(100_001)));
  }

  static Eff<Boolean> even(int i) {
    return i == 0 ? pure(true) : Eff.delay(() -> odd(i - 1));
  }

  static Eff<Boolean> odd(int i) {
    return i == 0 ? pure(false) : Eff.delay(() -> even(i - 1));
  }
}
