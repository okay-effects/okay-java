package okay.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import okay.core.Comp.Top;
import okay.std.Choose;
import okay.std.Emit;
import okay.std.State;
import okay.std.Throws;
import okay.std.Writer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.Warmup;

/** okay-core master's MonadsBenchmark on {@link Comp}, its programs and its names: `n` operations each but where
 * said. An answering handler here is the capability itself, under a handler installed all the same; okay-core's
 * `state` is a cell, {@link State#cell} — `stateFunctional` the functional one beside it */
@org.openjdk.jmh.annotations.State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class MonadsBenchmark {

  @Param("1000")
  public int n;

  List<Integer> range;

  @Setup
  public void setup() {
    range = new ArrayList<>();
    for (int i = 0; i < n; i++) range.add(i);
  }

  static <L> Comp<Void, L> unit() {
    return Comp.pure(null);
  }

  static <L> Comp<Boolean, L> isEven(int i) {
    return i == 0 ? Comp.pure(true) : MonadsBenchmark.<L>unit().flatMap(_ -> isOdd(i - 1));
  }

  static <L> Comp<Boolean, L> isOdd(int i) {
    return i == 0 ? Comp.pure(false) : MonadsBenchmark.<L>unit().flatMap(_ -> isEven(i - 1));
  }

  @Benchmark
  public boolean pure() {
    return Comp.run(isEven(n));
  }

  interface Tick<L> {
    Comp<Integer, L> tick(int a);

    @SuppressWarnings("unchecked")
    default <N extends L> Tick<N> at() {
      return (Tick<N>) (Tick<?>) this;
    }
  }

  static <L> Comp<Boolean, L> isEvenT(Tick<L> t, int i) {
    return i == 0 ? Comp.pure(true) : t.tick(i).flatMap(_ -> isOddT(t, i - 1));
  }

  static <L> Comp<Boolean, L> isOddT(Tick<L> t, int i) {
    return i == 0 ? Comp.pure(false) : t.tick(i).flatMap(_ -> isEvenT(t, i - 1));
  }

  /** a body given a Tick, at a fresh level */
  interface Ticked<L, A> {
    <M extends L> Comp<A, M> in(Tick<M> t);
  }

  /** a handler of Tick answering in place: the capability its value, the handler there all the same */
  static <L, A> Comp<A, L> ticking(Ticked<L, A> body) {
    return Comp.handle(new Comp.Body<L, A, A>() {
      public <M extends L> Comp<A, M> in(Prompt<L, M, A> p) {
        return body.in(Comp::pure);
      }
    }, Comp::pure);
  }

  /** the clause written out, {@code k(a)}: a capture each */
  static <L, A> Comp<A, L> tickingK(Ticked<L, A> body) {
    return Comp.handle(new Comp.Body<L, A, A>() {
      public <M extends L> Comp<A, M> in(Prompt<L, M, A> p) {
        return body.in(a -> p.shift0(k -> k.apply(a)));
      }
    }, Comp::pure);
  }

  /** {@code d} handlers of another effect, answering, never asked, around the ticks */
  <L> Comp<Boolean, L> skips(Tick<L> t, int d) {
    if (d == 0) return isEvenT(t, n);
    return Comp.handle(new Comp.Body<L, Boolean, Boolean>() {
      public <M extends L> Comp<Boolean, M> in(Prompt<L, M, Boolean> p) {
        return skips(t.at(), d - 1);
      }
    }, Comp::pure);
  }

  Ticked<Top, Boolean> skipping(int d) {
    return new Ticked<>() {
      public <M extends Top> Comp<Boolean, M> in(Tick<M> t) {
        return skips(t, d);
      }
    };
  }

  @Benchmark
  public boolean answered() {
    return Comp.run(ticking(skipping(0)));
  }

  @Benchmark
  public boolean general() {
    return Comp.run(tickingK(skipping(0)));
  }

  @Benchmark
  public boolean forwarded() {
    return Comp.run(ticking(skipping(1)));
  }

  @Benchmark
  public boolean forwarded16() {
    return Comp.run(ticking(skipping(16)));
  }

  @Benchmark
  public boolean forwarded4() {
    return Comp.run(ticking(skipping(4)));
  }

  /** the general clause across 1 and 16 handlers: okay-core's forwarded are answering */
  @Benchmark
  public boolean forwardedGeneral() {
    return Comp.run(tickingK(skipping(1)));
  }

  @Benchmark
  public boolean forwardedGeneral16() {
    return Comp.run(tickingK(skipping(16)));
  }

  static <L> Comp<Integer, L> counting(State<L, Integer> st, int i) {
    return i == 0 ? st.get() : st.get().flatMap(x -> st.put(x + 1)).flatMap(_ -> counting(st, i - 1));
  }

  State.Body<Top, Integer, Integer> counting() {
    return new State.Body<>() {
      public <M extends Top> Comp<Integer, M> in(State<M, Integer> st) {
        return counting(st, n);
      }
    };
  }

  @Benchmark
  public int state() {
    return Comp.run(State.cell(0, counting())).state();
  }

  @Benchmark
  public int stateFunctional() {
    return Comp.run(State.state(0, counting())).state();
  }

  static <L> Comp<Void, L> telling(Writer<L, Integer> w, int i) {
    return i == 0 ? unit() : w.tell(i).flatMap(_ -> telling(w, i - 1));
  }

  @Benchmark
  public int writer() {
    return Comp.run(Writer.writer(new Writer.Body<Top, Integer, Void>() {
      public <M extends Top> Comp<Void, M> in(Writer<M, Integer> w) {
        return telling(w, n);
      }
    })).log().size();
  }

  static <L> Comp<Integer, L> raising(int i, int acc) {
    if (i == 0) return Comp.pure(acc);
    return Throws.attempt(new Throws.Body<L, String, Integer>() {
      public <M extends L> Comp<Integer, M> in(Throws<M, String> t) {
        return t.raise("no");
      }
    }).flatMap(r -> raising(i - 1, acc + switch (r) {
      case Throws.Result.Ok<String, Integer>(var a) -> a;
      case Throws.Result.Failed<String, Integer>(var e) -> e.length();
    }));
  }

  @Benchmark
  public int throwing() {
    return Comp.run(raising(n, 0));
  }

  @Benchmark
  public int choose() {
    return Comp.run(Choose.all(new Choose.Body<Top, Integer>() {
      public <M extends Top> Comp<Integer, M> in(Choose<M> ch) {
        return ch.among(range).map(x -> x + 1);
      }
    })).size();
  }

  static <L> Comp<Void, L> fibs(Emit<L, Long> e, int i, long a, long b) {
    return i == 0 ? unit() : e.emit(a).flatMap(_ -> fibs(e, i - 1, b, a + b));
  }

  @Benchmark
  public long collect() {
    long sum = 0;
    for (long x : Comp.run(Emit.collect(new Emit.Body<Top, Long, Void>() {
      public <M extends Top> Comp<Void, M> in(Emit<M, Long> e) {
        return fibs(e, n, 0, 1);
      }
    }))) sum += x;
    return sum;
  }

  @Benchmark
  public int leftMaps() {
    Comp<Integer, Top> p = Comp.pure(0);
    for (int i = 0; i < n; i++) p = p.map(x -> x + 1);
    return Comp.run(p);
  }

  static <L> Comp<Integer, L> rightMapping(int i) {
    return i == 0 ? Comp.pure(0) : Comp.<L, Integer>delay(() -> rightMapping(i - 1)).map(x -> x + 1);
  }

  @Benchmark
  public int rightMaps() {
    return Comp.run(rightMapping(n));
  }

  static <L> Comp<Integer, L> shots(Function<Void, Comp<Integer, L>> k, int i, int acc) {
    return i == 0 ? Comp.pure(acc) : k.apply(null).flatMap(r -> shots(k, i - 1, acc + r));
  }

  /** a shift up to {@code p}, k {@code times} times, the rest {@code depth} maps */
  static <L, M extends L> Comp<Integer, M> captured(Prompt<L, M, Integer> p, int depth, int times) {
    Comp<Integer, M> c = p.<Void>shift(k -> shots(k, times, 0).at()).map(_ -> 1);
    for (int i = 0; i < depth; i++) c = c.map(x -> x + 1);
    return c;
  }

  static Comp.Body<Top, Integer, Integer> capturing(int depth, int times) {
    return new Comp.Body<>() {
      public <M extends Top> Comp<Integer, M> in(Prompt<Top, M, Integer> p) {
        return captured(p, depth, times);
      }
    };
  }

  @Benchmark
  public int capture() {
    return Comp.run(Comp.reset(capturing(16, 8)));
  }

  @Benchmark
  public int captureShallow() {
    return Comp.run(Comp.reset(capturing(1, 8)));
  }

  /** a shift to a handler's prompt: k the rest with the handler */
  @Benchmark
  public int captureHandled() {
    return Comp.run(Comp.handle(capturing(16, 8), Comp::pure));
  }

  @Benchmark
  public int captureHandledShallow() {
    return Comp.run(Comp.handle(capturing(1, 8), Comp::pure));
  }

  @Benchmark
  public int captureDeep() {
    return Comp.run(Comp.reset(capturing(256, 8)));
  }

  /** captureDeep's maps boxing by the constructor, {@code new Integer(x + 1)}, not by {@code Integer.valueOf}: every
   * map allocates, the cache never asked */
  @SuppressWarnings({"removal", "deprecation"})
  static <L, M extends L> Comp<Integer, M> capturedNew(Prompt<L, M, Integer> p, int depth, int times) {
    Comp<Integer, M> c = p.<Void>shift(k -> shots(k, times, 0).at()).map(_ -> 1);
    for (int i = 0; i < depth; i++) c = c.map(x -> new Integer(x.intValue() + 1));
    return c;
  }

  @Benchmark
  public int captureDeepNew() {
    return Comp.run(Comp.reset(new Comp.Body<Top, Integer, Integer>() {
      public <M extends Top> Comp<Integer, M> in(Prompt<Top, M, Integer> p) {
        return capturedNew(p, 256, 8);
      }
    }));
  }

  static <L> Comp<Int, L> shotsInt(Function<Void, Comp<Int, L>> k, int i, int acc) {
    return i == 0 ? Comp.pure(new Int(acc)) : k.apply(null).flatMap(r -> shotsInt(k, i - 1, acc + r.value()));
  }

  /** a box of one's own: no cache, not deprecated — every map a plain allocation */
  record Int(int value) {}

  static <L, M extends L> Comp<Int, M> capturedRecord(Prompt<L, M, Int> p, int depth, int times) {
    Comp<Int, M> c = p.<Void>shift(k -> shotsInt(k, times, 0).at()).map(_ -> new Int(1));
    for (int i = 0; i < depth; i++) c = c.map(x -> new Int(x.value() + 1));
    return c;
  }

  /** capture's program, its maps over a record: no Integer.valueOf on the way */
  @Benchmark
  public int captureRecord() {
    return Comp.run(Comp.reset(new Comp.Body<Top, Int, Int>() {
      public <M extends Top> Comp<Int, M> in(Prompt<Top, M, Int> p) {
        return capturedRecord(p, 16, 8);
      }
    })).value();
  }

  @Benchmark
  public int captureDeepRecord() {
    return Comp.run(Comp.reset(new Comp.Body<Top, Int, Int>() {
      public <M extends Top> Comp<Int, M> in(Prompt<Top, M, Int> p) {
        return capturedRecord(p, 256, 8);
      }
    })).value();
  }

  @Benchmark
  public int captureOnce() {
    return Comp.run(Comp.reset(capturing(1, 1)));
  }

  static <L> Comp<Boolean, L> evenDelayed(int i) {
    return i == 0 ? Comp.pure(true) : Comp.delay(() -> oddDelayed(i - 1));
  }

  static <L> Comp<Boolean, L> oddDelayed(int i) {
    return i == 0 ? Comp.pure(false) : Comp.delay(() -> evenDelayed(i - 1));
  }

  @Benchmark
  public boolean tailcallDelay() {
    return Comp.run(evenDelayed(n));
  }

  @Benchmark
  public int leftBinds() {
    Comp<Integer, Top> p = Comp.pure(0);
    for (int i = 0; i < n; i++) p = p.flatMap(x -> Comp.pure(x + 1));
    return Comp.run(p);
  }

  static <L> Comp<Long, L> sums(int i, long a, long b, long acc) {
    return i == 0 ? Comp.pure(acc) : MonadsBenchmark.<L>unit().flatMap(_ -> sums(i - 1, b, a + b, acc + a));
  }

  @Benchmark
  public long fib() {
    return Comp.run(sums(n, 0, 1, 0));
  }

  static boolean safe(int col, List<Integer> placed) {
    for (int d = 0; d < placed.size(); d++) {
      int c = placed.get(d);
      if (c == col || Math.abs(c - col) == d + 1) return false;
    }
    return true;
  }

  static <L> Comp<List<Integer>, L> place(Choose<L> ch, int row, List<Integer> placed) {
    if (row == 8) return Comp.pure(placed);
    List<Integer> cols = new ArrayList<>();
    for (int col = 0; col < 8; col++) if (safe(col, placed)) cols.add(col);
    return ch.among(cols).flatMap(col -> {
      List<Integer> next = new ArrayList<>(placed.size() + 1);
      next.add(col);
      next.addAll(placed);
      return place(ch, row + 1, next);
    });
  }

  /** the 92 solutions of 8 queens — not divided by n */
  @Benchmark
  public int queens() {
    return Comp.run(Choose.all(new Choose.Body<Top, List<Integer>>() {
      public <M extends Top> Comp<List<Integer>, M> in(Choose<M> ch) {
        return place(ch, 0, List.of());
      }
    })).size();
  }

  static <L> Comp<Void, L> forever(Emit<L, Long> e, long a, long b) {
    return e.emit(a).flatMap(_ -> forever(e, b, a + b));
  }

  /** n elements of an infinite generator pulled, each a run of the rest */
  @Benchmark
  public long generate() {
    Iterator<Long> it = Emit.iterator(new Emit.Body<Top, Long, Void>() {
      public <M extends Top> Comp<Void, M> in(Emit<M, Long> e) {
        return forever(e, 0, 1);
      }
    });
    long sum = 0;
    for (int i = 0; i < n; i++) sum += it.next();
    return sum;
  }
}
