package okay.std;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import okay.core.Eff;
import okay.core.Prompt;

/** {@link Async}'s handler: a prompt at the top, the future the run's end */
final class AsyncRun {
  private AsyncRun() {}

  static <A> CompletableFuture<A> runAsync(Async.Body<A> body) {
    CompletableFuture<A> done = new CompletableFuture<>();
    Eff<Eff.Top, Void> run = Eff.handle(new Eff.Body<Eff.Top, A, Void>() {
      public <M extends Eff.Top> Eff<M, A> in(Prompt<Eff.Top, M, Void> p) {
        return body.in(new Async<M>() {
          public <X> Eff<M, X> raise(Throwable e) {
            return p.shift0(_ -> fail(done, e));
          }

          public <X> Eff<M, X> async(Supplier<X> a) {
            return p.shift0(k -> {
              X x;
              try {
                x = a.get();
              } catch (RuntimeException e) {
                return fail(done, e);
              }
              return k.apply(x);
            });
          }

          public <X> Eff<M, X> await(Consumer<BiConsumer<X, Throwable>> register) {
            return p.shift0(k -> {
              // where the callback's value is against the run waiting on it: not yet; come while the run registered,
              // taken in place; or the run gone on without it, resumed by the callback
              AtomicReference<Object> slot = new AtomicReference<>(WAITING);
              register.accept((x, e) -> {
                Object r = e == null ? new Done<>(x) : new Failure(e);
                if (!slot.compareAndSet(WAITING, r)) go(done, resume(done, k, r));
              });
              if (slot.compareAndSet(WAITING, DETACHED)) return Eff.pure(null);
              return resume(done, k, slot.get());
            });
          }
        });
      }
    }, a -> {
      done.complete(a);
      return Eff.pure(null);
    });
    go(done, run);
    return done;
  }

  private static final Object WAITING = new Object();
  private static final Object DETACHED = new Object();

  private record Done<X>(X value) {}

  private record Failure(Throwable e) {}

  @SuppressWarnings("unchecked")
  private static <X> Eff<Eff.Top, Void> resume(CompletableFuture<?> done, Function<X, Eff<Eff.Top, Void>> k, Object r) {
    return r instanceof Failure(var e) ? fail(done, e) : k.apply(((Done<X>) r).value());
  }

  private static <L, X> Eff<L, X> fail(CompletableFuture<?> done, Throwable e) {
    done.completeExceptionally(e);
    return Eff.pure(null);
  }

  /** the run up to its end or to a wait; a throw in the program's own code its failure */
  private static void go(CompletableFuture<?> done, Eff<Eff.Top, Void> run) {
    try {
      Eff.run(run);
    } catch (RuntimeException e) {
      done.completeExceptionally(e);
    }
  }
}
