package okay.core;

import java.util.List;
import java.util.concurrent.TimeUnit;
import okay.core.Eff.Top;
import okay.std.Choose;
import okay.std.Reader;
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
import org.openjdk.jmh.annotations.Warmup;

/** not one effect in a loop but a program of several, as a program is: {@code n} orders, each priced from a reader's
 * base, its supplier the first of three that fits (a choice), a failure for some (a throw, caught per order), the
 * total a state and the failures a log. A handler of its own for each order's throw and choice, the others around
 * them all. Many functions at each of the machine's call sites, as in a program, not one */
@org.openjdk.jmh.annotations.State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class MixedBenchmark {
  @Param("1000")
  public int n;

  static final List<Integer> SUPPLIERS = List.of(0, 1, 2);

  /** one order: its price, or its failure in the log; the total moved by its price */
  static <L> Eff<L, Void> order(Reader<L, Integer> r, State<L, Integer> st, Writer<L, String> w, int i) {
    return r.ask().flatMap(base -> Throws.attempt(new Throws.Body<L, String, Integer>() {
      public <M extends L> Eff<M, Integer> in(Throws<M, String> t) {
        if (i % 13 == 0) return t.raise("no stock");
        return Choose.first(new Choose.Body<M, Integer>() {
          public <N extends M> Eff<N, Integer> in(Choose<N> ch) {
            return ch.among(SUPPLIERS).flatMap(s -> (i + s) % 3 == 0 ? Eff.pure(base + i % 7 + s) : ch.fail());
          }
        }).map(o -> o.orElse(0));
      }
    })).flatMap(res -> switch (res) {
      case Throws.Result.Ok<String, Integer>(var p) -> st.modify(x -> x + p);
      case Throws.Result.Failed<String, Integer>(var e) -> w.tell(e);
    });
  }

  static <L> Eff<L, Void> orders(Reader<L, Integer> r, State<L, Integer> st, Writer<L, String> w, int i, int n) {
    return i == n ? Eff.pure(null) : order(r, st, w, i).flatMap(_ -> orders(r, st, w, i + 1, n));
  }

  @Benchmark
  public int orders() {
    Writer.Log<String, State.Final<Integer, Void>> out = Eff.run(Reader.reader(100, new Reader.Body<Top, Integer,
        Writer.Log<String, State.Final<Integer, Void>>>() {
      public <M extends Top> Eff<M, Writer.Log<String, State.Final<Integer, Void>>> in(Reader<M, Integer> r) {
        return Writer.writer(new Writer.Body<M, String, State.Final<Integer, Void>>() {
          public <N extends M> Eff<N, State.Final<Integer, Void>> in(Writer<N, String> w) {
            return State.state(0, new State.Body<N, Integer, Void>() {
              public <O extends N> Eff<O, Void> in(State<O, Integer> st) {
                return orders(r.at(), st, w.at(), 0, n);
              }
            });
          }
        });
      }
    }));
    return out.value().state() + out.log().size();
  }
}
