package okay.rows;

import static okay.rows.Effects.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import okay.core.NestedRun;
import okay.rows.std.Async;
import okay.rows.std.Emit;
import okay.rows.std.State;
import org.junit.jupiter.api.Test;

/** A run begun inside a running program, on the same thread, refused at once ({@code NestedRun}); the escape, a
 * handler installed inside a continuation, and runs begun elsewhere — another thread, a virtual one, a callback —
 * go on. The tests run with -Xss512k: a nested run at each step would overflow there at n = 1000 */
class NestedRunTest {
  /** a loop whose every continuation runs the rest of the loop as a run of its own */
  static Comp<Integer, Pure> nested(int i, int[] steps) {
    if (i == 0) return pure(0);
    return Effects.<Integer, Pure>pure(1).flatMap(x -> {
      steps[0]++;
      return pure(x + run(nested(i - 1, steps)));
    });
  }

  @Test
  void aRunInsideAContinuationAtEachStepIsRefusedAtTheFirst() {
    int[] steps = {0};
    var e = assertThrows(NestedRun.class, () -> run(nested(1000, steps)));
    assertEquals(1, steps[0]);
    assertTrue(e.getMessage().contains("runIsolated"), e.getMessage());
    assertTrue(e.getMessage().contains("State.run(s0, p)"), e.getMessage());
    // the mark is cleared when the run is left by a throw: the thread runs again
    int again = run(Effects.<Integer, Pure>pure(1).map(x -> x + 1));
    assertEquals(2, again);
  }

  @Test
  void stepAndTheCoreRunnerAreRefusedTheSame() {
    assertThrows(NestedRun.class, () -> run(Effects.<Integer, Pure>pure(1).flatMap(x -> {
      Effects.step(Effects.<Integer, Pure>pure(x));
      return pure(x);
    })));
    assertThrows(NestedRun.class, () -> okay.core.Eff.run(okay.core.Eff.<okay.core.Eff.Top, Integer>pure(1).flatMap(x ->
        okay.core.Eff.pure(okay.core.Eff.run(okay.core.Eff.<okay.core.Eff.Top, Integer>pure(x))))));
  }

  @Test
  void aRunInsideAHandlersClauseIsRefused() {
    Comp<Integer, Pure> clause = handle(State.class, new Handler<State<Integer>, Integer, Integer, Pure>() {
      public Comp<Integer, Pure> ret(Integer a) { return pure(a); }
      public Comp<Integer, Pure> on(State<Integer> op, K<Integer, Pure> k) {
        return switch (op) {
          case State.Get<Integer> get -> k.resume(get, run(pure(41)));
          case State.Put<Integer> put -> k.resume(put, null);
        };
      }
    }, State.<Integer>get());
    assertThrows(NestedRun.class, () -> run(clause));
  }

  @Test
  void theEscapeAClosedSubProgramRunToItsEndOnPurpose() {
    Comp<Integer, Pure> p = Effects.<Integer, Pure>pure(1).flatMap(x ->
        pure(x + runIsolated(State.run(x, Effects.<Integer, Eff<State<Integer>, Pure>>fit(State.get()))).state()));
    assertEquals(2, run(p));
    // inside the isolated run, a plain run is refused again; after it, the outer run goes on
    assertThrows(NestedRun.class, () -> run(Effects.<Integer, Pure>pure(1).flatMap(x ->
        pure(runIsolated(Effects.<Integer, Pure>pure(x).map(y -> y + run(pure(1))))))));
    int after = run(Effects.<Integer, Pure>pure(1).flatMap(x -> Effects.<Integer, Pure>pure(runIsolated(pure(x))))
        .map(y -> y + 2));
    assertEquals(3, after);
    assertEquals(1, (int) okay.core.Eff.run(okay.core.Eff.<okay.core.Eff.Top, Integer>pure(1).flatMap(x ->
        okay.core.Eff.pure(okay.core.Eff.runIsolated(okay.core.Eff.<okay.core.Eff.Top, Integer>pure(x))))));
  }

  static Comp<Void, Eff<State<Integer>, Pure>> counting(int i) {
    if (i == 0) return pure(null);
    return Effects.<Integer, Eff<State<Integer>, Pure>>fit(State.get())
        .flatMap(s -> Effects.<Void, Eff<State<Integer>, Pure>>fit(State.put(s + 1)))
        .flatMap(done -> counting(i - 1));
  }

