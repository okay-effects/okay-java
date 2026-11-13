package okay.resumable;

import static okay.rows.Effects.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import okay.rows.Comp;
import okay.rows.Eff;
import okay.rows.Pure;
import okay.rows.std.State.Final;
import okay.rows.std.Throws;
import okay.rows.std.Writer;
import org.junit.jupiter.api.Test;

/** The reference properties of {@link DirectTest}, their handlers written in direct style ({@link DirectHandlers}),
 * alone and mixed with okay.rows' handlers in both orders */
class DirectHandlersTest {

  @Test
  void aRowHandlersTakeTheirEffectsOneByOne() {
    Writer.Logged<String, Final<Integer, Integer>> logged =
        new Writer.Logged<>(List.of("was 1"), new Final<>(2, 2));
    // all three in direct style
    assertEquals(new Throws.Ok<>(logged), run(DirectHandlers.failing(DirectHandlers.logged(
        DirectHandlers.counted(1, Direct.row(Counter.class, Log.class, Fail.class).of(DirectTest::logged))))));
    // mixed: okay.rows' in the middle, then outside and in
    assertEquals(new Throws.Ok<>(logged), run(DirectHandlers.failing(Log.run(
        DirectHandlers.general(1, Direct.row(Counter.class, Log.class, Fail.class).of(DirectTest::logged))))));
    assertEquals(new Throws.Ok<>(logged), run(Fail.run(DirectHandlers.logged(
        Counter.run(1, Direct.row(Counter.class, Log.class, Fail.class).of(DirectTest::logged))))));
  }

  @Test
  void theOrderOfHandlersIsTheMeaning() {
    assertEquals(new Final<>(5, new Throws.Err<>("no")), run(Counter.run(0,
        DirectHandlers.failing(Direct.row(Fail.class, Counter.class).of(DirectTest::putThenFail)))));
    assertEquals(new Throws.Err<>("no"), run(DirectHandlers.failing(
        Counter.run(0, Direct.row(Counter.class, Fail.class).of(DirectTest::putThenFail)))));
    assertEquals(new Final<>(5, new Throws.Err<>("no")), run(DirectHandlers.general(0,
        Fail.run(Direct.row(Fail.class, Counter.class).of(DirectTest::putThenFail)))));
  }

  @Test
  void everyPathOfAChoiceAndTheFirst() {
    List<DirectTest.Pair> all = List.of(new DirectTest.Pair(1, 10), new DirectTest.Pair(1, 20),
        new DirectTest.Pair(2, 10), new DirectTest.Pair(2, 20), new DirectTest.Pair(3, 10), new DirectTest.Pair(3, 20));
    assertEquals(all, run(DirectHandlers.all(Direct.row(Pick.class).of(DirectTest::pairs))));
    assertEquals(Optional.of(new DirectTest.Pair(1, 10)),
        run(DirectHandlers.first(Direct.row(Pick.class).of(DirectTest::pairs))));
    // the first path that comes to a value: a choice of none ends a path, unwound
    assertEquals(Optional.of(3), run(DirectHandlers.first(Direct.row(Pick.class).of(() -> {
      int x = Pick.among(1, 2, 3);
      if (x < 3) Pick.among();
      return x;
    }))));
  }

  @Test
  void manyTimesWithStateEachPathItsOwnOrOneThroughAll() {
    // a direct-style choice, okay.rows' state in place inside it and outside it
    assertEquals(List.of(new Final<>(1, 1), new Final<>(2, 2)), run(DirectHandlers.all(
        Counter.run(0, Direct.row(Counter.class, Pick.class).of(DirectTest::chosenThenAdded)))));
    assertEquals(new Final<>(3, List.of(1, 3)), run(Counter.run(0,
        DirectHandlers.all(Direct.row(Pick.class, Counter.class).of(DirectTest::chosenThenAdded)))));
    // okay.rows' choice, the direct-style state in place inside it and outside it
    assertEquals(List.of(new Final<>(1, 1), new Final<>(2, 2)), run(Pick.all(
        DirectHandlers.counted(0, Direct.row(Counter.class, Pick.class).of(DirectTest::chosenThenAdded)))));
    assertEquals(new Final<>(3, List.of(1, 3)), run(DirectHandlers.counted(0,
        Pick.all(Direct.row(Pick.class, Counter.class).of(DirectTest::chosenThenAdded)))));
  }

  static String toldTwice(int[] calls) throws Log, Pick {
    calls[0]++;
    Log.tell("a");
    int x = Pick.among(1, 2);
    Log.tell("b" + x);
    return "x" + x;
  }

  @Test
  void multiShotThroughADirectStyleClauseNoReplay() {
    List<Writer.Logged<String, String>> paths = List.of(new Writer.Logged<>(List.of("a", "b1"), "x1"),
        new Writer.Logged<>(List.of("a", "b2"), "x2"));
    // the log's clause for "a" waits for the rest, which the choice outside resumes twice: the clause's frames are
    // resumed once for each path, its locals copied — nothing replayed: the body is entered once
    int[] calls = {0};
    assertEquals(paths, run(Pick.all(DirectHandlers.logged(
        Direct.row(Log.class, Pick.class).of(() -> toldTwice(calls))))));
    assertEquals(1, calls[0]);
    // the other way round: the choice in direct style, okay.rows' log in place inside it
    assertEquals(paths, run(DirectHandlers.all(Log.run(
        Direct.row(Log.class, Pick.class).of(() -> toldTwice(calls))))));
    assertEquals(2, calls[0]);
  }

