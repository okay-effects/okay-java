package okay.core;

import static okay.core.Cont0.dollar;
import static okay.core.Cont0.lift;
import static okay.core.Cont0.pure;
import static okay.core.Cont0.reset0;
import static okay.core.Cont0.shift0;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import okay.std.PState0;
import okay.std.State.Final;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.EnumSource;

/** okay-core's TestCont0: {@code shift0} and {@code $}, where they agree with shift and reset, where they do not.
 * The levels are in the types: a program under one delimiter is {@code Cont0<P, P, A>}, {@code P} the level outside */
@ParameterizedClass
@EnumSource(Backend0.class)
class Cont0Test {

  /** the loop or the reference: each test on both */
  @Parameter
  Backend0 backend;

  <A> A value(Cont0<A, A, A> c) {
    return backend.value(c);
  }

  @Test
  void oneDelimiterAsWithShift() {
    // reset0(1 + shift0(k => k(k(10)))) is 12
    Cont0<Integer, Integer, Integer> p = reset0(
        Cont0.<Integer, Integer, Integer, Cont0<Integer, Integer, Integer>, Integer>shift0(k -> k.apply(10).flatMap(k))
            .map(x -> x + 1));
    assertEquals(12, value(p));
  }

  @Test
  void answerTypeModified() {
    Cont0<String, String, String> p = reset0(
        Cont0.<String, String, Integer, Cont0<String, String, String>, Integer>shift0(k -> k.apply(5).map(String::valueOf))
            .map(x -> x * 2));
    assertEquals("10", value(p));
  }

  /** {@code %d}: the hole a String, the answer modified from {@code T} to {@code Integer -> T} — Danvy's printf */
  private <S, T> Cont0<Cont0<T, T, T>, Cont0<S, S, Function<Integer, T>>, String> d() {
    return shift0(k -> pure(x -> value(k.apply(x.toString()))));
  }

  /** {@code %s}: the answer from {@code T} to {@code String -> T} */
  private <S, T> Cont0<Cont0<T, T, T>, Cont0<S, S, Function<String, T>>, String> s() {
    return shift0(k -> pure(x -> value(k.apply(x))));
  }

  @Test
  void printfTheAnswerTypeModifiedOncePerHole() {
    // reset0("a=" + %d + ", b=" + %s): a function of an Integer and a String, its type the format's
    Function<Integer, Function<String, String>> format = value(reset0(
        this.<Function<Integer, Function<String, String>>, Function<String, String>>d()
            .flatMap(a -> this.<Function<String, String>, String>s().map(b -> "a=" + a + ", b=" + b))));
    assertEquals("a=1, b=x", format.apply(1).apply("x"));
  }

  @Test
  void pstateTheTypeChangedInTheAnswerType() {
    // okay-core's: an Integer in, a String put, read back as a String
    Cont0<Final<String, Integer>, Final<String, Integer>, Final<String, Integer>> p = PState0.pstate(7,
        PState0.<Final<String, Integer>, Final<String, Integer>, Integer>get()
            .flatMap(n -> PState0.<Final<String, Integer>, Final<String, Integer>, Integer, String>put("x".repeat(n)))
            .flatMap(_ -> PState0.<Final<String, Integer>, Final<String, Integer>, String>get())
            .map(String::length));
    assertEquals(new Final<>("xxxxxxx", 7), value(p));
  }

  private static String pstateGetting(String type) {
    return """
        Cont0<Final<String, Integer>, Final<String, Integer>, Final<String, Integer>> p = PState0.pstate(7,
            PState0.<Final<String, Integer>, Final<String, Integer>, Integer, String>put("x")
                .flatMap(_ -> PState0.<Final<String, Integer>, Final<String, Integer>, %s>get()).map(Object::hashCode));
        """.formatted(type);
  }

  @Test
  void pstateAGetOfTheWrongTypeDoesNotCompile() throws java.io.IOException {
    assertEquals(List.of(), FxTest.errors(pstateGetting("String")));
    assertFalse(FxTest.errors(pstateGetting("Integer")).isEmpty());
  }

  @Test
  void aBodyThatDropsKAbortsTheDelimiter() {
    boolean[] ran = {false};
    Cont0<Integer, Integer, Integer> p = reset0(
        Cont0.<Integer, Integer, Integer, Cont0<Integer, Integer, Integer>, Integer>shift0(_ -> pure(-1))
            .map(x -> { ran[0] = true; return x + 1; }));
    assertEquals(-1, value(p));
    assertFalse(ran[0]);
  }

