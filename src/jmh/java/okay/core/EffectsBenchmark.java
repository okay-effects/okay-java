package okay.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okay.std.Choose;
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

/** okay-core's EffectsBenchmark on {@link Eff}, {@code n} operations each:
 *  - stateGetPut: get then put, n times — functional; stateCellGetPut: answered in place from a cell, as okay-core's
 *  - writerTell: n tells, the log built on the way back
 *  - throwsRaise: n handlers of attempt, each body raising at once
 *  - chooseAmong: one choice among n, every path */
@org.openjdk.jmh.annotations.State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class EffectsBenchmark {

  @Param("1000")
  public int n;

  List<Integer> range;

  @Setup
  public void setup() {
    range = new ArrayList<>();
    for (int i = 0; i < n; i++) range.add(i);
  }

  static Eff<Integer> counting(State<Integer> st, int i) {
    return i == 0 ? st.get() : st.get().flatMap(x -> st.put(x + 1)).flatMap(_ -> counting(st, i - 1));
  }

  @Benchmark
  public int stateGetPut() {
    return Eff.run(State.<Integer, Integer>state(0, st -> counting(st, n))).state();
  }

  @Benchmark
  public int stateCellGetPut() {
    return Eff.run(State.<Integer, Integer>cell(0, st -> counting(st, n))).state();
  }

  static Eff<Void> telling(Writer<Integer> w, int i) {
    return i == 0 ? Eff.pure(null) : w.tell(i).flatMap(_ -> telling(w, i - 1));
  }

  @Benchmark
  public int writerTell() {
    return Eff.run(Writer.<Integer, Void>writer(w -> telling(w, n))).log().size();
  }

  static Eff<Integer> raising(int i, int acc) {
    if (i == 0) return Eff.pure(acc);
    return Throws.<String, Integer>attempt(t -> t.raise("no")).flatMap(r -> raising(i - 1, acc + switch (r) {
      case Throws.Result.Ok<String, Integer>(var a) -> a;
      case Throws.Result.Failed<String, Integer>(var e) -> e.length();
    }));
  }

  @Benchmark
  public int throwsRaise() {
    return Eff.run(raising(n, 0));
  }

  @Benchmark
  public int chooseAmong() {
    return Eff.run(Choose.all(ch -> ch.among(range).map(x -> x + 1))).size();
  }
}
