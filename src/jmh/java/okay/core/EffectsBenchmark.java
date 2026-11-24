package okay.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okay.core.Comp.Top;
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

/** okay-core's EffectsBenchmark on {@link Comp}, {@code n} operations each:
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
  public int stateGetPut() {
    return Comp.run(State.state(0, counting())).state();
  }

  @Benchmark
  public int stateCellGetPut() {
    return Comp.run(State.cell(0, counting())).state();
  }

  static <L> Comp<Void, L> telling(Writer<L, Integer> w, int i) {
    return i == 0 ? Comp.pure(null) : w.tell(i).flatMap(_ -> telling(w, i - 1));
  }

  @Benchmark
  public int writerTell() {
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
  public int throwsRaise() {
    return Comp.run(raising(n, 0));
  }

  @Benchmark
  public int chooseAmong() {
    return Comp.run(Choose.all(new Choose.Body<Top, Integer>() {
      public <M extends Top> Comp<Integer, M> in(Choose<M> ch) {
        return ch.among(range).map(x -> x + 1);
      }
    })).size();
  }
}
