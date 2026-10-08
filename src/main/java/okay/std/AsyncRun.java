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

  static <A> CompletableFuture<A> runAsync(Function<Async, Eff<A>> body) {
    CompletableFuture<A> done = new CompletableFuture<>();
    Eff<Void> run = Eff.handle((Prompt<Void> p) -> body.apply(new Async() {
      public <X> Eff<X> raise(Throwable e) {
        return p.shift0(_ -> fail(done, e));
      }

      public <X> Eff<X> async(Supplier<X> a) {
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

      @SuppressWarnings("unchecked")
      public <X> Eff<X> await(Consumer<BiConsumer<X, Throwable>> register) {
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
    }), a -> {
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
  private static <X> Eff<Void> resume(CompletableFuture<?> done, Function<X, Eff<Void>> k, Object r) {
    return r instanceof Failure(var e) ? fail(done, e) : k.apply(((Done<X>) r).value());
  }

  private static <X> Eff<X> fail(CompletableFuture<?> done, Throwable e) {
    done.completeExceptionally(e);
    return Eff.pure(null);
  }

  /** the run up to its end or to a wait; a throw in the program's own code its failure */
  private static void go(CompletableFuture<?> done, Eff<Void> run) {
    try {
      Eff.run(run);
    } catch (RuntimeException e) {
      done.completeExceptionally(e);
    }
  }
}
