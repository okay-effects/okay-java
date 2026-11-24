package okay.core;

import static okay.core.Comp.pure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import okay.core.Comp.Top;
import okay.std.State;
import okay.std.State.Final;
import org.junit.jupiter.api.Test;

/** FAILURES in okay-java's own programs: a function of the program's throwing, or {@link Comp#failed}, unwound from
 * where it is to the nearest guard — {@link Comp#recover}, {@link Comp#ensuring} — through the handlers between; a
 * clause's failure outside its handler; a resumed rest's under its delimiters again; none taking it, out of the run.
 * On the machine: the reference runs no failures */
class GuardTest {

  static Comp<Integer, Top> boom(String what) {
    return Comp.<Top, Integer>pure(1).map(_ -> {
      throw new IllegalArgumentException(what);
    });
  }

  @Test
  void aMapsThrowRecovered() {
    assertEquals(4, Comp.run(Comp.recover(boom("boom"), t -> pure(t.getMessage().length()))));
    assertEquals(5, Comp.run(Comp.recover(Comp.<Top, Integer>failed(new RuntimeException("5 wide")), t -> pure(5))));
  }

  @Test
  void aFailureNoGuardTakesLeavesTheRun() {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Comp.run(boom("out")));
    assertEquals("out", e.getMessage());
    // a guard around a failure of another kind of its own: not this one's business, out as before
    assertThrows(IllegalArgumentException.class, () -> Comp.run(Comp.ensuring(boom("out"), pure(null))));
  }

  @Test
  void ensuringRunsOnAValueAndOnAFailureThenTheSameWayOn() {
    List<String> log = new ArrayList<>();
    Comp<Void, Top> fin = Comp.sync(() -> {
      log.add("fin");
      return null;
    });
    assertEquals(3, Comp.run(Comp.ensuring(pure(3), fin)));
    assertEquals(List.of("fin"), log);
    assertEquals(-1, Comp.run(Comp.recover(Comp.ensuring(boom("x"), fin), _ -> pure(-1))));
    assertEquals(List.of("fin", "fin"), log);
    // a finalizer's failure replaces the failure
    Comp<Void, Top> bad = Comp.sync(() -> {
      throw new IllegalStateException("fin failed");
    });
    assertEquals("fin failed", Comp.run(Comp.recover(Comp.ensuring(boom("x"), bad).map(String::valueOf), t -> pure(t.getMessage()))));
  }

  @Test
  void aRecoverThatThrowsGoesToTheGuardOutside() {
    Comp<Integer, Top> inner = Comp.recover(boom("a"), t -> {
      throw new IllegalStateException(t.getMessage() + "b");
    });
    assertEquals("ab", Comp.run(Comp.recover(inner.map(String::valueOf), t -> pure(t.getMessage()))));
  }

  /** a failure inside a handler's body unwinds through the handler: the handler gone, its return not run */
  @Test
  void aFailureUnwindsThroughAHandler() {
    List<String> log = new ArrayList<>();
    Comp<Final<Integer, Integer>, Top> body = State.state(0, new State.Body<Top, Integer, Integer>() {
      public <M extends Top> Comp<Integer, M> in(State<M, Integer> st) {
        return st.put(1).then(Comp.<M, Integer>failed(new RuntimeException("in"))).map(x -> {
          log.add("not reached");
          return x;
        });
      }
    });
    assertEquals("in", Comp.run(Comp.recover(body.map(f -> "ok"), t -> pure(t.getMessage()))));
    assertEquals(List.of(), log);
  }

  /** a guard inside a handler's body does not take a failure of a clause: the clause runs outside the handler */
  @Test
  void aClausesFailureIsOutsideItsHandler() {
    Comp<Integer, Top> p = Comp.recover(Comp.handle(new Comp.Body<Top, Integer, Integer>() {
      public <M extends Top> Comp<Integer, M> in(Prompt<Top, M, Integer> pr) {
        // the recover inside the body: not the clause's
        return Comp.recover(pr.shift0(k -> {
          throw new IllegalStateException("clause");
        }), _ -> pure(-1));
      }
    }, Comp::pure), t -> pure(t.getMessage().length()));
    assertEquals(6, Comp.run(p));
  }

  /** a {@code shift0} across a guard keeps it in k: resumed, the rest fails under the guard again — each time */
  @Test
  void aGuardCrossedByACaptureIsInK() {
    List<Integer> seen = new ArrayList<>();
    Comp<Integer, Top> p = Comp.handle(new Comp.Body<Top, Integer, Integer>() {
      public <M extends Top> Comp<Integer, M> in(Prompt<Top, M, Integer> pr) {
        Comp<Integer, M> op = pr.shift0(k -> k.apply(1).flatMap(a -> k.apply(2).map(b -> a + b)));
        return Comp.recover(op.map(x -> {
          seen.add(x);
          if (x == 1) throw new IllegalStateException("one");
          return x * 10;
        }), _ -> pure(100));
      }
    }, Comp::pure);
    assertEquals(120, Comp.run(p));
    assertEquals(List.of(1, 2), seen);
  }

  /** the first frame of a resumed rest — the operation's own map — failing: the guard that k crossed takes it */
  @Test
  void aResumedRestsFirstFrameFailsUnderTheGuardAgain() {
    Comp<Integer, Top> p = Comp.handle(new Comp.Body<Top, Integer, Integer>() {
      public <M extends Top> Comp<Integer, M> in(Prompt<Top, M, Integer> pr) {
        Comp<Integer, M> op = pr.shift0(k -> k.apply(7));
        return Comp.recover(op.map((Function<Integer, Integer>) x -> {
          throw new IllegalStateException("first");
        }), t -> pure(t.getMessage().length()));
      }
    }, Comp::pure);
    assertEquals(5, Comp.run(p));
  }

  /** a failure is not an error of the JVM: those are not caught */
  @Test
  void anErrorOfTheJvmIsNotAFailure() {
    assertThrows(StackOverflowError.class, () -> Comp.run(Comp.recover(Comp.<Top, Integer>pure(1).map(_ -> {
      throw new StackOverflowError();
    }), _ -> pure(0))));
  }

  @Test
  void manyFailuresInARowInConstantStack() {
    Comp<Integer, Top> p = pure(0);
    for (int i = 0; i < 100_000; i++) {
      Comp<Integer, Top> q = p;
      // the value so far, failed with, recovered one more: 100 000 guards in force at the bottom, on the heap
      p = Comp.recover(q.flatMap(n -> Comp.failed(new RuntimeException(String.valueOf(n)))),
          t -> pure(Integer.parseInt(t.getMessage()) + 1));
    }
    assertTrue(Comp.run(p) == 100_000);
  }
}
