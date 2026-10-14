package okay.core;

import static okay.core.Eff.pure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import okay.core.Eff.Top;
import okay.std.State;
import okay.std.State.Final;
import org.junit.jupiter.api.Test;

/** FAILURES in okay-java's own programs: a function of the program's throwing, or {@link Eff#failed}, unwound from
 * where it is to the nearest guard — {@link Eff#recover}, {@link Eff#ensuring} — through the handlers between; a
 * clause's failure outside its handler; a resumed rest's under its delimiters again; none taking it, out of the run.
 * On the machine: the reference runs no failures */
class GuardTest {

  static Eff<Top, Integer> boom(String what) {
    return Eff.<Top, Integer>pure(1).map(_ -> {
      throw new IllegalArgumentException(what);
    });
  }

  @Test
  void aMapsThrowRecovered() {
    assertEquals(4, Eff.run(Eff.recover(boom("boom"), t -> pure(t.getMessage().length()))));
    assertEquals(5, Eff.run(Eff.recover(Eff.<Top, Integer>failed(new RuntimeException("5 wide")), t -> pure(5))));
  }

  @Test
  void aFailureNoGuardTakesLeavesTheRun() {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Eff.run(boom("out")));
    assertEquals("out", e.getMessage());
    // a guard around a failure of another kind of its own: not this one's business, out as before
    assertThrows(IllegalArgumentException.class, () -> Eff.run(Eff.ensuring(boom("out"), pure(null))));
  }

  @Test
  void ensuringRunsOnAValueAndOnAFailureThenTheSameWayOn() {
    List<String> log = new ArrayList<>();
    Eff<Top, Void> fin = Eff.sync(() -> {
      log.add("fin");
      return null;
    });
    assertEquals(3, Eff.run(Eff.ensuring(pure(3), fin)));
    assertEquals(List.of("fin"), log);
    assertEquals(-1, Eff.run(Eff.recover(Eff.ensuring(boom("x"), fin), _ -> pure(-1))));
    assertEquals(List.of("fin", "fin"), log);
    // a finalizer's failure replaces the failure
    Eff<Top, Void> bad = Eff.sync(() -> {
      throw new IllegalStateException("fin failed");
    });
    assertEquals("fin failed", Eff.run(Eff.recover(Eff.ensuring(boom("x"), bad).map(String::valueOf), t -> pure(t.getMessage()))));
  }

  @Test
  void aRecoverThatThrowsGoesToTheGuardOutside() {
    Eff<Top, Integer> inner = Eff.recover(boom("a"), t -> {
      throw new IllegalStateException(t.getMessage() + "b");
    });
    assertEquals("ab", Eff.run(Eff.recover(inner.map(String::valueOf), t -> pure(t.getMessage()))));
  }

  /** a failure inside a handler's body unwinds through the handler: the handler gone, its return not run */
  @Test
  void aFailureUnwindsThroughAHandler() {
    List<String> log = new ArrayList<>();
    Eff<Top, Final<Integer, Integer>> body = State.state(0, new State.Body<Top, Integer, Integer>() {
      public <M extends Top> Eff<M, Integer> in(State<M, Integer> st) {
        return st.put(1).then(Eff.<M, Integer>failed(new RuntimeException("in"))).map(x -> {
          log.add("not reached");
          return x;
        });
      }
    });
    assertEquals("in", Eff.run(Eff.recover(body.map(f -> "ok"), t -> pure(t.getMessage()))));
    assertEquals(List.of(), log);
  }

  /** a guard inside a handler's body does not take a failure of a clause: the clause runs outside the handler */
  @Test
  void aClausesFailureIsOutsideItsHandler() {
    Eff<Top, Integer> p = Eff.recover(Eff.handle(new Eff.Body<Top, Integer, Integer>() {
      public <M extends Top> Eff<M, Integer> in(Prompt<Top, M, Integer> pr) {
        // the recover inside the body: not the clause's
        return Eff.recover(pr.shift0(k -> {
          throw new IllegalStateException("clause");
        }), _ -> pure(-1));
      }
    }, Eff::pure), t -> pure(t.getMessage().length()));
    assertEquals(6, Eff.run(p));
  }

  /** a {@code shift0} across a guard keeps it in k: resumed, the rest fails under the guard again — each time */
  @Test
  void aGuardCrossedByACaptureIsInK() {
    List<Integer> seen = new ArrayList<>();
    Eff<Top, Integer> p = Eff.handle(new Eff.Body<Top, Integer, Integer>() {
      public <M extends Top> Eff<M, Integer> in(Prompt<Top, M, Integer> pr) {
        Eff<M, Integer> op = pr.shift0(k -> k.apply(1).flatMap(a -> k.apply(2).map(b -> a + b)));
        return Eff.recover(op.map(x -> {
          seen.add(x);
          if (x == 1) throw new IllegalStateException("one");
          return x * 10;
        }), _ -> pure(100));
      }
    }, Eff::pure);
    assertEquals(120, Eff.run(p));
    assertEquals(List.of(1, 2), seen);
  }

  /** the first frame of a resumed rest — the operation's own map — failing: the guard that k crossed takes it */
  @Test
  void aResumedRestsFirstFrameFailsUnderTheGuardAgain() {
    Eff<Top, Integer> p = Eff.handle(new Eff.Body<Top, Integer, Integer>() {
      public <M extends Top> Eff<M, Integer> in(Prompt<Top, M, Integer> pr) {
        Eff<M, Integer> op = pr.shift0(k -> k.apply(7));
        return Eff.recover(op.map((Function<Integer, Integer>) x -> {
          throw new IllegalStateException("first");
        }), t -> pure(t.getMessage().length()));
      }
    }, Eff::pure);
    assertEquals(5, Eff.run(p));
  }

  /** a failure is not an error of the JVM: those are not caught */
  @Test
  void anErrorOfTheJvmIsNotAFailure() {
    assertThrows(StackOverflowError.class, () -> Eff.run(Eff.recover(Eff.<Top, Integer>pure(1).map(_ -> {
      throw new StackOverflowError();
    }), _ -> pure(0))));
  }

  @Test
  void manyFailuresInARowInConstantStack() {
    Eff<Top, Integer> p = pure(0);
    for (int i = 0; i < 100_000; i++) {
      Eff<Top, Integer> q = p;
      // the value so far, failed with, recovered one more: 100 000 guards in force at the bottom, on the heap
      p = Eff.recover(q.flatMap(n -> Eff.failed(new RuntimeException(String.valueOf(n)))),
          t -> pure(Integer.parseInt(t.getMessage()) + 1));
    }
    assertTrue(Eff.run(p) == 100_000);
  }
}