  @Test
  void theBodyRunsOutsideTheDelimiter() {
    // reset0(reset0(shift0 k1. shift0 k2. k2(k2(k1 1))) + 10) * 2): k1 = _ + 10, k2 = _ * 2 — 1 + 10, twice doubled
    Cont0<Cont0<Integer, Integer, Integer>, Cont0<Integer, Integer, Integer>, Integer> inner = reset0(
        Cont0.<Integer, Integer, Integer, Cont0<Cont0<Integer, Integer, Integer>, Cont0<Integer, Integer, Integer>, Integer>, Integer>shift0(
            k1 -> Cont0.<Integer, Integer, Integer, Cont0<Integer, Integer, Integer>, Integer>shift0(
                k2 -> k1.apply(1).flatMap(k2).flatMap(k2)))
            .map(x -> x + 10));
    assertEquals(44, value(reset0(inner.map(x -> x * 2))));
    // with shift, k2 is the empty context under the delimiter the first body runs under: 11, doubled once
    Cont<Integer, Integer, Integer> onCont = Cont.reset(
        Cont.<Integer, Integer, Integer>reset(Cont.<Integer, Integer, Integer>shift(
                k1 -> Cont.<Integer, Integer, Integer>shift(k2 -> k1.apply(1).flatMap(k2).flatMap(k2)))
            .map(x -> x + 10)).map(x -> x * 2));
    assertEquals(22, Cont.run(onCont));
  }

  @Test
  void twoShift0DeepABodyDropsBoth() {
    Cont0<Cont0<Integer, Integer, Integer>, Cont0<Integer, Integer, Integer>, Integer> inner = reset0(
        Cont0.<Integer, Integer, Integer, Cont0<Cont0<Integer, Integer, Integer>, Cont0<Integer, Integer, Integer>, Integer>, Integer>shift0(
            _ -> Cont0.<Integer, Integer, Integer, Cont0<Integer, Integer, Integer>, Integer>shift0(_ -> pure(-1)))
            .map(x -> x + 10));
    assertEquals(-1, value(reset0(inner.map(x -> x * 2))));
    // a shift gets out of one only
    Cont<Integer, Integer, Integer> onCont = Cont.reset(
        Cont.<Integer, Integer, Integer>reset(Cont.<Integer, Integer, Integer>shift(
                _ -> Cont.<Integer, Integer, Integer>shift(_ -> Cont.pure(-1)))
            .map(x -> x + 10)).map(x -> x * 2));
    assertEquals(-2, Cont.run(onCont));
  }

  @Test
  void dollarsReturnClauseIsPutBackByK() {
    // a handler's ret: the body's value through it, each time k resumes
    Cont0<List<Integer>, List<Integer>, List<Integer>> p = dollar(
        (Integer a) -> Cont0.<List<Integer>, List<Integer>>pure(List.of(a)),
        Cont0.<List<Integer>, List<Integer>, List<Integer>, Cont0<List<Integer>, List<Integer>, List<Integer>>, Integer>shift0(
                k -> k.apply(1).flatMap(xs -> k.apply(2).map(ys -> concat(xs, ys))))
            .map(x -> x * 10));
    assertEquals(List.of(10, 20), value(p));
  }

  private static <X> List<X> concat(List<X> a, List<X> b) {
    List<X> r = new ArrayList<>(a);
    r.addAll(b);
    return r;
  }

  /** writer's answer at the top, the log and choose's value */
  record W(List<String> log, List<Integer> value) {}

  @Test
  void handlersAsDelimitersATellLiftedOverChoose() {
    // writer: ret $ body; tell: a shift0 up to it. choose: ret $ body; among: a shift0 up to it, k for each.
    // A tell under choose is lifted: one shift0 more, over choose's delimiter
    Function<Integer, Cont0<Cont0<Cont0<W, W, W>, Cont0<W, W, W>, List<Integer>>, Cont0<Cont0<W, W, W>, Cont0<W, W, W>, List<Integer>>, Integer>> body =
        x -> Cont0.<Cont0<W, W, W>, Cont0<W, W, W>, Cont0<W, W, W>, List<Integer>, Void>lift(tell("x=" + x))
            .map(_ -> x * 10);
    var prog = among(List.of(1, 2)).flatMap(body);
    assertEquals(new W(List.of("x=1", "x=2"), List.of(10, 20)), value(writer(choose(prog))));
  }

