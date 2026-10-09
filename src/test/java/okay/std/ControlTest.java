package okay.std;

import static okay.core.Eff.pure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import okay.core.Backend;
import okay.core.Eff;
import okay.core.Eff.Top;
import okay.core.Prompt;
import okay.std.Throws.Result;
import okay.std.Writer.Log;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.EnumSource;

/** okay-core's TestControl and TestShiftAtHandler: shift and reset beside the handlers, by prompt — a shift to a
 * reset's, Danvy–Filinski's; to a handler's, k the rest WITH the handler, its ret in each resumption. A reset's body
 * at a fresh level too: k gives a program outside it, which runs inside as it is */
@ParameterizedClass
@EnumSource(Backend.class)
class ControlTest {

  @Parameter
  Backend backend;

  <A> A run(Eff<Top, A> p) {
    return backend.run(p);
  }

  @Test
  void aBodyDroppingKAbortsTheReset() {
    boolean[] ran = {false};
    assertEquals(-1, (int) run(Eff.reset(new Eff.Body<Top, Integer, Integer>() {
      public <M extends Top> Eff<M, Integer> in(Prompt<Top, M, Integer> p) {
        return p.<Integer>shift(_ -> pure(-1)).map(x -> { ran[0] = true; return x; });
      }
    })));
    assertFalse(ran[0]);
  }

  @Test
  void multiShotKResumedTwiceTheAnswersJoined() {
    assertEquals(List.of(1, 10, 2, 20), run(Eff.reset(new Eff.Body<Top, List<Integer>, List<Integer>>() {
      public <M extends Top> Eff<M, List<Integer>> in(Prompt<Top, M, List<Integer>> p) {
        return p.<Integer>shift(k -> k.apply(1).flatMap(a -> k.apply(2).map(b -> concat(a, b))).at())
            .map(x -> List.of(x, x * 10));
      }
    })));
  }

  private static List<Integer> concat(List<Integer> a, List<Integer> b) {
    return java.util.stream.Stream.concat(a.stream(), b.stream()).toList();
  }

  @Test
  void aShiftsBodyRunsUnderTheResetAShiftInItCapturesUpToTheSame() {
    assertEquals(200, (int) run(Eff.reset(new Eff.Body<Top, Integer, Integer>() {
      public <M extends Top> Eff<M, Integer> in(Prompt<Top, M, Integer> p) {
        return p.<Integer>shift(k -> p.<Integer>shift(k2 -> k2.apply(1).map(x -> x * 100).at()).flatMap(k)).map(x -> x + 1);
      }
    })));
  }

  /** a reset whose body is one shift, k applied to {@code x}, the answer moved by {@code f} */
  static <L> Eff<L, Integer> once(int x, java.util.function.UnaryOperator<Integer> f, java.util.function.UnaryOperator<Integer> rest) {
    return Eff.reset(new Eff.Body<L, Integer, Integer>() {
      public <M extends L> Eff<M, Integer> in(Prompt<L, M, Integer> p) {
        return p.<Integer>shift(k -> k.apply(x).map(f).at()).map(rest);
      }
    });
  }

  @Test
  void aShiftCapturesUpToItsOwnResetOnly() {
    assertEquals(109, (int) run(ControlTest.<Top>once(1, x -> x * 100, x -> x).flatMap(a -> once(2, x -> x + 7, x -> x + a))));
  }

  @Test
  void aShiftToAResetFurtherOutCrossingANearerOne() {
    // by prompt, not the nearest: k is the rest up to the outer reset, the inner one in it
    assertEquals(1001, (int) run(Eff.reset(new Eff.Body<Top, Integer, Integer>() {
      public <M extends Top> Eff<M, Integer> in(Prompt<Top, M, Integer> outer) {
        return Eff.reset(new Eff.Body<M, Integer, Integer>() {
          public <N extends M> Eff<N, Integer> in(Prompt<M, N, Integer> inner) {
            return outer.<Integer>shift(k -> k.apply(1).map(x -> x + 1000).at()).<N>at().map(x -> x * 10);
          }
        }).map(x -> x / 10);
      }
    })));
  }

  @Test
  void kAppliedRunsNothingOfTheRest() {
    boolean[] ran = {false};
    assertEquals(0, (int) run(Eff.reset(new Eff.Body<Top, Integer, Integer>() {
      public <M extends Top> Eff<M, Integer> in(Prompt<Top, M, Integer> p) {
        return p.<Integer>shift(k -> {
          k.apply(1);
          return Eff.delay(() -> pure(ran[0] ? 1 : 0));
        }).flatMap(x -> { ran[0] = true; return pure(x); });
      }
    })));
  }

  static <L> Eff<L, Integer> many(int n, int acc) {
    return n == 0 ? pure(acc) : ControlTest.<L>once(1, x -> x, x -> x + 1).flatMap(x -> many(n - 1, acc + x));
  }

  @Test
  void aHundredThousandResetsAndShiftsInConstantStack() {
    assertEquals(200_000, (int) run(many(100_000, 0)));
  }

  static <L, M extends L> Eff<M, Integer> nested(Prompt<L, M, Integer> p, int n) {
    return n == 0 ? pure(0) : p.<Integer>shift(k -> k.apply(1).map(x -> x + 1).at()).flatMap(x -> nested(p, n - 1).map(y -> y + x));
  }

