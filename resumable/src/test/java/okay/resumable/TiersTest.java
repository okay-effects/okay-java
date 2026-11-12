package okay.resumable;

import static okay.rows.Effects.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.IntStream;
import okay.rows.std.State.Final;
import okay.rows.std.Throws;
import org.junit.jupiter.api.Test;

/** Which of the three ways an operation takes, by its handler */
class TiersTest {
  static int depth() {
    return StackWalker.getInstance().walk(s -> (int) s.count());
  }

  static List<Integer> depthsAround() throws Counter {
    int before = depth();
    Counter.put(Counter.get() + 1);
    return List.of(before, depth());
  }

  /** how many operations {@code r} sent each way: in place, a clause on the body's stack, yielded, frames saved */
  static List<Integer> taken(Runnable r) {
    int[] before = Driver.TAKEN.clone();
    r.run();
    return IntStream.range(0, 4).mapToObj(i -> Driver.TAKEN[i] - before[i]).toList();
  }

  @Test
  void eachOperationTakesTheWayItsHandlerAllows() {
    assertTrue(Driver.TRACE, "the tests run with -Dokay.resumable.trace=true");
    // in place: answered on the body's stack, by the handler's cell
    assertEquals(List.of(2, 0, 0, 0), taken(() ->
        run(Counter.run(0, Direct.row(Counter.class).of(TiersTest::depthsAround)))));
    // general, declared Once, its clause resuming at once: the clause called from the body's stack, nothing yields
    assertEquals(List.of(0, 3, 0, 0), taken(() ->
        assertEquals(6, run(Input.once(ints(), Direct.row(Input.class).of(TiersTest::sumOfThree))))));
    // declared Once, its clause resuming later: the body yields on the JVM's continuation
    assertEquals(List.of(0, 0, 3, 0), taken(() ->
        assertEquals(6, run(Input.deferred(ints(), Direct.row(Input.class).of(TiersTest::sumOfThree))))));
    // not declared Once: the frames are saved
    assertEquals(List.of(0, 0, 0, 3), taken(() ->
        assertEquals(6, run(Input.many(ints(), Direct.row(Input.class).of(TiersTest::sumOfThree))))));
  }

  @Test
  void inPlaceAnsweredOnTheBodysOwnStack() {
    // no suspension: the body goes on on the same Java stack, at the same depth
    List<Integer> d = run(Counter.run(0, Direct.row(Counter.class).of(TiersTest::depthsAround))).value();
    assertEquals(d.get(0), d.get(1));
    // a general handler on the way (an observer): the frames saved and resumed by the boundary, at another depth
    List<Integer> w = run(Counter.run(0, Counter.watched(Direct.row(Counter.class).of(TiersTest::depthsAround))))
        .value();
    assertTrue(!w.get(0).equals(w.get(1)), w.toString());
  }

  @Test
  void anUntransformedFrameIsHarmlessWhereNothingSuspends() {
    // answered in place: nothing is saved, so a frame that was not transformed loses nothing
    assertEquals(new Final<>(0, 2),
        run(Counter.run(0, Direct.row(Counter.class).of(() -> Untransformed.middle() * 2))));
  }

  static int sumOfThree() throws Input {
    return Input.next() + Input.next() + Input.next();
  }

  static Iterator<Integer> ints() {
    return IntStream.iterate(1, i -> i + 1).iterator();
  }

  @Test
  void generalOnceOnTheJvmsContinuation() {
    assertEquals("the JVM's continuations", Direct.continuations());
    assertEquals(6, run(Input.once(ints(), Direct.row(Input.class).of(TiersTest::sumOfThree))));
    // the JVM freezes every frame, transformed or not: a frame that would be lost to saved frames is not
    assertEquals(2, run(Input.once(ints(), Direct.row(Input.class).of(() -> Untransformed.input() * 2))));
    assertEquals(2, run(Input.deferred(ints(), Direct.row(Input.class).of(() -> Untransformed.input() * 2))));
    // the same body, its handler not declared Once: saved frames
    assertEquals(6, run(Input.many(ints(), Direct.row(Input.class).of(TiersTest::sumOfThree))));
  }

  static List<Integer> deep(int n, List<Integer> depths) throws Input {
    if (n == 0) return List.of(Input.next());
    List<Integer> r = deep(n - 1, depths);
    depths.add(depth());
    return r;
  }

  @Test
  void onTheJvmsContinuationTheWholeStackComesBack() {
    List<Integer> depths = new ArrayList<>();
    assertEquals(List.of(0, 0, 1, 0), taken(() ->
        run(Input.deferred(ints(), Direct.row(Input.class).of(() -> deep(200, depths))))));
    // thawed by the JVM: the recursion's frames are all there again, each level one deeper than the next
    assertEquals(200, depths.size());
    assertEquals(199, depths.getFirst() - depths.getLast());
  }

  @Test
  void aYieldedBodyLeftUnansweredHasItsFinallyBlocksRun() {
    List<String> log = new ArrayList<>();
    assertEquals(List.of(0, 0, 1, 0), taken(() -> assertEquals(new Throws.Err<>("no"),
        run(Fail.runOnce(Direct.row(Fail.class).of(() -> DirectTest.outerFinally(log)))))));
    assertEquals(List.of("body", "inner", "outer"), log);
  }

  @Test
  void aHandlerDeclaredOnceThatResumesTwiceIsRefused() {
    var e = assertThrows(IllegalStateException.class,
        () -> run(Input.lying(Direct.row(Input.class).of(TiersTest::sumOfThree))));
    assertTrue(e.getMessage().contains("declares Once but resumed its rest again"), e.getMessage());
  }

  @Test
  void withoutTheExportsOnceTakesSavedFramesAndSaysWhy() throws IOException, InterruptedException {
    String java = ProcessHandle.current().info().command().orElseThrow();
    Process p = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "okay.resumable.TiersMain")
        .start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
    String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).strip();
    assertEquals(0, p.waitFor(), out + err);
    System.out.println(out);
    System.out.println(err);
    assertEquals(List.of("saved frames: java.base does not export jdk.internal.vm to unnamed module", "6"),
        out.lines().map(l -> l.replaceAll(" @[0-9a-f]+", "")).toList());
    assertEquals(1,
        err.lines().filter(l -> l.contains("okay.resumable: the JVM's continuations are not used")).count(), err);
  }
}
