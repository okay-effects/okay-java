package okay.std;

import static okay.core.Eff.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import okay.core.Eff;
import okay.core.Eff.Top;
import okay.std.State.Final;
import okay.std.Throws.Result;
import org.junit.jupiter.api.Test;

/** okay-core's TestPStateAsync, the Async part; and the other handlers of okay-core's std: a cell state, throws */
class AsyncTest {

  /** an async run at the top, its failure the {@code Failed}'s message */
  static <A> Result<String, A> runIO(Async.Body<A> body) {
    return runIO(body, false);
  }

  /** the same, a run of its own when {@code isolated}: inside a running program */
  static <A> Result<String, A> runIO(Async.Body<A> body, boolean isolated) {
    Eff<Top, Result<Throwable, A>> p = Throws.attempt(new Throws.Body<Top, Throwable, A>() {
      public <M extends Top> Eff<M, A> in(Throws<M, Throwable> t) {
        return Async.run(t, body);
      }
    });
    Result<Throwable, A> r = isolated ? Eff.runIsolated(p) : run(p);
    return switch (r) {
      case Result.Ok<Throwable, A>(var a) -> new Result.Ok<>(a);
      case Result.Failed<Throwable, A>(var e) -> new Result.Failed<>(e.getMessage());
    };
  }

  @Test
  void computationsRunInOrderTheirValuesBound() {
    assertEquals(new Result.Ok<>(42), runIO(new Async.Body<Integer>() {
      public <M extends Top> Eff<M, Integer> in(Async<M> as) {
        return as.async(() -> 20).flatMap(a -> as.async(() -> a + 1)).map(x -> x * 2);
      }
    }));
  }

