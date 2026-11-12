package okay.resumable;

import static okay.rows.Effects.intercept;
import static okay.rows.Effects.observe;
import static okay.rows.Effects.perform;
import static okay.rows.Effects.pure;
import static okay.rows.Effects.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import okay.rows.Comp;
import okay.rows.Eff;
import okay.rows.Handler;
import okay.rows.K;
import okay.rows.Pure;
import okay.rows.std.State.Final;
import okay.rows.std.Throws;
import okay.rows.std.Writer;
import org.junit.jupiter.api.Test;

/** okay.rows' reference properties that make sense in direct style, with the same expected outcomes as
 * {@code okay.rows.Properties}: the programs are plain Java methods; the handlers are okay.rows' */
class DirectTest {

  // ---- the programs

  static int logged() throws Counter, Log, Fail {
    int s = Counter.get();
    Log.tell("was " + s);
    Counter.put(s + 1);
    return s + 1;
  }

  static int putThenFail() throws Counter, Fail {
    Counter.put(5);
    return Fail.raise("no");
  }

  static int count(int n) throws Counter {
    for (int i = 0; i < n; i++) Counter.put(Counter.get() + 1);
    return Counter.get();
  }

  record Pair(int x, int y) {}

  static Pair pairs() throws Pick {
    int x = Pick.among(1, 2, 3);
    return new Pair(x, Pick.among(10, 20));
  }

  static int add(int x) throws Counter {
    int s = Counter.get();
    Counter.put(s + x);
    return s + x;
  }

  static int chosenThenAdded() throws Counter, Pick {
    return add(Pick.among(1, 2));
  }

  // ---- the properties

  @Test
  void aRowHandlersTakeTheirEffectsOneByOne() {
    var got = run(Fail.run(Log.run(Counter.run(1,
        Direct.row(Counter.class, Log.class, Fail.class).of(DirectTest::logged)))));
    assertEquals(new Throws.Ok<>(new Writer.Logged<>(List.of("was 1"), new Final<>(2, 2))), got);
  }

  @Test
  void theOrderOfHandlersIsTheMeaning() {
    assertEquals(new Final<>(5, new Throws.Err<>("no")),
        run(Counter.run(0, Fail.run(Direct.row(Fail.class, Counter.class).of(DirectTest::putThenFail)))));
    assertEquals(new Throws.Err<>("no"),
        run(Fail.run(Counter.run(0, Direct.row(Counter.class, Fail.class).of(DirectTest::putThenFail)))));
  }

  @Test
  void constantStackAMillionOperations() {
    assertEquals(new Final<>(1_000_000, 1_000_000),
        run(Counter.run(0, Direct.row(Counter.class).of(() -> count(1_000_000)))));
    // the same, every operation seen by a general handler on its way: the frames saved and resumed each time
    assertEquals(new Final<>(1_000_000, 1_000_000),
        run(Counter.run(0, Counter.watched(Direct.row(Counter.class).of(() -> count(1_000_000))))));
  }

