package okay.std;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import okay.core.Comp;
import okay.core.Prompt;

/** {@link Async}'s handler: a prompt at the top, the future the run's end */
final class AsyncRun {
  private AsyncRun() {}

  static <A> CompletableFuture<A> runAsync(Async.Body<A> body) {
    CompletableFuture<A> done = new CompletableFuture<>();
    Comp<Void, Comp.Top> run = Comp.handle(new Comp.Body<Comp.Top, A, Void>() {
      public <M extends Comp.Top> Comp<A, M> in(Prompt<Comp.Top, M, Void> p) {
        return body.in(new Async<M>() {
          public <X> Comp<X, M> raise(Throwable e) {
            return p.shift0(_ -> fail(done, e));
          }

          public <X> Comp<X, M> async(Supplier<X> a) {
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

          public <X> Comp<X, M> await(Consumer<BiConsumer<X, Throwable>> register) {
            return p.shift0(k -> {
              // where the callback's value is against the run waiting on it: not yet; come while the run registered,
              // taken in place; or the run gone on without it, resumed by the callback
              AtomicReference<Object> slot = new AtomicReference<>(WAITING);
              register.accept((x, e) -> {
                Object r = e == null ? new Done<>(x) : new Failure(e);
                if (!slot.compareAndSet(WAITING, r)) go(done, resume(done, k, r));
              });
              if (slot.compareAndSet(WAITING, DETACHED)) return Comp.pure(null);
              return resume(done, k, slot.get());
            });
          }
        });
      }
    }, a -> {
      done.complete(a);
      return Comp.pure(null);
    });
    go(done, run);
    return done;
  }

  private static final Object WAITING = new Object();
  private static final Object DETACHED = new Object();

  private record Done<X>(X value) {}

  private record Failure(Throwable e) {}

  @SuppressWarnings("unchecked")
  private static <X> Comp<Void, Comp.Top> resume(
      CompletableFuture<?> done, Function<X, Comp<Void, Comp.Top>> k, Object r) {
    return r instanceof Failure(var e) ? fail(done, e) : k.apply(((Done<X>) r).value());
  }

  private static <L, X> Comp<X, L> fail(CompletableFuture<?> done, Throwable e) {
    done.completeExceptionally(e);
    return Comp.pure(null);
  }

  /** the run up to its end or to a wait; a throw in the program's own code its failure */
  private static void go(CompletableFuture<?> done, Comp<Void, Comp.Top> run) {
    try {
      Comp.runIsolated(run);
    } catch (RuntimeException e) {
      done.completeExceptionally(e);
    }
  }
}