  @Test
  void aCallbackFromAnotherThreadTheRunGoingOnInIt() {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      assertEquals(new Result.Ok<>(6), runIO(new Async.Body<Integer>() {
        <M extends Top> Eff<M, Integer> later(Async<M> as, int x) {
          return as.await(cb -> pool.execute(() -> {
            sleep();
            cb.accept(x, null);
          }));
        }

        public <M extends Top> Eff<M, Integer> in(Async<M> as) {
          return later(as, 1).flatMap(a -> later(as, 2).map(b -> a + b)).flatMap(a -> later(as, 3).map(b -> a + b));
        }
      }));
    } finally {
      pool.shutdown();
    }
  }

  private static void sleep() {
    try {
      Thread.sleep(1);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  static <L> Eff<L, Integer> loop(Async<L> as, int i, int acc) {
    return i == 0 ? Eff.pure(acc) : as.<Integer>await(cb -> cb.accept(1, null)).flatMap(x -> loop(as, i - 1, acc + x));
  }

  @Test
  void callbacksAnsweredAtOnceInConstantStack() {
    assertEquals(new Result.Ok<>(100_000), runIO(new Async.Body<Integer>() {
      public <M extends Top> Eff<M, Integer> in(Async<M> as) {
        return loop(as, 100_000, 0);
      }
    }));
  }

  @Test
  void aFailureAtACallbackOrInAComputationTheRestNeverRuns() {
    boolean[] ran = {false};
    Result<String, Integer> e1 = runIO(new Async.Body<Integer>() {
      public <M extends Top> Eff<M, Integer> in(Async<M> as) {
        return as.<Integer>await(cb -> cb.accept(null, new IllegalStateException("cb"))).map(x -> { ran[0] = true; return x; });
      }
    });
    Result<String, Integer> e2 = runIO(new Async.Body<Integer>() {
      public <M extends Top> Eff<M, Integer> in(Async<M> as) {
        return as.async(() -> Integer.parseInt("x")).map(x -> { ran[0] = true; return x; });
      }
    });
    assertEquals(new Result.Failed<>("cb"), e1);
    assertEquals(new Result.Failed<>("For input string: \"x\""), e2);
    assertFalse(ran[0]);
  }

  @Test
  void theProgramsOwnRaiseFailsTheRun() {
    assertEquals(new Result.Failed<>("own"), runIO(new Async.Body<Integer>() {
      public <M extends Top> Eff<M, Integer> in(Async<M> as) {
        return as.async(() -> 1).flatMap(_ -> as.<Integer>raise(new IllegalStateException("own")));
      }
    }));
  }

  @Test
  void aStateInsideGoesOnThroughTheCallbacks() {
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Result<String, Final<Integer, Integer>> r = runIO(new Async.Body<Final<Integer, Integer>>() {
        public <M extends Top> Eff<M, Final<Integer, Integer>> in(Async<M> as) {
          return State.state(0, new State.Body<M, Integer, Integer>() {
            public <N extends M> Eff<N, Integer> in(State<N, Integer> st) {
              return as.<Integer>await(cb -> pool.execute(() -> cb.accept(5, null))).<N>at().flatMap(x -> st.modify(s -> s + x))
                  .then(as.<Integer>await(cb -> pool.execute(() -> cb.accept(7, null)))).flatMap(y -> st.modify(s -> s + y))
                  .then(st.get());
            }
          });
        }
      });
      assertEquals(new Result.Ok<>(new Final<>(12, 12)), r);
    } finally {
      pool.shutdown();
    }
  }

  @Test
  void aReaderOutsideTheAsyncRun() {
    // the reader's capability is the body's, at a level under the top: not the async run's, which is at the top on
    // its own — so its value is asked first, and the run given it: a run inside a running program, on purpose
    assertEquals(new Result.Ok<>(42), run(Reader.reader(41, new Reader.Body<Top, Integer, Result<String, Integer>>() {
      public <M extends Top> Eff<M, Result<String, Integer>> in(Reader<M, Integer> r) {
        return r.ask().map(a -> runIO(new Async.Body<Integer>() {
          public <N extends Top> Eff<N, Integer> in(Async<N> as) {
            return as.async(() -> a + 1);
          }
        }, true));
      }
    })));
  }

  @Test
  void aBlockingCallOnAVirtualThreadTheRunGoingOnInIt() {
    boolean[] virtual = {false, false};
    Result<String, Integer> r = runIO(new Async.Body<Integer>() {
      public <M extends Top> Eff<M, Integer> in(Async<M> as) {
        return as.blocking(() -> {
          virtual[0] = Thread.currentThread().isVirtual();
          Thread.sleep(5);
          return 41;
        }).flatMap(x -> as.async(() -> {
          virtual[1] = Thread.currentThread().isVirtual();
          return x + 1;
        }));
      }
    });
    assertEquals(new Result.Ok<>(42), r);
    // the call on a virtual thread, and the rest of the run in it after
    assertEquals(List.of(true, true), List.of(virtual[0], virtual[1]));
  }

  @Test
  void aBlockingCallsThrowTheRunsFailure() {
    assertEquals(new Result.Failed<>("io"), runIO(new Async.Body<Integer>() {
      public <M extends Top> Eff<M, Integer> in(Async<M> as) {
        return as.blocking(() -> { throw new java.io.IOException("io"); });
      }
    }));
  }

  /** a run of its own: a blocking sleep, then its number */
  static Async.Body<Integer> sleeper(int i) {
    return new Async.Body<>() {
      public <M extends Top> Eff<M, Integer> in(Async<M> as) {
        return as.blocking(() -> {
          Thread.sleep(50);
          return i;
        });
      }
    };
  }

  static <L> Eff<L, Integer> joinAll(Async<L> as, List<java.util.concurrent.CompletableFuture<Integer>> fs, int i, int acc) {
    return i == fs.size() ? Eff.pure(acc) : as.await(fs.get(i)).flatMap(x -> joinAll(as, fs, i + 1, acc + x));
  }

  static <L> Eff<L, List<java.util.concurrent.CompletableFuture<Integer>>> forkAll(Async<L> as, int n, int i,
      List<java.util.concurrent.CompletableFuture<Integer>> acc) {
    return i == n ? Eff.pure(acc) : as.fork(sleeper(i)).flatMap(f -> {
      acc.add(f);
      return forkAll(as, n, i + 1, acc);
    });
  }

  @Test
  void tenThousandRunsForkedEachBlockingFiftyMillisecondsJoinedAtOnce() {
    // on virtual threads: 10 000 blocking sleeps of 50 ms side by side — 500 s one after the other
    long t0 = System.nanoTime();
    Result<String, Integer> r = runIO(new Async.Body<Integer>() {
      public <M extends Top> Eff<M, Integer> in(Async<M> as) {
        return forkAll(as, 10_000, 0, new java.util.ArrayList<>()).flatMap(fs -> joinAll(as, fs, 0, 0));
      }
    });
    long ms = (System.nanoTime() - t0) / 1_000_000;
    assertEquals(new Result.Ok<>(10_000 * 9_999 / 2), r);
    assertTrue(ms < 20_000, ms + " ms");
  }

  @Test
  void aForkedRunsFailureAtItsAwait() {
    assertEquals(new Result.Failed<>("forked"), runIO(new Async.Body<Integer>() {
      public <M extends Top> Eff<M, Integer> in(Async<M> as) {
        return as.fork(new Async.Body<Integer>() {
          public <N extends Top> Eff<N, Integer> in(Async<N> inner) {
            return inner.raise(new IllegalStateException("forked"));
          }
        }).flatMap(as::await);
      }
    }));
  }

  @Test
  void aCompletionStageAwaited() {
    assertEquals(new Result.Ok<>("x!"), runIO(new Async.Body<String>() {
      public <M extends Top> Eff<M, String> in(Async<M> as) {
        return as.await(java.util.concurrent.CompletableFuture.supplyAsync(() -> "x")).map(s -> s + "!");
      }
    }));
  }

  /** a choice, the state moved by it, the state read */
  static <L> Eff<L, Integer> step(State<L, Integer> st, Choose<L> ch) {
    return ch.among(List.of(1, 2)).flatMap(x -> st.modify(s -> s + x)).then(st.get());
  }

  @Test
  void aCellStateAnsweredInPlaceSharedByTheResumptions() {
    // okay-core's state: one cell per run, a body resumed twice sees the first resumption's last state
    assertEquals(new Final<>(3, List.of(1, 3)), run(State.cell(0, new State.Body<Top, Integer, List<Integer>>() {
      public <M extends Top> Eff<M, List<Integer>> in(State<M, Integer> st) {
        return Choose.all(new Choose.Body<M, Integer>() {
          public <N extends M> Eff<N, Integer> in(Choose<N> ch) {
            return step(st.at(), ch);
          }
        });
      }
    })));
    // choose outside too: its k resumes the body in the same run of cell — the second path sees the first's 1
    assertEquals(List.of(new Final<>(1, 1), new Final<>(3, 3)), run(Choose.all(new Choose.Body<Top, Final<Integer, Integer>>() {
      public <M extends Top> Eff<M, Final<Integer, Integer>> in(Choose<M> ch) {
        return State.cell(0, new State.Body<M, Integer, Integer>() {
          public <N extends M> Eff<N, Integer> in(State<N, Integer> st) {
            return step(st, ch.at());
          }
        });
      }
    })));
    assertEquals(new Final<>(100_000, 100_000), run(State.cell(0, new State.Body<Top, Integer, Integer>() {
      public <M extends Top> Eff<M, Integer> in(State<M, Integer> st) {
        return count(st, 100_000);
      }
    })));
  }

  static <L> Eff<L, Integer> count(State<L, Integer> st, int i) {
    return i == 0 ? st.get() : st.modify(s -> s + 1).then(Eff.delay(() -> count(st, i - 1)));
  }

  @Test
  void throwsTheValueOrTheFirstRaise() {
    assertEquals(new Result.Ok<>(1), run(Throws.attempt(new Throws.Body<Top, String, Integer>() {
      public <M extends Top> Eff<M, Integer> in(Throws<M, String> t) {
        return Eff.pure(1);
      }
    })));
    assertEquals(new Result.Failed<>("a"), run(Throws.attempt(new Throws.Body<Top, String, Integer>() {
      public <M extends Top> Eff<M, Integer> in(Throws<M, String> t) {
        return t.<Integer>raise("a").then(t.raise("b"));
      }
    })));
  }
}
