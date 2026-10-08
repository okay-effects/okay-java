package okay.std;

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
 * delimiter, by prompt, whatever thread resumes it.
 */
public interface Async extends Throws<Throwable> {
  /** a computation, suspended: run when the run gets to it */
  <A> Eff<A> async(Supplier<A> a);

  /** the value a callback is called with, once: {@code (value, null)} or {@code (null, failure)} */
  <A> Eff<A> await(Consumer<BiConsumer<A, Throwable>> register);

  default <A> Eff<A> await(CompletionStage<A> stage) {
    return await(cb -> stage.whenComplete(cb));
  }

  /** the body run, its value or its failure the future's */
  static <A> CompletableFuture<A> runAsync(Function<Async, Eff<A>> body) {
    return AsyncRun.runAsync(body);
  }

  /** the body's value, this thread waiting for it — at the edge of a program only; its failure raised to {@code t} */
  static <A> Eff<A> run(Throws<Throwable> t, Function<Async, Eff<A>> body) {
    return Eff.delay(() -> {
      try {
        return Eff.pure(runAsync(body).join());
      } catch (CompletionException e) {
        return t.raise(e.getCause());
      }
    });
  }
}
