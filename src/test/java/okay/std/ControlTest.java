package okay.std;

import static okay.core.Eff.pure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.function.BiFunction;
import okay.core.Backend;
import okay.core.Eff;
import okay.core.Prompt;
import okay.std.Throws.Result;
import okay.std.Writer.Log;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.EnumSource;

/** okay-core's TestControl and TestShiftAtHandler: shift and reset beside the handlers, by prompt — a shift to a
 * reset's, Danvy–Filinski's; to a handler's, k the rest WITH the handler, its ret in each resumption */
@ParameterizedClass
@EnumSource(Backend.class)
class ControlTest {

  @Parameter
  Backend backend;

  <A> A run(Eff<A> p) {
    return backend.run(p);
  }

  @Test
  void aBodyDroppingKAbortsTheReset() {
    boolean[] ran = {false};
    assertEquals(-1, (int) run(Eff.<Integer>reset(p -> p.<Integer>shift(_ -> pure(-1)).map(x -> { ran[0] = true; return x; }))));
    assertFalse(ran[0]);
  }

  @Test
  void multiShotKResumedTwiceTheAnswersJoined() {
    assertEquals(List.of(1, 10, 2, 20), run(Eff.<List<Integer>>reset(p -> p.<Integer>shift(k ->
        k.apply(1).flatMap(a -> k.apply(2).map(b -> concat(a, b)))).map(x -> List.of(x, x * 10)))));
  }

  private static List<Integer> concat(List<Integer> a, List<Integer> b) {
    return java.util.stream.Stream.concat(a.stream(), b.stream()).toList();
  }

  @Test
  void aShiftsBodyRunsUnderTheResetAShiftInItCapturesUpToTheSame() {
    assertEquals(200, (int) run(Eff.<Integer>reset(p -> p.<Integer>shift(k ->
        p.<Integer>shift(k2 -> k2.apply(1).map(x -> x * 100)).flatMap(k)).map(x -> x + 1))));
  }

  @Test
  void aShiftCapturesUpToItsOwnResetOnly() {
    assertEquals(109, (int) run(Eff.<Integer>reset(p -> p.<Integer>shift(k -> k.apply(1).map(x -> x * 100)))
        .flatMap(a -> Eff.<Integer>reset(q -> q.<Integer>shift(k -> k.apply(2).map(x -> x + 7)).map(x -> x + a)))));
  }

  @Test
  void aShiftToAResetFurtherOutCrossingANearerOne() {
    // by prompt, not the nearest: k is the rest up to the outer reset, the inner one in it
    assertEquals(1001, (int) run(Eff.<Integer>reset(outer -> Eff.<Integer>reset(_ ->
        outer.<Integer>shift(k -> k.apply(1).map(x -> x + 1000)).map(x -> x * 10)).map(x -> x / 10))));
  }

  @Test
  void kAppliedRunsNothingOfTheRest() {
    boolean[] ran = {false};
    assertEquals(0, (int) run(Eff.<Integer>reset(p -> p.<Integer>shift(k -> {
      k.apply(1);
      return Eff.delay(() -> pure(ran[0] ? 1 : 0));
    }).flatMap(x -> { ran[0] = true; return pure(x); }))));
  }

  static Eff<Integer> many(int n, int acc) {
    return n == 0 ? pure(acc)
        : Eff.<Integer>reset(p -> p.<Integer>shift(k -> k.apply(1)).map(x -> x + 1)).flatMap(x -> many(n - 1, acc + x));
  }

  @Test
  void aHundredThousandResetsAndShiftsInConstantStack() {
    assertEquals(200_000, (int) run(many(100_000, 0)));
  }

  static Eff<Integer> nested(Prompt<Integer> p, int n) {
    return n == 0 ? pure(0) : p.<Integer>shift(k -> k.apply(1).map(x -> x + 1)).flatMap(x -> nested(p, n - 1).map(y -> y + x));
  }