  private static Cont0<W, W, W> writer(Cont0<Cont0<W, W, W>, Cont0<W, W, W>, List<Integer>> body) {
    return dollar((List<Integer> a) -> Cont0.<W, W>pure(new W(List.of(), a)), body);
  }

  private static Cont0<Cont0<W, W, W>, Cont0<W, W, W>, Void> tell(String s) {
    return shift0(k -> k.apply(null).map(w -> new W(prepend(s, w.log()), w.value())));
  }

  private static Cont0<Cont0<W, W, W>, Cont0<W, W, W>, List<Integer>> choose(
      Cont0<Cont0<Cont0<W, W, W>, Cont0<W, W, W>, List<Integer>>, Cont0<Cont0<W, W, W>, Cont0<W, W, W>, List<Integer>>, Integer> body) {
    return dollar((Integer a) -> Cont0.<Cont0<W, W, W>, List<Integer>>pure(List.of(a)), body);
  }

  private static Cont0<Cont0<Cont0<W, W, W>, Cont0<W, W, W>, List<Integer>>, Cont0<Cont0<W, W, W>, Cont0<W, W, W>, List<Integer>>, Integer> among(
      List<Integer> xs) {
    return shift0(k -> {
      Cont0<Cont0<W, W, W>, Cont0<W, W, W>, List<Integer>> acc = pure(List.of());
      for (Integer x : xs) acc = acc.flatMap(l -> k.apply(x).map(r -> concat(l, r)));
      return acc;
    });
  }

  private static List<String> prepend(String s, List<String> l) {
    List<String> r = new ArrayList<>();
    r.add(s);
    r.addAll(l);
    return r;
  }

  @Test
  void bindsNestedLeftAndRightInConstantStack() {
    Cont0<Integer, Integer, Integer> left = pure(0);
    for (int i = 0; i < 100_000; i++) left = left.map(x -> x + 1);
    assertEquals(100_000, value(left));
    assertEquals(100_000, value(right(100_000)));
  }

  private static Cont0<Integer, Integer, Integer> right(int n) {
    return n == 0 ? pure(0) : Cont0.<Integer, Integer>pure(1).flatMap(x -> right(n - 1).map(r -> r + x));
  }

  @Test
  void shift0InARowInConstantStack() {
    // no body is put under a delimiter, none nest
    assertEquals(200_000, value(reset0(shifts(100_000, 0))));
  }

  private static Cont0<Cont0<Integer, Integer, Integer>, Cont0<Integer, Integer, Integer>, Integer> shifts(int i, int acc) {
    if (i == 0) return pure(acc);
    return Cont0.<Integer, Integer, Integer, Cont0<Integer, Integer, Integer>, Integer>shift0(k -> k.apply(1))
        .flatMap(x -> Cont0.<Cont0<Integer, Integer, Integer>, Integer>pure(x + 1))
        .flatMap(x -> shifts(i - 1, acc + x));
  }

  @Test
  void kUnderManyFramesAppliedThreeTimesIsNotWorn() {
    Cont0<Cont0<Long, Long, Long>, Cont0<Long, Long, Long>, Integer> p =
        Cont0.<Long, Long, Long, Cont0<Long, Long, Long>, Integer>shift0(k -> calls(k, 3, 0));
    for (int j = 1; j <= 100_000; j++) {
      p = j % 2 == 0 ? p.map(x -> x + 1) : p.flatMap(x -> Cont0.<Cont0<Long, Long, Long>, Integer>pure(x + 1));
    }
    assertEquals((100_003L + 100_002L + 100_001L) * 10, value(reset0(p.map(Integer::longValue))));
  }

  private static Cont0<Long, Long, Long> calls(Function<Integer, Cont0<Long, Long, Long>> k, int i, long acc) {
    if (i == 0) return pure(acc);
    return k.apply(i).flatMap(r -> Cont0.<Long, Long>pure(r * 10)).flatMap(r -> calls(k, i - 1, acc + r));
  }

  @Test
  void tailCallsThroughDelayInConstantStack() {
    assertEquals(false, value(isEven(100_001)));
  }

  private static Cont0<Boolean, Boolean, Boolean> isEven(int i) {
    return i == 0 ? pure(true) : Cont0.delay(() -> isOdd(i - 1));
  }

  private static Cont0<Boolean, Boolean, Boolean> isOdd(int i) {
    return i == 0 ? pure(false) : Cont0.delay(() -> isEven(i - 1));
  }
}
