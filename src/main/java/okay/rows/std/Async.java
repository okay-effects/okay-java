package okay.rows.std;

import static okay.rows.Effects.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import okay.rows.Comp;
import okay.rows.Handler;
import okay.rows.Eff;
import okay.rows.K;
import okay.rows.Pure;
import okay.rows.Op;

/** ASYNC: an answer that comes later. An operation gives a callback to whoever will have the answer; the handler
 * keeps the rest of the program and returns. The callback, whenever and on whatever thread it is called, resumes
 * the rest. No threads are held waiting: asynchrony is callbacks and continuations, nothing else. */
public sealed interface Async {
  record Await<X>(Consumer<Consumer<X>> register) implements Async, Op<Async, X, Await<X>> {}

  /** the answer {@code register} will be called back with */
  static <X> Comp<X, Eff<Async, ?>> await(Consumer<Consumer<X>> register) {
    return perform(new Await<>(register));
  }

  static Comp<Void, Eff<Async, ?>> sleep(long millis) {
    return await(resume -> Timer.ONE.schedule(() -> resume.accept(null), millis, TimeUnit.MILLISECONDS));
  }

  /** the program started; {@code done} is called with its value, now or later. Returns as soon as the program
   * waits. */
  static <A> void start(Comp<A, ? super Eff<Async, Pure>> p, Consumer<A> done) {
    Turns turns = new Turns();
    Comp<Void, Pure> started = handle(Async.class, new Handler<Async, A, Void, Pure>() {
      public Comp<Void, Pure> ret(A a) { done.accept(a); return pure(null); }
      public Comp<Void, Pure> on(Async op, K<Void, Pure> k) {
        return switch (op) {
          case Await<?> await -> registered(await, k, turns);
        };
      }
    }, p);
    turns.take(() -> run(started));
  }

  private static <X> Comp<Void, Pure> registered(Await<X> await, K<Void, Pure> k, Turns turns) {
    await.register().accept(x -> turns.take(() -> run(k.resume(await, x))));
    return pure(null);
  }

  /** both programs at once; their values when both have come */
  static <A, B> Comp<Pair<A, B>, Eff<Async, ?>> both(
      Comp<A, ? super Eff<Async, Pure>> a, Comp<B, ? super Eff<Async, Pure>> b) {
    return await(resume -> {
      List<A> left = new ArrayList<>(); // none or one: a value that came, waiting for the other
      List<B> right = new ArrayList<>();
      Object lock = new Object();
      start(a, x -> {
        boolean ready;
        synchronized (lock) { left.add(x); ready = !right.isEmpty(); }
        if (ready) resume.accept(new Pair<>(x, right.get(0)));
      });
      start(b, y -> {
        boolean ready;
        synchronized (lock) { right.add(y); ready = !left.isEmpty(); }
        if (ready) resume.accept(new Pair<>(left.get(0), y));
      });
    });
  }

  /** all the programs at once; their values, in the programs' order */
  static <A> Comp<List<A>, Eff<Async, ?>> all(List<? extends Comp<A, ? super Eff<Async, Pure>>> ps) {
    if (ps.isEmpty()) return pure(List.of());
    return both(ps.get(0), all(ps.subList(1, ps.size()))).map(pair -> {
      List<A> values = new ArrayList<>(List.of(pair.first()));
      values.addAll(pair.second());
      return values;
    });
  }

  /** both programs at once; the value that comes first — the other's is dropped */
  static <A> Comp<A, Eff<Async, ?>> race(Comp<A, ? super Eff<Async, Pure>> a, Comp<A, ? super Eff<Async, Pure>> b) {
    return await(resume -> {
      AtomicBoolean won = new AtomicBoolean(false);
      start(a, x -> { if (won.compareAndSet(false, true)) resume.accept(x); });
      start(b, x -> { if (won.compareAndSet(false, true)) resume.accept(x); });
    });
  }

  /** the future's outcome, awaited */
  static <A> Comp<Throws.Result<Throwable, A>, Eff<Async, ?>> fromFuture(Supplier<CompletableFuture<A>> future) {
    return await(resume -> future.get().whenComplete((a, failure) ->
        resume.accept(failure == null ? new Throws.Ok<>(a) : new Throws.Err<>(failure))));
  }

  static <A> CompletableFuture<A> toFuture(Comp<A, ? super Eff<Async, Pure>> p) {
    CompletableFuture<A> future = new CompletableFuture<>();
    start(p, future::complete);
    return future;
  }

  /** the program's value, this thread waiting for it: the edge of a program that is not asynchronous itself */
  static <A> A runBlocking(Comp<A, ? super Eff<Async, Pure>> p) { return toFuture(p).join(); }
}

/** The one thread that wakes the sleeping; it runs nothing of a program but the callback. */
final class Timer {
  static final ScheduledThreadPoolExecutor ONE = new ScheduledThreadPoolExecutor(1, runnable -> {
    Thread thread = new Thread(runnable, "okay-timer");
    thread.setDaemon(true);
    return thread;
  });
}

/** The turns of one started program, one at a time and in a loop: a callback called at once, or from another
 * thread, or a million times in a row, neither nests nor overlaps. */
final class Turns {
  private final ArrayDeque<Runnable> ready = new ArrayDeque<>();
  private boolean running = false;

  void take(Runnable turn) {
    synchronized (this) {
      ready.add(turn);
      if (running) return;
      running = true;
    }
    while (true) {
      Runnable next;
      synchronized (this) {
        next = ready.poll();
        if (next == null) { running = false; return; }
      }
      try {
        next.run();
      } catch (Throwable e) {
        synchronized (this) { running = false; }
        throw e;
      }
    }
  }
}
