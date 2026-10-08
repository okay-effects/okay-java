package okay.std;

import static okay.core.Eff.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import okay.core.Eff;
import okay.std.State.Final;
import okay.std.Throws.Result;
import org.junit.jupiter.api.Test;

/** okay-core's TestPStateAsync, the Async part; and the other handlers of okay-core's std: a cell state, throws */
class AsyncTest {

  /** an async run at the top, its failure the {@code Failed}'s message */
  static <A> Result<String, A> runIO(Function<Async, Eff<A>> body) {
    Result<Throwable, A> r = run(Throws.attempt(t -> Async.run(t, body)));
    return switch (r) {
      case Result.Ok<Throwable, A>(var a) -> new Result.Ok<>(a);
      case Result.Failed<Throwable, A>(var e) -> new Result.Failed<>(e.getMessage());
    };
  }

  @Test
  void computationsRunInOrderTheirValuesBound() {
    assertEquals(new Result.Ok<>(42), runIO(as -> as.async(() -> 20).flatMap(a -> as.async(() -> a + 1)).map(x -> x * 2)));
  }

  @Test
  void aCallbackFromAnotherThreadTheRunGoingOnInIt() {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Function<Async, Function<Integer, Eff<Integer>>> later = as -> x -> as.await(cb -> pool.execute(() -> {
        sleep();
        cb.accept(x, null);
      }));
      assertEquals(new Result.Ok<>(6), runIO(as -> later.apply(as).apply(1)
          .flatMap(a -> later.apply(as).apply(2).map(b -> a + b))
          .flatMap(a -> later.apply(as).apply(3).map(b -> a + b))));
    } finally {
      pool.shutdown();
    }
  }

  private static void sleep() {
    try {
      Thread.sleep(1);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  static Eff<Integer> loop(Async as, int i, int acc) {
    return i == 0 ? Eff.pure(acc) : as.<Integer>await(cb -> cb.accept(1, null)).flatMap(x -> loop(as, i - 1, acc + x));
  }

  @Test
  void callbacksAnsweredAtOnceInConstantStack() {
    assertEquals(new Result.Ok<>(100_000), runIO(as -> loop(as, 100_000, 0)));
  }

  @Test
  void aFailureAtACallbackOrInAComputationTheRestNeverRuns() {
    boolean[] ran = {false};
    Result<String, Integer> e1 = runIO(as -> as.<Integer>await(cb -> cb.accept(null, new IllegalStateException("cb")))
        .map(x -> { ran[0] = true; return x; }));
    Result<String, Integer> e2 = runIO(as -> as.async(() -> Integer.parseInt("x")).map(x -> { ran[0] = true; return x; }));
    assertEquals(new Result.Failed<>("cb"), e1);
    assertEquals(new Result.Failed<>("For input string: \"x\""), e2);
    assertFalse(ran[0]);
  }

  @Test
  void theProgramsOwnRaiseFailsTheRun() {
    assertEquals(new Result.Failed<>("own"),
        runIO(as -> as.async(() -> 1).flatMap(_ -> as.<Integer>raise(new IllegalStateException("own")))));
  }

  @Test
  void aStateInsideGoesOnThroughTheCallbacks() {
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Result<String, Final<Integer, Integer>> r = runIO(as -> State.<Integer, Integer>state(0, st ->
          as.<Integer>await(cb -> pool.execute(() -> cb.accept(5, null))).flatMap(x -> st.modify(s -> s + x))
              .then(as.<Integer>await(cb -> pool.execute(() -> cb.accept(7, null)))).flatMap(y -> st.modify(s -> s + y))
              .then(st.get())));
      assertEquals(new Result.Ok<>(new Final<>(12, 12)), r);
    } finally {
      pool.shutdown();
    }
  }

  @Test
  void aReaderOutsideTheAsyncRun() {
    assertEquals(new Result.Ok<>(42), run(Reader.<Integer, Result<String, Integer>>reader(41, r ->
        Eff.pure(runIO(as -> r.ask().flatMap(a -> as.async(() -> a + 1)))))));
  }

  @Test
  void aCompletionStageAwaited() {
    assertEquals(new Result.Ok<>("x!"), runIO(as -> as.await(java.util.concurrent.CompletableFuture.supplyAsync(() -> "x"))
        .map(s -> s + "!")));
  }

  @Test
  void aCellStateAnsweredInPlaceSharedByTheResumptions() {
    // okay-core's state: one cell per run, a body resumed twice sees the first resumption's last state
    assertEquals(new Final<>(3, List.of(1, 3)), run(State.<Integer, List<Integer>>cell(0, st -> Choose.all(ch ->
        ch.among(List.of(1, 2)).flatMap(x -> st.modify(s -> s + x)).then(st.get())))));
    // choose outside too: its k resumes the body in the same run of cell — the second path sees the first's 1
    assertEquals(List.of(new Final<>(1, 1), new Final<>(3, 3)), run(Choose.<Final<Integer, Integer>>all(ch ->
        State.cell(0, st -> ch.among(List.of(1, 2)).flatMap(x -> st.modify(s -> s + x)).then(st.get())))));
    assertEquals(new Final<>(100_000, 100_000), run(State.<Integer, Integer>cell(0, st -> count(st, 100_000))));
  }

  static Eff<Integer> count(State<Integer> st, int i) {
    return i == 0 ? st.get() : st.modify(s -> s + 1).then(Eff.delay(() -> count(st, i - 1)));
  }

  @Test
  void throwsTheValueOrTheFirstRaise() {
    assertEquals(new Result.Ok<>(1), run(Throws.<String, Integer>attempt(_ -> Eff.pure(1))));
    assertEquals(new Result.Failed<>("a"), run(Throws.<String, Integer>attempt(t -> t.<Integer>raise("a").then(t.raise("b")))));
  }
}
