package okay.java.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import okay.kernel.Answers;
import okay.kernel.Computation;
import okay.kernel.Direct;
import okay.kernel.Effects;
import okay.kernel.Fn;
import okay.kernel.Op;
import okay.kernel.Prog;
import okay.kernel.Program;
import okay.kernel.Step;
import okay.kernel.Type;
import okay.kernel.std.Choose;
import okay.kernel.std.Emit;
import okay.kernel.std.Handlers;
import okay.kernel.std.Reader;
import okay.kernel.std.Results;
import okay.kernel.std.State;
import okay.kernel.std.Throws;
import okay.kernel.std.Writer;
import org.junit.jupiter.api.Test;

/** okay-kernel's TestSteps on okay-java's bridge: the standard handlers written once, control, failures, finalizers,
 * cancellation, the protocol — export, import, multi-shot k */
class OnJavaTest {
  static final Effects E = OnJava.EFFECTS;
  static final Type ST = State.of(Type.of(Integer.class));
  static final Type LOG = Writer.of(Type.of(String.class));
  static final Type ERR = Throws.of(Type.of(String.class));
  static final Type RD = Reader.of(Type.of(Integer.class));

  static <A> A run(Program<A> p) {
    return OnJava.run(p);
  }

  static Program<Integer> count(int n) {
    return e -> n == 0 ? Handlers.<Integer>get(ST).in(e)
        : e.flatMap(Handlers.<Integer>modify(ST, s -> s + 1).in(e), _ -> count(n - 1).in(e));
  }

  @Test
  void stateAsACell100000OperationsInConstantStack() {
    Results.Final<Integer, Integer> r = run(Handlers.state(ST, 0, count(100_000)));
    assertEquals(100_000, r.state());
  }

  @Test
  void writerAndStateReaderAnsweredInPlace() {
    Program<Integer> body = e -> e.flatMap(Handlers.<Integer>ask(RD).in(e), n ->
        e.flatMap(Handlers.tell(LOG, "n=" + n).in(e), _ ->
            e.flatMap(Handlers.put(ST, n).in(e), _ -> Handlers.<Integer>get(ST).in(e))));
    Results.Logged<String, Results.Final<Integer, Integer>> r =
        run(Handlers.reader(RD, 7, Handlers.<String, Results.Final<Integer, Integer>>writer(LOG, Handlers.state(ST, 0, body))));
    assertEquals(List.of("n=7"), r.log());
    assertEquals(7, r.value().state());
  }

  @Test
  void aRaiseEndsTheBodyItsFinalizerRunHandleWithAnswersIt() {
    boolean[] finalized = {false};
    Program<Integer> body = e -> e.ensuring(Handlers.raise(ERR, "boom"), e2 -> {
      finalized[0] = true;
      return e2.pure(null);
    });
    Results.Result<String, Integer> r = run(Handlers.throwsOf(ERR, body));
    assertInstanceOf(Results.Err.class, r);
    assertTrue(finalized[0]);
    assertEquals(4, run(Handlers.<String, Integer>handleWith(ERR, body, m -> e -> e.pure(m.length()))));
  }

  @Test
  void chooseEveryPathTheFirst() {
    Program<Integer> pair = e -> e.flatMap(Handlers.among(List.of(1, 2)).in(e), a ->
        e.map(Handlers.among(List.of(10, 20)).in(e), b -> a * 100 + b));
    assertEquals(List.of(110, 120, 210, 220), run(Handlers.all(pair)));
    assertEquals(Optional.of(110), run(Handlers.first(pair)));
  }

  @Test
  void shift0AndDollarTheLaws() {
    Fn<Integer, Prog<Integer>> k = x -> E.pure(x * 2);
    assertEquals(42, OnJavaTest.<Integer>run(e -> e.<Integer, Integer, Integer>dollar(e2 -> e2.pure(21), k)));
    Program<Integer> shifted = e -> e.<Integer, Integer, Integer>shift0(kk -> e.flatMap(kk.apply(1), kk));
    assertEquals(21, OnJavaTest.<Integer>run(e -> e.<Integer, Integer, Integer>dollar(shifted, x -> e.pure(x + 10))));
  }

  @Test
  void aGeneratorValuesPulledOneByOne() {
    Type em = Emit.of(Type.of(Integer.class));
    Program<String> three = e -> e.flatMap(Handlers.send(em, 1).in(e), _ ->
        e.flatMap(Handlers.send(em, 2).in(e), _ -> e.pure("done")));
    List<Integer> pulled = new ArrayList<>();
    Results.Gen<Integer, String> g = run(Handlers.<Integer, String>generate(em, three));
    while (g instanceof Results.Next<Integer, String>(var v, var rest)) {
      pulled.add(v);
      g = run(rest);
    }
    assertEquals(List.of(1, 2), pulled);
    assertEquals("done", ((Results.End<Integer, String>) g).value());
  }