  @Test
  void capturesOnManyThreadsAtOnceEachTakesItsOwn() throws Exception {
    // the frames saved at every operation, on four threads at once: a capture leaves the twins by returns, and the
    // count each thread reads is everyone's — each must take its own capture only
    try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
      List<Future<Final<Integer, Integer>>> runs = new ArrayList<>();
      for (int i = 0; i < 8; i++) {
        int n = 20_000 + i;
        runs.add(pool.submit(() -> run(Counter.run(0, Counter.watched(Direct.row(Counter.class).of(() -> count(n)))))));
      }
      for (int i = 0; i < 8; i++) assertEquals(new Final<>(20_000 + i, 20_000 + i), runs.get(i).get());
    }
  }

  @Test
  void aContinuationTakenManyTimesEveryPathOfAChoice() {
    assertEquals(List.of(new Pair(1, 10), new Pair(1, 20), new Pair(2, 10), new Pair(2, 20), new Pair(3, 10),
        new Pair(3, 20)), run(Pick.all(Direct.row(Pick.class).of(DirectTest::pairs))));
  }

  @Test
  void manyTimesWithStateEachPathItsOwnOrOneThroughAll() {
    assertEquals(List.of(new Final<>(1, 1), new Final<>(2, 2)),
        run(Pick.all(Counter.run(0, Direct.row(Counter.class, Pick.class).of(DirectTest::chosenThenAdded)))));
    assertEquals(new Final<>(3, List.of(1, 3)),
        run(Counter.run(0, Pick.all(Direct.row(Pick.class, Counter.class).of(DirectTest::chosenThenAdded)))));
  }

  // ---- no replay

  static int countedBeforeAChoice(int[] before, int[] after) throws Pick {
    before[0]++;
    int x = Pick.among(1, 2, 3, 4, 5);
    after[0]++;
    return x;
  }

  @Test
  void noReplayACounterBeforeAChoiceCountsOnceHoweverManyPaths() {
    int[] before = {0};
    int[] after = {0};
    assertEquals(List.of(1, 2, 3, 4, 5),
        run(Pick.all(Direct.row(Pick.class).of(() -> countedBeforeAChoice(before, after)))));
    assertEquals(1, before[0]);
    assertEquals(5, after[0]);
  }

  static List<Integer> localsPerPath(int[] calls) throws Pick {
    calls[0]++;
    List<Integer> seen = new ArrayList<>(); // an object: shared by every path that resumes this frame
    int sum = 100; // a local: copied into each resumption
    int x = Pick.among(1, 2);
    sum += x;
    seen.add(sum);
    int y = Pick.among(10, 20);
    sum += y;
    seen.add(sum);
    return List.copyOf(seen);
  }

  @Test
  void multiShotCopiesLocalsAndSharesTheObjectsTheyReferTo() {
    int[] calls = {0};
    List<List<Integer>> paths = run(Pick.all(Direct.row(Pick.class).of(() -> localsPerPath(calls))));
    assertEquals(1, calls[0]);
    // the sums are per path; the list is one, seen growing along every path in turn
    assertEquals(List.of(
        List.of(101, 111),
        List.of(101, 111, 121),
        List.of(101, 111, 121, 102, 112),
        List.of(101, 111, 121, 102, 112, 122)), paths);
  }

  // ---- the stack

  static int deep(int n, List<Integer> depths) throws Counter {
    if (n == 0) return Counter.get();
    int r = deep(n - 1, depths) + 1;
    Counter.put(r);
    depths.add(StackWalker.getInstance().walk(s -> (int) s.count()));
    return r;
  }

  @Test
  void deepRecursionResumedOneFrameAtATime() {
    List<Integer> depths = new ArrayList<>();
    int n = 1_000; // the first descent is plain Java recursion, under the tests' -Xss512k
    // watched: every operation goes by a general handler, so the frames are saved at each
    assertEquals(new Final<>(n, n),
        run(Counter.run(0, Counter.watched(Direct.row(Counter.class).of(() -> deep(n, depths))))));
    // every level runs after a resumption: the driver re-enters one saved frame at a time, so each sees the
    // same, small Java stack — not the n frames of the recursion
    assertEquals(n, depths.size());
    assertEquals(1, depths.stream().distinct().count(), "depths " + depths.stream().distinct().toList());
    assertTrue(depths.getFirst() < 100, "depth " + depths.getFirst());
  }

  // ---- unwinding

  static int guardedPath(List<String> log) throws Pick {
    try {
      int x = Pick.among(1, 2);
      if (x == 2) Pick.among(); // no value: the path ends, unwound
      return x;
    } finally {
      log.add("finally");
    }
  }

  static int outerFinally(List<String> log) throws Fail {
    try {
      return innerFinally(log);
    } finally {
      log.add("outer");
    }
  }

  static int innerFinally(List<String> log) throws Fail {
    try {
      log.add("body");
      return Fail.raise("no");
    } finally {
      log.add("inner");
    }
  }

  @Test
  void finallyBlocksRunWhenTheRestIsUnwound() {
    List<String> log = new ArrayList<>();
    assertEquals(List.of(1), run(Pick.all(Direct.row(Pick.class).of(() -> guardedPath(log)))));
    assertEquals(List.of("finally", "finally"), log);

    List<String> nested = new ArrayList<>();
    assertEquals(new Throws.Err<>("no"), run(Fail.run(Direct.row(Fail.class).of(() -> outerFinally(nested)))));
    assertEquals(List.of("body", "inner", "outer"), nested);
  }

  @Test
  void ensuringOfOkayRowsAroundADirectProgram() {
    List<String> log = new ArrayList<>();
    Comp<Integer, Eff<Fail, Pure>> p = Direct.row(Fail.class).of(() -> Fail.<Integer>raise("no"));
    assertEquals(new Throws.Err<>("no"), run(Fail.run(p.ensuring(() -> {
      log.add("ensured");
      return pure(null);
    }))));
    assertEquals(List.of("ensured"), log);
  }

  static int inner() throws Counter {
    if (Counter.get() > 0) throw new IllegalStateException("positive");
    return 0;
  }

  static int caught() throws Counter {
    try {
      return inner();
    } catch (IllegalStateException e) {
      return -1;
    }
  }

  @Test
  void anExceptionAfterAResumptionReachesTheCatchOfTheFrameAbove() {
    assertEquals(new Final<>(1, -1), run(Counter.run(1, Direct.row(Counter.class).of(DirectTest::caught))));
    assertEquals(new Final<>(0, 0), run(Counter.run(0, Direct.row(Counter.class).of(DirectTest::caught))));
  }

  // ---- interception and observation by okay.rows' handlers

  static <A, R extends Eff<Counter, ?>> Comp<A, R> capped(List<String> seen, Comp<A, ? super R> p) {
    return intercept(Counter.class, new Handler<Counter, A, A, R>() {
      public Comp<A, R> ret(A a) { return pure(a); }
      public Comp<A, R> on(Counter op, K<A, R> k) {
        seen.add(op.toString());
        return switch (op) {
          case Counter.Put put when put.value > 2 -> k.after(put, perform(new Counter.Put(2)));
          case Counter.Put put -> k.after(put, perform(put));
          case Counter.Get get -> k.after(get, perform(get));
        };
      }
    }, p);
  }

  @Test
  void interceptionAnOperationSeenChangedAndAskedAgain() {
    List<String> seen = new ArrayList<>();
    assertEquals(new Final<>(2, 2), run(Counter.run(0, capped(seen, Direct.row(Counter.class).of(() -> count(5))))));
    assertEquals(5L, seen.stream().filter(s -> s.startsWith("Put")).count());
  }

  @Test
  void observedEachOperationOfAnEffectShownAndGoneOn() {
    List<String> seen = new ArrayList<>();
    var got = run(Counter.run(0, observe(Counter.class, op -> {
      seen.add(op.toString());
      return pure(null);
    }, Direct.row(Counter.class).of(() -> add(3)))));
    assertEquals(new Final<>(3, 3), got);
    assertEquals(List.of("Get", "Put(3)"), seen);
  }
}
