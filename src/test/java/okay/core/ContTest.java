package okay.core;

import static okay.core.Cont.delay;
import static okay.core.Cont.pure;
import static okay.core.Cont.reset;
import static okay.core.Cont.run;
import static okay.core.Cont.shift;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** the monad alone: shift, reset, the loop — okay-core's TestCont, the parts without effects */
class ContTest {

  @Test
  void contextCapturedAsAProgramAppliedTwice() {
    // reset(1 + shift(k => k(k(10)))) is 12
    Cont<Integer, Integer, Integer> p =
        reset(Cont.<Integer, Integer, Integer>shift(k -> k.apply(10).flatMap(k)).map(x -> x + 1));
    assertEquals(12, run(p));
  }

  @Test
  void answerTypeModified() {
    // reset(shift(k => k(5).toString) * 2) is a String
    Cont<String, String, String> p =
        reset(Cont.<Integer, String, Integer>shift(k -> k.apply(5).map(String::valueOf)).map(x -> x * 2));
    assertEquals("10", run(p));
  }

  @Test
  void aShiftThatDropsKAbortsTheDelimiter() {
    boolean[] ran = {false};
    Cont<Integer, Integer, Integer> p =
        reset(Cont.<Integer, Integer, Integer>shift(_ -> pure(-1)).map(x -> { ran[0] = true; return x + 1; }));
    assertEquals(-1, run(p));
    assertFalse(ran[0]);
  }

  @Test
  void theTopIsADelimiter() {
    assertEquals(9, run(Cont.<Integer, Integer, Integer>shift(k -> k.apply(1).flatMap(k)).map(x -> x * 3)));
  }

  @Test
  void aShiftCapturesToTheNearestDelimiter() {
    Cont<Integer, Integer, Integer> inner = reset(Cont.<Integer, Integer, Integer>shift(k -> k.apply(1)).map(x -> x + 10));
    assertEquals(22, run(reset(inner.map(x -> x * 2))));
  }

  @Test
  void aShiftsBodyRunsUnderTheDelimiter() {
    Cont<Integer, Integer, Integer> p = reset(
        Cont.<Integer, Integer, Integer>shift(k ->
            Cont.<Integer, Integer, Integer>shift(k2 -> k2.apply(1).map(x -> x * 100)).flatMap(k))
        .map(x -> x + 1));
    assertEquals(200, run(p));
  }

  @Test
  void bindsNestedLeftAndRightInConstantStack() {
    Cont<Integer, Integer, Integer> left = pure(0);
    for (int i = 0; i < 100_000; i++) left = left.flatMap(x -> pure(x + 1));
    assertEquals(100_000, run(left));
    assertEquals(100_000, run(right(100_000)));
  }

  private static Cont<Integer, Integer, Integer> right(int n) {
    return n == 0 ? pure(0) : Cont.<Integer, Integer>pure(1).flatMap(x -> right(n - 1).map(r -> r + x));
  }

  @Test
  void shiftsInARowInConstantStack() {
    // the delimiters do not nest
    assertEquals(200_000, run(shifts(100_000, 0)));
    assertEquals(200_000, run(reset(shifts(100_000, 0))));
  }

  private static Cont<Integer, Integer, Integer> shifts(int i, int acc) {
    if (i == 0) return pure(acc);
    return Cont.<Integer, Integer, Integer>shift(k -> k.apply(1))
        .flatMap(x -> Cont.<Integer, Integer>pure(x + 1))
        .flatMap(x -> shifts(i - 1, acc + x));
  }

  @Test
  void delayBuildsTheProgramWhenTheLoopGetsToIt() {
    int[] built = {0};
    Cont<Integer, Integer, Integer> p = delay(() -> { built[0]++; return pure(1); });
    assertEquals(0, built[0]);
    assertEquals(1, run(p));
    assertEquals(1, run(p));
    assertEquals(2, built[0]);
  }

  @Test
  void tailCallsThroughDelayInConstantStack() {
    assertEquals(true, run(isEven(100_000)));
    assertEquals(false, run(isEven(100_001)));
  }

  private static Cont<Boolean, Boolean, Boolean> isEven(int i) {
    return i == 0 ? pure(true) : delay(() -> isOdd(i - 1));
  }

  private static Cont<Boolean, Boolean, Boolean> isOdd(int i) {
    return i == 0 ? pure(false) : delay(() -> isEven(i - 1));
  }

  @Test
  void mapsNestedLeftAndRightInConstantStack() {
    Cont<Integer, Integer, Integer> left = pure(0);
    for (int i = 0; i < 100_000; i++) left = left.map(x -> x + 1);
    assertEquals(100_000, run(left));
    assertEquals(100_000, run(go(100_000)));
  }

  private static Cont<Integer, Integer, Integer> go(int i) {
    return i == 0 ? pure(0) : Cont.<Integer, Integer, Integer>delay(() -> go(i - 1)).map(x -> x + 1);
  }

  @Test
  void mapsAndBindsMixedKeepTheirOrder() {
    Cont<String, String, String> p = Cont.<String, Integer>pure(1)
        .map(x -> x + 1)
        .flatMap(x -> Cont.<String, Integer>pure(x * 10))
        .map(String::valueOf)
        .flatMap(s -> Cont.<String, String>pure(s + "!"))
        .map(s -> s.repeat(2));
    assertEquals("20!20!", run(p));
  }

  @Test
  void aShiftUnderLeftNestedBindsAndMaps() {
    Cont<Integer, Integer, Integer> inner =
        shift(k -> k.apply(1).flatMap(a -> k.apply(10).map(b -> a + b)));
    Cont<Integer, Integer, Integer> p =
        reset(inner.map(x -> x + 1).flatMap(x -> Cont.<Integer, Integer>pure(x * 2)).map(x -> x + 100));
    // k(x) = (x + 1) * 2 + 100: k(1) = 104, k(10) = 122
    assertEquals(226, run(p));
  }

  @Test
  void kUnderManyFramesAppliedThreeTimesIsNotWorn() {
    Cont<Long, Long, Integer> p = Cont.<Long, Long, Integer>shift(k -> calls(k, 3, 0));
    for (int j = 1; j <= 100_000; j++) {
      p = j % 2 == 0 ? p.map(x -> x + 1) : p.flatMap(x -> Cont.<Long, Integer>pure(x + 1));
    }
    // k(i) = i + 100 000: the shots 3, 2, 1, each times 10
    assertEquals((100_003L + 100_002L + 100_001L) * 10, run(reset(p.map(Integer::longValue))));
  }

  private static Cont<Long, Long, Long> calls(Function<Integer, Cont<Long, Long, Long>> k, int i, long acc) {
    if (i == 0) return pure(acc);
    return k.apply(i)
        .flatMap(r -> Cont.<Long, Long>pure(r * 10))
        .flatMap(r -> calls(k, i - 1, acc + r));
  }

  @Test
  void aCapturedKResumedManyTimes() {
    // a pure tree: k is a value, resumed 1000 times, each run sees the whole rest
    Cont<Integer, Integer, Integer> p = reset(
        Cont.<Integer, Integer, Integer>shift(k -> sum(k, 1000, 0)).map(x -> x * 2).flatMap(x -> Cont.<Integer, Integer>pure(x + 1)));
    // sum over i = 1..1000 of (2i + 1)
    assertEquals(1000 * 1001 + 1000, run(p));
  }

  private static Cont<Integer, Integer, Integer> sum(Function<Integer, Cont<Integer, Integer, Integer>> k, int i, int acc) {
    return i == 0 ? pure(acc) : k.apply(i).flatMap(r -> sum(k, i - 1, acc + r));
  }
}