  static Comp<Integer, Pure> each(int i, int acc) {
    if (i == 0) return pure(acc);
    return State.run(0, counting(3)).flatMap(f -> each(i - 1, acc + f.state()));
  }

  @Test
  void aHandlerInstalledInsideAContinuationAtEachStepAsAPartOfTheProgram() {
    assertEquals(300_000, run(each(100_000, 0)));
  }

  /** {@code body} on a thread {@code start} makes, joined: its value, or what it threw */
  static int on(java.util.function.Function<Runnable, Thread> start, Supplier<Integer> body) throws Exception {
    AtomicReference<Object> got = new AtomicReference<>();
    start.apply(() -> {
      try {
        got.set(body.get());
      } catch (Throwable t) {
        got.set(t);
      }
    }).join();
    if (got.get() instanceof RuntimeException e) throw e;
    return (Integer) got.get();
  }

  @Test
  void aRunOnAnotherThreadBegunByAStepIsNotRefused() {
    assertEquals(42, (int) run(Effects.<Integer, Pure>pure(1).map(x -> {
      try {
        return x + on(r -> Thread.ofPlatform().start(r), () -> run(pure(41)));
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    })));
    assertEquals(42, (int) run(Effects.<Integer, Pure>pure(1).map(x -> {
      try {
        return x + on(r -> Thread.ofVirtual().start(r), () -> run(pure(41)));
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    })));
    // a nested run on the virtual thread's own stack: refused there as anywhere
    assertThrows(NestedRun.class, () -> on(r -> Thread.ofVirtual().start(r),
        () -> run(Effects.<Integer, Pure>pure(1).map(x -> x + run(pure(1))))));
  }

  static Comp<Integer, Eff<Async, Pure>> waits(int i, ExecutorService pool) {
    if (i == 0) return pure(0);
    Comp<Integer, Eff<Async, Pure>> one = fit(Async.<Integer>await(cb -> pool.execute(() -> cb.accept(1))));
    return one.flatMap(x -> waits(i - 1, pool).map(y -> x + y));
  }

  @Test
  void aProgramResumedByCallbacksOnOtherThreadsAndOnThisOneInsideARun() throws Exception {
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      CompletableFuture<Integer> done = new CompletableFuture<>();
      Async.start(waits(1000, pool), done::complete);
      assertEquals(1000, done.get(10, TimeUnit.SECONDS));
    }
    // the callback of a wait called by a step of another run on this thread: the waiting program goes on there
    AtomicReference<Consumer<Integer>> callback = new AtomicReference<>();
    CompletableFuture<Integer> waited = new CompletableFuture<>();
    Async.start(Effects.<Integer, Eff<Async, Pure>>fit(Async.<Integer>await(callback::set)).map(x -> x + 1),
        waited::complete);
    run(defer(() -> {
      callback.get().accept(41);
      return Effects.<Void, Pure>pure(null);
    }));
    assertEquals(42, waited.getNow(-1));
    // a program started by a step (both: each started inside the wait's registration)
    CompletableFuture<Integer> both = new CompletableFuture<>();
    Async.start(Async.both(Effects.<Integer, Eff<Async, Pure>>pure(1), Effects.<Integer, Eff<Async, Pure>>pure(2))
        .map(pair -> pair.first() + pair.second()), both::complete);
    assertEquals(3, both.getNow(-1));
    // waiting for a run inside a step: refused
    assertThrows(NestedRun.class, () -> run(Effects.<Integer, Pure>defer(() ->
        pure(Async.runBlocking(Effects.<Integer, Eff<Async, Pure>>pure(1))))));
    assertEquals(1, (int) Async.runBlocking(Effects.<Integer, Eff<Async, Pure>>pure(1)));
  }

  @Test
  void aStreamPulledInsideAProgramItsPullsRunsOfTheirOwn() {
    Comp<Void, Eff<Emit<Integer>, Pure>> three = fit(Emit.send(1).flatMap(x -> Emit.send(2))
        .flatMap(x -> Emit.send(3)));
    assertEquals(List.of(1, 2, 3), run(Effects.<Void, Pure>pure(null).map(v -> {
      List<Integer> got = new java.util.ArrayList<>();
      Emit.pull(three).forEachRemaining(got::add);
      return got;
    })));
  }
}