  @Test
  void finallyBlocksRunWhenADirectStyleClauseUnwindsTheRest() {
    List<String> log = new ArrayList<>();
    assertEquals(List.of(1), run(DirectHandlers.all(Direct.row(Pick.class).of(() -> DirectTest.guardedPath(log)))));
    assertEquals(List.of("finally", "finally"), log);
    List<String> nested = new ArrayList<>();
    assertEquals(new Throws.Err<>("no"),
        run(DirectHandlers.failing(Direct.row(Fail.class).of(() -> DirectTest.outerFinally(nested)))));
    assertEquals(List.of("body", "inner", "outer"), nested);
  }

  @Test
  void anInterceptorWhoseClausePerformsTheEffectsOutsideIt() {
    // it tells the log, outside it, and asks the counter's handler, outside it, again
    assertEquals(new Writer.Logged<>(List.of("saw Get", "saw Put(3)"), new Final<>(3, 3)),
        run(Log.run(Counter.run(0, DirectHandlers.told(Direct.row(Counter.class, Log.class).of(() ->
            DirectTest.add(3)))))));
    // the same with direct-style handlers outside it
    assertEquals(new Writer.Logged<>(List.of("saw Get", "saw Put(3)"), new Final<>(3, 3)),
        run(DirectHandlers.logged(DirectHandlers.counted(0, DirectHandlers.told(
            Direct.row(Counter.class, Log.class).of(() -> DirectTest.add(3)))))));
  }

  @Test
  void anInPlaceHandlerInDirectStyleAnswersOnTheBodysStack() {
    assertEquals(List.of(7, 0, 0, 0), TiersTest.taken(() -> assertEquals(new Final<>(3, 3),
        run(DirectHandlers.counted(0, Direct.row(Counter.class).of(() -> DirectTest.count(3)))))));
  }

  static int depthAt(int n, List<Integer> depths) throws Counter {
    for (int i = 0; i < n; i++) {
      Counter.put(Counter.get() + 1);
      if (i % 100_000 == 0) depths.add(StackWalker.getInstance().walk(s -> (int) s.count()));
    }
    return Counter.get();
  }

  @Test
  void constantStackAMillionOperationsResumedInTailPosition() {
    List<Integer> depths = new ArrayList<>();
    long t0 = System.nanoTime();
    assertEquals(new Final<>(1_000_000, 1_000_000),
        run(DirectHandlers.general(0, Direct.row(Counter.class).of(() -> depthAt(1_000_000, depths)))));
    long ns = (System.nanoTime() - t0) / 2_000_001;
    System.out.println("a direct-style general handler, resumed in tail position: about " + ns + " ns an operation");
    // every operation runs the clause; neither stack grows: the Java stack the same at each
    assertConstant(depths);
  }

  static void assertConstant(List<Integer> depths) {
    assertEquals(10, depths.size());
    assertEquals(1, depths.stream().distinct().count(), "depths " + depths);
    assertTrue(depths.getFirst() < 100, "depth " + depths.getFirst());
  }

  interface Shape {
    Comp<Final<Integer, Integer>, Pure> handled(Comp<Integer, Eff<Counter, Pure>> p);
  }

  @Test
  void tailPositionThroughALocalACastATernaryASwitchsLocal() {
    Map<String, Shape> shapes = new LinkedHashMap<>();
    shapes.put("a local", p -> DirectHandlers.generalThroughALocal(0, p));
    shapes.put("a cast of a local, a ternary", p -> DirectHandlers.generalThroughACastAndATernary(0, p));
    shapes.put("a switch's local", p -> DirectHandlers.generalThroughASwitchsLocal(0, p));
    shapes.forEach((name, shape) -> {
      List<Integer> depths = new ArrayList<>();
      int tails = Driver.TAILS;
      // a million operations each, under -Xss512k: nothing kept for each
      assertEquals(new Final<>(1_000_000, 1_000_000),
          run(shape.handled(Direct.row(Counter.class).of(() -> depthAt(1_000_000, depths)))), name);
      assertConstant(depths);
      assertEquals(2_000_001, Driver.TAILS - tails, name);
    });
    // an int unboxed into a local and boxed again
    List<Integer> depths = new ArrayList<>();
    int tails = Driver.TAILS;
    assertEquals(1_000_000, run(DirectHandlers.counting(Direct.row(Counter.class).of(() ->
        depthAt(1_000_000, depths)))));
    assertConstant(depths);
    assertEquals(2_000_001, Driver.TAILS - tails);
  }

  @Test
  void aLocalReadForAnythingElseIsNotTailPosition() {
    // the value is looked at after the rest has run: correct, each clause's frame kept till then
    List<Object> seen = new ArrayList<>();
    int tails = Driver.TAILS;
    assertEquals(new Final<>(3, 3), run(DirectHandlers.generalLookingAtTheLocal(0, seen,
        Direct.row(Counter.class).of(() -> DirectTest.count(3)))));
    assertEquals(7, seen.size());
    assertTrue(seen.stream().allMatch(new Final<>(3, 3)::equals), seen.toString());
    assertEquals(0, Driver.TAILS - tails);
  }
}
