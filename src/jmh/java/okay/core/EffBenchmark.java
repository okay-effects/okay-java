package okay.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okay.core.Eff.Top;
import okay.std.Choose;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/** okay-core's EffBenchmark on {@link Eff}: {@code n} operations of {@code Tick}, each answered {@code k(a)} by its
 * handler, crossing {@code crossed} handlers of {@code Skip}. {@code clause}: {@code general}, a capture each;
 * {@code answering}, the capability its own handler, no prompt. And queens, a resumption per safe square */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class EffBenchmark {

  @Param("10000")
  public int n;

  @Param({"0", "1", "4"})
  public int crossed;

  @Param({"general", "answering"})
  public String clause;

  interface Tick<L> {
    Eff<L, Integer> tick(int a);

    @SuppressWarnings("unchecked")
    default <N extends L> Tick<N> at() {
      return (Tick<N>) (Tick<?>) this;
    }
  }

  static <L> Eff<L, Integer> ticks(Tick<L> t, int i, int acc) {
    return i == 0 ? Eff.pure(acc) : t.tick(1).flatMap(x -> ticks(t, i - 1, acc + x));
  }

  /** a handler of Tick: the body at a fresh level, given the capability */
  Eff<Top, Integer> ticking() {
    return Eff.handle(new Eff.Body<Top, Integer, Integer>() {
      public <M extends Top> Eff<M, Integer> in(Prompt<Top, M, Integer> p) {
        Tick<M> t = clause.equals("answering") ? Eff::pure : a -> p.shift0(k -> k.apply(a));
        return nest(t, crossed);
      }
    }, Eff::pure);
  }

  /** {@code d} handlers of another effect, each a general clause, never asked */
  <L> Eff<L, Integer> nest(Tick<L> t, int d) {
    if (d == 0) return ticks(t, n, 0);
    return Eff.handle(new Eff.Body<L, Integer, Integer>() {
      public <M extends L> Eff<M, Integer> in(Prompt<L, M, Integer> p) {
        return nest(t.at(), d - 1);
      }
    }, Eff::pure);
  }

  @Benchmark
  public int opsEff() {
    return Eff.run(ticking());
  }

  @State(Scope.Thread)
  @BenchmarkMode(Mode.AverageTime)
  @OutputTimeUnit(TimeUnit.MICROSECONDS)
  @Warmup(iterations = 3, time = 1)
  @Measurement(iterations = 5, time = 1)
  @Fork(2)
  public static class Queens {
    @Param("8")
    public int size;

    <L> Eff<L, List<Integer>> place(Choose<L> ch, int row, List<Integer> placed) {
      if (row == size) return Eff.pure(placed);
      List<Integer> safe = new ArrayList<>();
      for (int col = 0; col < size; col++) if (safe(col, placed)) safe.add(col);
      return ch.among(safe).flatMap(col -> {
        List<Integer> next = new ArrayList<>(placed.size() + 1);
        next.add(col);
        next.addAll(placed);
        return place(ch, row + 1, next);
      });
    }

    static boolean safe(int col, List<Integer> placed) {
      for (int d = 0; d < placed.size(); d++) {
        int c = placed.get(d);
        if (c == col || Math.abs(c - col) == d + 1) return false;
      }
      return true;
    }

    @Benchmark
    public int queensEff() {
      return Eff.run(Choose.all(new Choose.Body<Top, List<Integer>>() {
        public <M extends Top> Eff<M, List<Integer>> in(Choose<M> ch) {
          return place(ch, 0, List.of());
        }
      })).size();
    }
  }
}
