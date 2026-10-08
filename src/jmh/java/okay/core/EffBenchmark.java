package okay.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
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

  interface Tick {
    Eff<Integer> tick(int a);
  }

  interface Skip {
    Eff<Integer> skip(int a);
  }

  static Eff<Integer> ticks(Tick t, int i, int acc) {
    return i == 0 ? Eff.pure(acc) : t.tick(1).flatMap(x -> ticks(t, i - 1, acc + x));
  }

  <A> Eff<A> ticking(Function<Tick, Eff<A>> body) {
    if (clause.equals("answering")) return body.apply(Eff::pure);
    return Eff.handle((Prompt<A> p) -> body.apply(a -> p.shift0(k -> k.apply(a))));
  }

  static <A> Eff<A> skipping(Function<Skip, Eff<A>> body) {
    return Eff.handle((Prompt<A> p) -> body.apply(a -> p.shift0(k -> k.apply(a))));
  }

  Eff<Integer> nest(Tick t, int d) {
    return d == 0 ? ticks(t, n, 0) : skipping(_ -> nest(t, d - 1));
  }

  @Benchmark
  public int opsEff() {
    return Eff.run(ticking(t -> nest(t, crossed)));
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

    Eff<List<Integer>> place(Choose ch, int row, List<Integer> placed) {
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
      return Eff.run(Choose.all(ch -> place(ch, 0, List.of()))).size();
    }
  }
}
