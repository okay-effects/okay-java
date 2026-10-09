package okay.std;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import okay.core.Eff;

/**
 * a program that waits — on a computation suspended, {@link #async}, or on a callback, {@link #await}. Its failure
 * is a {@code Throws<Throwable>}: a throw in a computation, a failure at a callback, the program's own
 * {@code raise}, each the end of the run. Handled at the top, by {@link #runAsync}: a {@link CompletableFuture}
 * of the value. The run goes on in the callback's thread when the callback comes later, in place when it came
 * already — so a callback answered at once is a loop, not a frame. A handler inside goes on with it: k holds its
 * delimiter, by prompt, whatever thread resumes it. On virtual threads: a blocking call, {@link #blocking}, waited for
 * without a thread held; a run of its own, {@link #fork}, started on one, its future awaited.
 */
public interface Async<L> extends Throws<L, Throwable> {
  /** a computation, suspended: run when the run gets to it */
  <A> Eff<L, A> async(Supplier<A> a);

  /** the value a callback is called with, once: {@code (value, null)} or {@code (null, failure)} */
  <A> Eff<L, A> await(Consumer<BiConsumer<A, Throwable>> register);

  default <A> Eff<L, A> await(CompletionStage<A> stage) {
    return await(cb -> stage.whenComplete(cb));
  }

  /** a BLOCKING computation — a database call, a file, a socket — on a virtual thread of its own: the run waits for
   * it without holding a thread, and goes on in the virtual thread when it is done. Its throw is the run's failure */
  default <A> Eff<L, A> blocking(Callable<A> a) {
    return await(cb -> Thread.ofVirtual().start(() -> {
      A v;
      try {
        v = a.call();
      } catch (Throwable e) {
        cb.accept(null, e);
        return;
      }
      cb.accept(v, null);
    }));
  }

  /** a run of its own, {@code body}, started on a virtual thread: its future, to {@link #await} — fork and join */
  default <A> Eff<L, CompletableFuture<A>> fork(Body<A> body) {
    return async(() -> {
      CompletableFuture<A> done = new CompletableFuture<>();
      Thread.ofVirtual().start(() -> runAsync(body).whenComplete((v, e) -> {
        if (e == null) done.complete(v);
        else done.completeExceptionally(e instanceof CompletionException c && c.getCause() != null ? c.getCause() : e);
      }));
      return done;
    });
  }

  /** this capability at a level further in */
  @SuppressWarnings("unchecked")
  default <N extends L> Async<N> at() {
    return (Async<N>) (Async<?>) this;
  }

  /** the body of an async run: at a fresh level under the top, given the capability there */
  interface Body<A> {
    <M extends Eff.Top> Eff<M, A> in(Async<M> as);
  }

  /** the body run, its value or its failure the future's */
  static <A> CompletableFuture<A> runAsync(Body<A> body) {
    return AsyncRun.runAsync(body);
  }

  /** the body's value, this thread waiting for it — at the edge of a program only; its failure raised to {@code t} */
  static <L, A> Eff<L, A> run(Throws<L, Throwable> t, Body<A> body) {
    return Eff.delay(() -> {
      try {
        return Eff.pure(runAsync(body).join());
      } catch (CompletionException e) {
        return t.raise(e.getCause());
      }
    });
  }
}
