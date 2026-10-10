package okay.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import okay.kernel.Answers;
import okay.kernel.Computation;
import okay.kernel.Effects;
import okay.kernel.Fn;
import okay.kernel.General;
import okay.kernel.Op;
import okay.kernel.Prog;
import okay.kernel.Program;
import okay.kernel.Returning;
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

/** okay-kernel's TestSteps on okay-java, its Kernel: the standard handlers written once, control, failures, finalizers,
 * cancellation, the protocol — export, import, multi-shot k */
class KernelTest {
  static final Effects E = Kernel.EFFECTS;
  static final Type ST = State.of(Type.of(Integer.class));
  static final Type LOG = Writer.of(Type.of(String.class));
  static final Type ERR = Throws.of(Type.of(String.class));
  static final Type RD = Reader.of(Type.of(Integer.class));

  static <A> A run(Program<A> p) {
    return Kernel.run(p);
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
    assertEquals(42, KernelTest.<Integer>run(e -> e.<Integer, Integer, Integer>dollar(e2 -> e2.pure(21), k)));
    Program<Integer> shifted = e -> e.<Integer, Integer, Integer>shift0(kk -> e.flatMap(kk.apply(1), kk));
    assertEquals(21, KernelTest.<Integer>run(e -> e.<Integer, Integer, Integer>dollar(shifted, x -> e.pure(x + 10))));
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
    assertEquals(2, KernelTest.<Integer>run(e -> e.recover(e2 -> e2.failed(new RuntimeException("no")), t -> e.pure(t.getMessage().length()))));
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

  // -- where implementations went wrong: okay-kernel's TCK, each a check of its own

  @Test
  void kResumedUnderOtherHandlersAnOperationGoesToTheHandlerInForceWhereItRuns() {
    Type tick = Type.of("okay.core.kernel.tick");
    record Tick(Type effect) implements Op<Void> {}
    Program<Integer> body = e -> e.flatMap(e.perform(new Tick(tick)), _ -> ASK.in(e));
    // the clause resumes k under a reader of its own: the ask, in k, finds that one
    General<Integer, Integer> h = new General<>() {
      public Type effect() {
        return tick;
      }

      public Prog<Integer> ret(Integer a, Effects e) {
        return e.pure(a);
      }

      public <X> Prog<Integer> apply(Op<X> op, Fn<X, Prog<Integer>> k, Effects e) {
        return Handlers.reader(RD, 2, _ -> k.apply(null)).in(e);
      }
    };
    assertEquals(2, KernelTest.<Integer>run(Handlers.reader(RD, 1, e -> e.handle(h, body))));
  }

  @Test
  void aShift0sBodyRunsOutsideItsDelimiterAHandlerBetweenIsNotInForceThere() {
    Program<Integer> p = Handlers.reader(RD, 1, e -> e.<Integer, Integer, Integer>dollar(
        Handlers.reader(RD, 2, y -> y.<Integer, Integer, Integer>shift0(_ -> ASK.in(y))), e::pure));
    assertEquals(1, KernelTest.<Integer>run(p));
  }

  @Test
  void aFailureOfAHandlersAnswerIsTheProgramsCaughtByRecover() {
    Type none = Type.of("okay.core.kernel.none");
    Returning<Integer, Integer> h = new Returning<>() {
      public Type effect() {
        return none;
      }

      public Integer answer(Integer a) {
        throw new IllegalStateException("answer");
      }
    };
    assertEquals(7, KernelTest.<Integer>run(e -> e.recover(e2 -> e2.handle(h, e3 -> e3.pure(1)), _ -> e.pure(7))));
  }

  @Test
  void aCancellationWaitingInARegionThatFailsTheRegionsEndCancelsStill() {
    Computation<Integer> c = E.computation(e -> e.recover(e2 -> e2.uncancelable(e3 -> e3.flatMap(ASK.in(e3),
        _ -> e3.<Integer>failed(new RuntimeException("region")))), _ -> e.pure(0)), Set.of(RD));
    Step<Integer> waiting = yieldOf(c.start(Answers.NONE)).k().cancel();
    assertInstanceOf(Step.Cancelled.class, yieldOf(waiting).k().resume(5), "cancelled, not recovered");
  }

  @Test
  void aForeignProgramEndedByAnAbortCrossingItItsFinalizersRunTheirOperationsAnsweredHere() {
    Type err = Throws.of(Type.of("okay.core.kernel.t2"));
    Computation<Integer> inner = E.computation(e -> e.ensuring(Handlers.raise(err, "stop"), Handlers.tell(LOG, "fin")), Set.of(err, LOG));
    Results.Logged<String, Results.Result<String, Integer>> r =
        run(Handlers.writer(LOG, Handlers.<String, Integer>throwsOf(err, e -> e.foreign(inner))));
    assertEquals(List.of("fin"), r.log());
    assertInstanceOf(Results.Err.class, r.value());
  }

  // -- effects from effects: one's own effect translated to another, hidden inside, intercepted

  static final Type AUDIT = Type.of("okay.core.audit");
  static final Type TICKET = Type.of("okay.core.ticket");

  /** one's own effect, its cases as a visitor */
  interface AuditOps<X> extends Op<X> {
    <R> R fold(AuditCases<X, R> c);
  }

  interface AuditCases<X, R> {
    R note(String s, okay.kernel.Same<Void, X> v);
  }

  record Note(String s) implements AuditOps<Void> {
    public Type effect() {
      return AUDIT;
    }

    public <R> R fold(AuditCases<Void, R> c) {
      return c.note(s, new okay.kernel.Same.Refl<>());
    }
  }

  record Next() implements Op<Integer> {
    public Type effect() {
      return TICKET;
    }
  }

  /** an operation of the effect found by its identity, as one of its family: the test's trusted step */
  @SuppressWarnings("unchecked")
  static <X> AuditOps<X> audit(Op<X> op) {
    return (AuditOps<X>) op;
  }

  static Program<Void> note(String s) {
    return e -> e.perform(new Note(s));
  }

  static final Handlers.Translation TO_WRITER = new Handlers.Translation() {
    public <X> Program<X> apply(Op<X> op) {
      return audit(op).fold(new AuditCases<X, Program<X>>() {
        public Program<X> note(String s, okay.kernel.Same<Void, X> v) {
          return e -> e.map(Handlers.tell(LOG, "[audit] " + s).in(e), v::apply);
        }
      });
    }
  };

  @Test
  void onesEffectTranslatedToAnotherOutsideItNoKWritten() {
    Program<Integer> audited = e -> e.flatMap(note("a").in(e), _ -> e.map(note("b").in(e), _ -> 7));
    Results.Logged<String, Integer> r = run(Handlers.writer(LOG, Handlers.translate(AUDIT, TO_WRITER, audited)));
    assertEquals(List.of("[audit] a", "[audit] b"), r.log());
    assertEquals(7, r.value());
    // multi-shot through it: every path of a choice, each its own audit
    Program<Integer> paths = e -> e.flatMap(Handlers.among(List.of(1, 2)).in(e), n -> e.map(note("" + n).in(e), _ -> n));
    Results.Logged<String, List<Integer>> all = run(Handlers.writer(LOG, Handlers.all(Handlers.translate(AUDIT, TO_WRITER, paths))));
    assertEquals(List.of(1, 2), all.value());
    assertEquals(2, all.log().size());
  }

  static <A> Program<Results.Final<Integer, A>> tickets(Type hidden, Program<A> body) {
    return Handlers.state(hidden, 0, Handlers.translate(TICKET, new Handlers.Translation() {
      public <X> Program<X> apply(Op<X> op) {
        return e -> e.flatMap(Handlers.<Integer>modify(hidden, i -> i + 1).in(e), _ -> Handlers.<X>get(hidden).in(e));
      }
    }, body));
  }

  @Test
  void onesEffectOnAnotherInstalledInsideHiddenAFreshIdentityTheCallersOwnUntouched() {
    Type hidden = Type.fresh();
    Program<Integer> two = e -> e.flatMap(e.perform(new Next()), a -> e.flatMap(e.perform(new Next()), b ->
        e.map(Handlers.<Integer>get(ST).in(e), s -> a * 10 + b + s * 100)));
    Results.Final<Integer, Results.Final<Integer, Integer>> r = run(Handlers.state(ST, 5, tickets(hidden, two)));
    assertEquals(5, r.state());
    assertEquals(2, r.value().state());
    assertEquals(512, r.value().value());
  }

  static Handlers.Translation asked(java.util.function.IntUnaryOperator f) {
    return new Handlers.Translation() {
      public <X> Program<X> apply(Op<X> op) {
        return Reader.<Integer, X>narrow(op).fold(new Reader.Cases<Integer, X, Program<X>>() {
          public Program<X> ask(okay.kernel.Same<Integer, X> v) {
            return e -> e.map(Handlers.<Integer>ask(RD).in(e), r -> v.apply(f.applyAsInt(r)));
          }
        });
      }
    };
  }

  @Test
  void interceptorsWhatAnEffectDoesInABodyChangedNestedTheClausesOwnOperationsOutward() {
    assertEquals(10, KernelTest.<Integer>run(Handlers.reader(RD, 5, Handlers.intercept(RD, asked(r -> r * 2), ASK))));
    // the inner interceptor's ask goes to the outer one, its ask to the reader: (5 * 2) + 1
    assertEquals(11, KernelTest.<Integer>run(Handlers.reader(RD, 5, Handlers.intercept(RD, asked(r -> r * 2),
        Handlers.intercept(RD, asked(r -> r + 1), ASK)))));
  }
}