  @Test
  void aFailureIsDataRecoverCatchesItAnExceptionOfAFunctionIsOne() {
    Computation<Integer> c = E.computation(e -> e.<Integer, Integer>map(e.pure(1), _ -> {
      throw new IllegalStateException("x");
    }), Set.of());
    assertInstanceOf(Step.Failed.class, c.start(Answers.NONE));
    assertEquals(2, OnJavaTest.<Integer>run(e -> e.recover(e2 -> e2.failed(new RuntimeException("no")), t -> e.pure(t.getMessage().length()))));
  }

  /** an operation nothing here handles: handed out */
  static final Program<Integer> ASK = Handlers.ask(RD);

  @Test
  void exportAnOperationHandedOutKResumedTwiceMultiShot() {
    Computation<Integer> c = E.computation(e -> e.map(ASK.in(e), n -> n + 1), Set.of(RD));
    Step.Yield<Integer, Integer> ask = yieldOf(c.start(Answers.NONE));
    assertEquals(new Step.Done<>(42), ask.k().resume(41));
    assertEquals(new Step.Done<>(2), ask.k().resume(1));
    assertEquals(Set.of(RD), c.effects());
  }

  @Test
  void exportAnsweredInPlaceByTheHostNoYield() {
    Answers host = new Answers() {
      public boolean answers(Type t) {
        return t == RD;
      }

      public <X> X value(Op<X> op) {
        return Reader.<Integer, X>narrow(op).fold(new Reader.Cases<Integer, X, X>() {
          public X ask(okay.kernel.Same<Integer, X> v) {
            return v.apply(41);
          }
        });
      }
    };
    Computation<Integer> c = E.computation(e -> e.map(ASK.in(e), n -> n + 1), Set.of(RD));
    assertEquals(new Step.Done<>(42), c.start(host));
  }

  @SuppressWarnings("unchecked") // the test knows the operation: an ask of an Integer
  static Step.Yield<Integer, Integer> yieldOf(Step<Integer> s) {
    return (Step.Yield<Integer, Integer>) assertInstanceOf(Step.Yield.class, s);
  }

  @Test
  void kFailTheComputationCatchesItWhereTheOperationWas() {
    Computation<Integer> c = E.computation(e -> e.recover(ASK, _ -> e.pure(-1)), Set.of(RD));
    assertEquals(new Step.Done<>(-1), yieldOf(c.start(Answers.NONE)).k().fail(new RuntimeException("x")));
  }

  @Test
  void kCancelFinalizersRunNotCatchableInsideUncancelableItWaits() {
    int[] finalized = {0};
    Program<Void> fin = e -> {
      finalized[0]++;
      return e.pure(null);
    };
    Computation<Integer> c = E.computation(e -> e.recover(e2 -> e2.ensuring(ASK, fin), _ -> e.pure(0)), Set.of(RD));
    Step<Integer> cancelled = yieldOf(c.start(Answers.NONE)).k().cancel();
    assertInstanceOf(Step.Cancelled.class, cancelled, "cancelled, recover not taken");
    assertEquals(1, finalized[0], "the finalizer once");
    Computation<Integer> u = E.computation(e -> e.uncancelable(ASK), Set.of(RD));
    Step<Integer> waiting = yieldOf(u.start(Answers.NONE)).k().cancel();
    assertInstanceOf(Step.Yield.class, waiting, "the region waits");
    assertInstanceOf(Step.Cancelled.class, yieldOf(waiting).k().resume(5), "its end cancels");
  }

  @Test
  void importAnothersProgramUnderThisOnesHandlersInPlaceAndByK() {
    Computation<Integer> inner = E.computation(e -> e.flatMap(ASK.in(e), n ->
        e.map(Handlers.among(List.of(n, n + 1)).in(e), x -> x * 10)), Set.of(RD, Choose.TYPE));
    Program<List<Integer>> outer = Handlers.reader(RD, 4, Handlers.all(e -> e.foreign(inner)));
    assertEquals(List.of(40, 50), run(outer));
  }

  @Test
  void directOkayJavasProgramsBuiltDirectlyUnderAHandlerWrittenOnce() {
    Program<Integer> body = e -> {
      Direct d = assertInstanceOf(Direct.class, e);
      return d.prog(d.flatMap(d.pure(20), x -> d.map(d.perform(new Reader.Ask<Integer>(RD)), y -> (Integer) x + (Integer) y)));
    };
    assertEquals(42, OnJavaTest.<Integer>run(Handlers.reader(RD, 22, body)));
  }
}
