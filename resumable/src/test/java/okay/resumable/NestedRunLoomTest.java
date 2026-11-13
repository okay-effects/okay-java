package okay.resumable;

import static okay.rows.Effects.handle;
import static okay.rows.Effects.pure;
import static okay.rows.Effects.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okay.core.NestedRun;
import okay.rows.Comp;
import okay.rows.Eff;
import okay.rows.Effects;
import okay.rows.Handler;
import okay.rows.K;
import okay.rows.Once;
import okay.rows.Pure;
import okay.rows.std.Async;
import org.junit.jupiter.api.Test;

/** NestedRun and the bodies of okay-java-resumable: a body on the JVM's continuation runs on the thread that runs
 * its program, so a run it begins is refused as any; a body yielded on one thread and resumed by a callback on
 * another — the machine run again there, by Async's runner — is not */
class NestedRunLoomTest {
  /** each Next answered later, by a callback on the pool: declared Once, the clause not resuming at once — the body
   * yields on the JVM's continuation, and is resumed on a pool thread */
  static <A> Comp<A, Eff<Async, Pure>> onThePool(ExecutorService pool, Set<String> threads,
      Comp<A, ? super Eff<Input, Eff<Async, Pure>>> p) {
    final class Later implements Handler<Input, A, A, Eff<Async, Pure>>, Once {
      int next;

      public Comp<A, Eff<Async, Pure>> ret(A a) { return pure(a); }
      public Comp<A, Eff<Async, Pure>> on(Input op, K<A, Eff<Async, Pure>> k) {
        return switch (op) {
          case Input.Next n -> Effects.<Integer, Eff<Async, Pure>>fit(Async.<Integer>await(cb -> pool.execute(() -> {
            threads.add(Thread.currentThread().getName());
            cb.accept(++next);
          }))).flatMap(x -> k.resume(n, x));
        };
      }
    }
    return handle(Input.class, new Later(), p);
  }

  static int sum(int n) throws Input {
    int s = 0;
    for (int i = 0; i < n; i++) s += Input.next();
    return s;
  }

  @Test
  void aBodyYieldedAndResumedOnOtherThreadsIsNotRefused() throws Exception {
    Set<String> threads = ConcurrentHashMap.newKeySet();
    try (ExecutorService pool = Executors.newFixedThreadPool(3)) {
      CompletableFuture<Integer> done = new CompletableFuture<>();
      Async.start(onThePool(pool, threads, Effects.under(Direct.row(Input.class).of(() -> sum(1000)))),
          done::complete);
      assertEquals(500_500, done.get(10, TimeUnit.SECONDS));
    }
    assertTrue(threads.size() > 1, threads.toString());
  }

  @Test
  void aRunBegunByABodyIsRefusedHereAndAfterAResumptionOnAnotherThread() throws Exception {
    // on this thread: a body that runs a program by itself
    List<String> left = new ArrayList<>();
    assertThrows(NestedRun.class, () -> run(Input.deferred(List.of(1, 2).iterator(),
        Direct.row(Input.class).of(() -> {
          try {
            return Input.next() + run(Effects.<Integer, Pure>pure(1));
          } finally {
            left.add("finally");
          }
        }))));
    assertEquals(List.of("finally"), left);
    // resumed on a pool thread: the run it begins there is refused there
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      CompletableFuture<Object> done = new CompletableFuture<>();
      Async.start(onThePool(pool, ConcurrentHashMap.newKeySet(), Effects.under(Direct.row(Input.class).of(() -> {
        int a = Input.next();
        try {
          return a + run(Effects.<Integer, Pure>pure(1));
        } catch (NestedRun e) {
          return -1;
        }
      }))), done::complete);
      assertEquals(-1, done.get(10, TimeUnit.SECONDS));
    }
  }
}
