package okay.resumable;

import static okay.rows.Effects.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import okay.rows.Comp;
import okay.rows.Eff;
import okay.rows.Effects;
import okay.rows.Pure;
import okay.rows.std.State.Final;
import okay.rows.std.Writer.Logged;
import org.junit.jupiter.api.Test;

/** A HANDLER INSTALLED AROUND A PART OF A BODY, in direct style: {@code Direct.row(..).embed(sub)} runs a sub-program,
 * its handlers inside it, as a part of the program that calls it — its operations that leave it go to the program's
 * handlers, and the body goes on with its value. And what a body that runs a program by itself, with a top-level
 * runner (a nested run), gets instead: a clear refusal, not a lost frame */
class EmbedTest {

  // ---- scoping

  /** a counter of its own around a part of the body: the part sees the local one, the body its own before and after;
   * the part's log goes to the program's log */
  static List<Integer> scoped(List<String> seen) throws Counter, Log {
    Counter.put(10);
    Log.tell("before");
    Final<Integer, Integer> local = Direct.row(Log.class).embed(Counter.run(100,
        Direct.row(Counter.class, Log.class).of(() -> {
          Log.tell("inside, the local counter at " + Counter.get());
          Counter.put(Counter.get() + 1);
          return Counter.get() * 2;
        })));
    seen.add("after");
    Log.tell("after");
    Counter.put(Counter.get() + 1);
    return List.of(local.state(), local.value(), Counter.get());
  }

  @Test
  void aLocalHandlerAroundAPartOfTheBodyScopesItsEffect() {
    List<String> seen = new ArrayList<>();
    var r = run(Log.run(Counter.run(0, Direct.row(Counter.class, Log.class).of(() -> scoped(seen)))));
    assertEquals(new Final<>(11, List.of(101, 202, 11)), r.value());
    assertEquals(List.of("before", "inside, the local counter at 100", "after"), r.log());
    assertEquals(List.of("after"), seen);
  }

  /** the same under each way an operation of the program's handler is answered: in place, by a clause on the body's
   * stack (declared Once), after a yield (Once, later), on saved frames (general) */
  static int partThenRest() throws Input {
    int a = Input.next();
    Final<Integer, Integer> local = Direct.row(Input.class).embed(Counter.run(a,
        Direct.row(Counter.class, Input.class).of(() -> {
          Counter.put(Counter.get() + Input.next());
          return Counter.get();
        })));
    return local.value() * 100 + Input.next();
  }

  @Test
  void theSameWhicheverWayTheProgramsHandlerAnswers() {
    assertEquals(303, run(Input.once(ints(), Direct.row(Input.class).of(EmbedTest::partThenRest))));
    assertEquals(303, run(Input.deferred(ints(), Direct.row(Input.class).of(EmbedTest::partThenRest))));
    assertEquals(303, run(Input.many(ints(), Direct.row(Input.class).of(EmbedTest::partThenRest))));
  }

  /** the program's row longer than the sub-program's: a place put for each effect it has not, so that each operation
   * goes to its own effect's place */
  static String placed() throws Log, Counter, Input {
    Counter.put(5);
    // the sub-program has the program's Input only: Log and Counter are put around it
    int n = Direct.row(Input.class).embed(Counter.run(0, Direct.row(Counter.class, Input.class)
        .of(() -> Counter.get() + Input.next()))).value();
    // the sub-program has Log and Input, Counter between them in the program's row
    Logged<String, Integer> l = Direct.row(Log.class, Input.class).embed(Log.run(Direct.row(Log.class, Log.class,
        Input.class).of(() -> {
          Log.tell("local");
          return Input.next();
        })));
    Log.tell("outer");
    return n + " " + l.log() + " " + l.value() + " " + Counter.get();
  }

  @Test
  void aSubProgramOfSomeOfTheProgramsEffectsInTheirOrder() {
    var r = run(Input.many(ints(), Counter.run(0, Log.run(
        Direct.row(Log.class, Counter.class, Input.class).of(EmbedTest::placed)))));
    assertEquals(new Logged<>(List.of("outer"), "1 [local] 2 5"), r.value());
  }

  static int wrongOrder() throws Log, Counter {
    return Direct.row(Counter.class, Log.class).embed(Direct.row(Counter.class, Log.class).of(() -> 1));
  }

  @Test
  void aSubProgramWhoseEffectsAreNotTheProgramsInOrderIsRefused() {
    var e = assertThrows(IllegalStateException.class,
        () -> run(Counter.run(0, Log.run(Direct.row(Log.class, Counter.class).of(EmbedTest::wrongOrder)))));
    assertTrue(e.getMessage().contains("Direct.row(Counter.class, Log.class).embed was called by a program of the "
        + "row (Log.class, Counter.class)"), e.getMessage());
  }

  @Test
  void embedWhereNoProgramRunsIsRefused() {
    var e = assertThrows(IllegalStateException.class,
        () -> Direct.row(Counter.class).embed(Direct.row(Counter.class).of(() -> 1)));
    assertTrue(e.getMessage().contains("where no program runs"), e.getMessage());
  }