  @Test
  void aHundredThousandShiftsEachKResumedNestedInItsBody() {
    // the delimiters nest, one a shift: the machine in constant stack; the reference, recursive, on fewer
    int n = backend == Backend.REF ? 1_000 : 100_000;
    assertEquals(2 * n, (int) run(Eff.reset(new Eff.Body<Top, Integer, Integer>() {
      public <M extends Top> Eff<M, Integer> in(Prompt<Top, M, Integer> p) {
        return nested(p, n);
      }
    })));
  }

  // A SHIFT UP TO A HANDLER

  interface Ask<L> {
    Eff<L, Integer> number();
  }

  /** a body given the handler's prompt, to shift to, and its capability */
  interface Env<L> {
    <M extends L> Eff<M, Integer> in(Prompt<L, M, Integer> p, Ask<M> a);
  }

  /** every {@code number} answered {@code e} by a general clause, the body's value times {@code scale} */
  static <L> Eff<L, Integer> env(int e, int scale, Env<L> body) {
    return Eff.handle(new Eff.Body<L, Integer, Integer>() {
      public <M extends L> Eff<M, Integer> in(Prompt<L, M, Integer> p) {
        return body.in(p, () -> p.shift0(k -> k.apply(e)));
      }
    }, a -> pure(a * scale));
  }

  @Test
  void kResumedTwiceTheHandlersRetInEachResumption() {
    // k(a) = (3 + a) * 10; the body under the handler again, no ret: its ask the handler's
    assertEquals(560, (int) run(env(3, 10, new Env<Top>() {
      public <M extends Top> Eff<M, Integer> in(Prompt<Top, M, Integer> p, Ask<M> a) {
        return p.<Integer>shift(k -> k.apply(1).<M>at().flatMap(x -> a.number().flatMap(e -> k.apply(x + e))).map(x -> x + 100))
            .flatMap(x -> a.number().map(e -> e + x));
      }
    })));
  }

  @Test
  void tellsInTheBodyInKAfterTheShiftInOrder() {
    assertEquals(new Log<>(List.of("b1", "x=1", "b2", "x=2"), 4), run(Writer.writer(new Writer.Body<Top, String, Integer>() {
      public <M extends Top> Eff<M, Integer> in(Writer<M, String> w) {
        return env(2, 1, new Env<M>() {
          public <N extends M> Eff<N, Integer> in(Prompt<M, N, Integer> p, Ask<N> a) {
            return p.<Integer>shift(k -> w.tell("b1").<N>at().then(k.apply(1)).flatMap(x -> w.tell("b2").then(k.apply(x))))
                .flatMap(x -> w.tell("x=" + x).<N>at().then(a.number()).map(e -> e * x));
          }
        });
      }
    })));
  }

  @Test
  void kDroppedTheBodysValueTheHandlersAnswerNoRet() {
    assertEquals(new Log<>(List.of("a"), 8), run(Writer.writer(new Writer.Body<Top, String, Integer>() {
      public <M extends Top> Eff<M, Integer> in(Writer<M, String> w) {
        return env(7, 3, new Env<M>() {
          public <N extends M> Eff<N, Integer> in(Prompt<M, N, Integer> p, Ask<N> a) {
            return w.tell("a").<N>at().then(p.<Integer>shift(_ -> a.number().map(e -> e + 1))).flatMap(x -> w.tell("never").map(_ -> x));
          }
        });
      }
    })));
  }

  @Test
  void aShiftUnderAHandlerUnderAResetTheResetUntouched() {
    // k(x) = 4 + x: k(k(1)) = 9, doubled outside the handler
    assertEquals(18, (int) run(Eff.reset(new Eff.Body<Top, Integer, Integer>() {
      public <M extends Top> Eff<M, Integer> in(Prompt<Top, M, Integer> outer) {
        return env(4, 1, new Env<M>() {
          public <N extends M> Eff<N, Integer> in(Prompt<M, N, Integer> p, Ask<N> a) {
            return p.<Integer>shift(k -> k.apply(1).flatMap(k).at()).flatMap(x -> a.number().map(e -> e + x));
          }
        }).map(x -> x * 2);
      }
    })));
  }

  @Test
  void aRaiseInKThatKsFailureTheShiftsBodyGoesOn() {
    // a shift to the handler of the failure: k is the rest WITH it, a raise in k k's own Failed
    Result<String, Integer> r = run(Eff.handle(new Eff.Body<Top, Integer, Result<String, Integer>>() {
      public <M extends Top> Eff<M, Integer> in(Prompt<Top, M, Result<String, Integer>> p) {
        Throws<M, String> t = new Throws<>() {
          public <X> Eff<M, X> raise(String e) {
            return p.shift0(_ -> pure(new Result.Failed<>(e)));
          }
        };
        return p.<Integer>shift(k -> k.apply(1).flatMap(r1 -> k.apply(2).map(r2 -> (Result<String, Integer>) new Result.Ok<String, Integer>(size(r1) + size(r2)))).at())
            .flatMap(x -> x == 1 ? t.<Integer>raise("one") : pure(x * 10));
      }
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