  @Test
  void aHundredThousandShiftsEachKResumedNestedInItsBody() {
    // the delimiters nest, one a shift: the machine in constant stack; the reference, recursive, on fewer
    int n = backend == Backend.REF ? 1_000 : 100_000;
    assertEquals(2 * n, (int) run(Eff.<Integer>reset(p -> nested(p, n))));
  }

  // A SHIFT UP TO A HANDLER

  interface Ask {
    Eff<Integer> number();
  }

  /** every {@code number} answered {@code e} by a general clause, the body's value times {@code scale}; the body given
   * the handler's prompt too, to shift to */
  static Eff<Integer> env(int e, int scale, BiFunction<Prompt<Integer>, Ask, Eff<Integer>> body) {
    return Eff.handle((Prompt<Integer> p) -> body.apply(p, () -> p.shift0(k -> k.apply(e))), a -> pure(a * scale));
  }

  @Test
  void kResumedTwiceTheHandlersRetInEachResumption() {
    // k(a) = (3 + a) * 10; the body under the handler again, no ret: its ask the handler's
    assertEquals(560, (int) run(env(3, 10, (p, a) -> p.<Integer>shift(k -> k.apply(1)
        .flatMap(x -> a.number().flatMap(e -> k.apply(x + e))).map(x -> x + 100)).flatMap(x -> a.number().map(e -> e + x)))));
  }

  @Test
  void tellsInTheBodyInKAfterTheShiftInOrder() {
    assertEquals(new Log<>(List.of("b1", "x=1", "b2", "x=2"), 4), run(Writer.<String, Integer>writer(w -> env(2, 1, (p, a) ->
        p.<Integer>shift(k -> w.tell("b1").then(k.apply(1)).flatMap(x -> w.tell("b2").then(k.apply(x))))
            .flatMap(x -> w.tell("x=" + x).then(a.number()).map(e -> e * x))))));
  }

  @Test
  void kDroppedTheBodysValueTheHandlersAnswerNoRet() {
    assertEquals(new Log<>(List.of("a"), 8), run(Writer.<String, Integer>writer(w -> env(7, 3, (p, a) ->
        w.tell("a").then(p.<Integer>shift(_ -> a.number().map(e -> e + 1))).flatMap(x -> w.tell("never").map(_ -> x))))));
  }

  @Test
  void aShiftUnderAHandlerUnderAResetTheResetUntouched() {
    // k(x) = 4 + x: k(k(1)) = 9, doubled outside the handler
    assertEquals(18, (int) run(Eff.<Integer>reset(_ -> env(4, 1, (p, a) ->
        p.<Integer>shift(k -> k.apply(1).flatMap(k)).flatMap(x -> a.number().map(e -> e + x))).map(x -> x * 2))));
  }

  @Test
  void aRaiseInKThatKsFailureTheShiftsBodyGoesOn() {
    // a shift to the handler of the failure: k is the rest WITH it, a raise in k k's own Failed
    Result<String, Integer> r = run(Eff.<Integer, Result<String, Integer>>handle((Prompt<Result<String, Integer>> p) -> {
      Throws<String> t = new Throws<>() {
        public <X> Eff<X> raise(String e) {
          return p.shift0(_ -> pure(new Result.Failed<>(e)));
        }
      };
      return p.<Integer>shift(k -> k.apply(1).flatMap(r1 -> k.apply(2).map(r2 -> new Result.Ok<>(size(r1) + size(r2)))))
          .flatMap(x -> x == 1 ? t.<Integer>raise("one") : pure(x * 10));
    }, a -> pure(new Result.Ok<>(a))));
    assertEquals(new Result.Ok<>(3 + 20), r);
  }

  private static int size(Result<String, Integer> r) {
    return switch (r) {
      case Result.Ok<String, Integer>(var a) -> a;
      case Result.Failed<String, Integer>(var e) -> e.length();
    };
  }
}