  /** every path of a choice outside the body re-enters the part's frames, nothing replayed */
  static int chosenThenScoped(List<String> entered) throws Pick {
    int x = Pick.among(1, 2);
    entered.add("body " + x);
    int local = Direct.row(Pick.class).embed(Counter.run(x * 10, Direct.row(Counter.class, Pick.class).of(() -> {
      int y = Pick.among(0, 1);
      Counter.put(Counter.get() + y);
      return Counter.get();
    }))).value();
    return local;
  }

  @Test
  void aChoiceOutsideResumesThePartOncePerPath() {
    List<String> entered = new ArrayList<>();
    assertEquals(List.of(10, 11, 20, 21), run(Pick.all(Direct.row(Pick.class).of(() -> chosenThenScoped(entered)))));
    assertEquals(List.of("body 1", "body 2"), entered);
  }

  // ---- a handler installed around the rest at every step

  static final int STEPS = 100_000;

  /** each step a counter of its own, around the rest of the steps: STEPS handlers nested, the program's Input asked
   * through all of them at the end */
  static int stepLocal(int i, int n) throws Input {
    if (i == n) return Input.next();
    Final<Integer, Integer> r = Direct.row(Input.class).embed(Counter.run(i, Direct.row(Counter.class, Input.class)
        .of(() -> {
          Counter.put(Counter.get() + 1);
          return stepLocal(i + 1, n);
        })));
    return r.value() + r.state() - i;
  }

  /** the same steps, one counter outside them all */
  static int stepOutside(int n) throws Counter, Input {
    for (int i = 0; i < n; i++) Counter.put(Counter.get() + 1);
    return Counter.get() + Input.next();
  }

  @Test
  void aHandlerReinstalledAroundTheRestAtEveryStep() {
    Iterator<Integer> seven = Stream.iterate(7, x -> x).iterator();
    long t0 = System.nanoTime();
    long m0 = used();
    assertEquals(STEPS + 7, run(Input.many(seven, Direct.row(Input.class).of(() -> stepLocal(0, STEPS)))));
    long local = System.nanoTime() - t0;
    long localMemory = used() - m0;
    t0 = System.nanoTime();
    assertEquals(STEPS + 7, (int) run(Input.many(seven, Counter.run(0,
        Direct.row(Counter.class, Input.class).of(() -> stepOutside(STEPS))))).value());
    long outside = System.nanoTime() - t0;
    System.out.printf("a handler around the rest at each of %d steps: %d ms, heap +%d MB; one handler outside: "
        + "%d ms%n", STEPS, local / 1_000_000, localMemory >> 20, outside / 1_000_000);
  }

  private static long used() {
    Runtime r = Runtime.getRuntime();
    return r.totalMemory() - r.freeMemory();
  }

  // ---- a nested run: a body that runs a program by itself

  /** a body that runs a program of its own effect to its end, by a top-level runner: the program's handler is not
   * inside that run, so its operation reaches no handler there */
  @SuppressWarnings({"unchecked", "rawtypes"}) // the row told a lie, as only a cast can: the nested run takes Pure
  static int nestedRun(List<String> left) throws Input {
    try {
      int a = Input.next();
      Comp lied = Direct.row(Input.class).of(() -> Input.next() + 1);
      return a + (int) Effects.run(lied);
    } finally {
      left.add("finally");
    }
  }

  @Test
  void aNestedRunOfTheProgramsEffectIsRefusedOnEveryTier() {
    for (var how : List.<Function<Comp<Integer, Eff<Input, Pure>>,
        Comp<Integer, ?>>>of(p -> Input.once(ints(), p), p -> Input.deferred(ints(), p), p -> Input.many(ints(), p))) {
      List<String> left = new ArrayList<>();
      var e = assertThrows(IllegalStateException.class,
          () -> run((Comp<Integer, Pure>) (Comp<?, ?>) how.apply(Direct.row(Input.class)
              .of(() -> nestedRun(left)))));
      assertTrue(e.getMessage().contains("ran a program by a top-level runner"), e.getMessage());
      assertEquals(List.of("finally"), left);
    }
  }

  /** a nested run whose program has its handler inside it: a function of the body, as any. The captures inside it,
   * on each way, stay inside it, the runner's frames on the stack under them; the body's own frames, around it, are
   * saved and resumed on each way too */
  static int selfContained(Function<Comp<Integer, Eff<Input, Pure>>,
      Comp<Integer, Pure>> inner) throws Input {
    int a = Input.next();
    int b = Effects.run(inner.apply(Direct.row(Input.class).of(() -> Input.next() * 10 + Input.next())));
    return a * 1000 + b + Input.next() * 100_000;
  }

  @Test
  void aNestedRunWithItsHandlersInsideItIsAFunctionOfTheBody() {
    List<Function<Comp<Integer, Eff<Input, Pure>>, Comp<Integer, Pure>>>
        ways = List.of(p -> Input.once(ints(), p), p -> Input.deferred(ints(), p), p -> Input.many(ints(), p));
    for (var outer : ways)
      for (var inner : ways)
        assertEquals(201_012, run(outer.apply(Direct.row(Input.class).of(() -> selfContained(inner)))));
  }

  private static Iterator<Integer> ints() {
    return Stream.iterate(1, x -> x + 1).iterator();
  }
}
