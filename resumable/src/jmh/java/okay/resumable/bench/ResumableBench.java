package okay.resumable.bench;

import static okay.rows.Effects.defer;
import static okay.rows.Effects.perform;
import static okay.rows.Effects.pure;
import static okay.rows.Effects.run;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import okay.direct.Answer;
import okay.direct.Effect;
import okay.direct.Handler;
import okay.direct.Resume;
import okay.direct.TailHandler;
import okay.direct.loom.LoomEffects;
import okay.resumable.Counter;
import okay.resumable.Direct;
import okay.resumable.Input;
import okay.resumable.Pick;
import okay.rows.Comp;
import okay.rows.Eff;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/** The three tiers on the same programs, against okay.rows and okay-direct's Loom implementation: ns an operation */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class ResumableBench {
  static final int N = 1000;
  static final Direct.Row1<Counter> COUNTER = Direct.row(Counter.class);
  static final Direct.Row1<Input> INPUT = Direct.row(Input.class);
  static final Direct.Row1<Pick> PICK = Direct.row(Pick.class);
  int x = 3;

  static java.util.Iterator<Integer> ones() {
    return IntStream.generate(() -> 1).iterator();
  }

  // ---- a state loop: in place

  @Benchmark
  @OperationsPerInvocation(2 * N)
  public Object stateDirectInPlace() {
    return run(Counter.run(0, COUNTER.of(() -> Programs.count(N))));
  }

  static Comp<Integer, Eff<Counter, ?>> countRows(int n) {
    if (n == 0) return perform(new Counter.Get());
    return perform(new Counter.Get()).flatMap(s -> perform(new Counter.Put(s + 1)))
        .flatMap(done -> defer(() -> countRows(n - 1)));
  }

  @Benchmark
  @OperationsPerInvocation(2 * N)
  public Object stateRows() {
    return run(Counter.run(0, countRows(N)));
  }

  sealed interface St {
    record Get() implements St, Effect.Op<St, Integer> {}
    record Put(int value) implements St, Effect.Op<St, Void> {}
  }

  static final class StateTail implements TailHandler<St, Integer, Integer> {
    int state;

    public Integer value(Integer a) { return a; }

    public void operation(St op, Answer<St> k) {
      switch (op) {
        case St.Get g -> k.answer(g, state);
        case St.Put p -> {
          state = p.value();
          k.answer(p, null);
        }
      }
    }
  }

  @Benchmark
  @OperationsPerInvocation(2 * N)
  public Object stateOkayDirectLoom() {
    return new LoomEffects().handle(new StateTail(), (Effect<St> st) -> {
      for (int i = 0; i < N; i++) st.perform(new St.Put(st.perform(new St.Get()) + 1));
      return st.perform(new St.Get());
    });
  }

  // ---- a general handler that resumes once

  @Benchmark
  @OperationsPerInvocation(N)
  public Object onceDirectLoom() {
    return run(Input.once(ones(), INPUT.of(() -> Programs.sum(N))));
  }

  @Benchmark
  @OperationsPerInvocation(N)
  public Object onceDirectSavedFrames() {
    return run(Input.many(ones(), INPUT.of(() -> Programs.sum(N))));
  }

  @Benchmark
  @OperationsPerInvocation(N)
  public Object onceDirectLoomDeep() {
    return run(Input.once(ones(), INPUT.of(() -> Programs.sumDeep(N))));
  }

  @Benchmark
  @OperationsPerInvocation(N)
  public Object onceDirectSavedFramesDeep() {
    return run(Input.many(ones(), INPUT.of(() -> Programs.sumDeep(N))));
  }

  static Comp<Integer, Eff<Input, ?>> sumRows(int n, int s) {
    if (n == 0) return pure(s);
    return perform(new Input.Next()).flatMap(x -> defer(() -> sumRows(n - 1, s + x)));
  }

  @Benchmark
  @OperationsPerInvocation(N)
  public Object onceRows() {
    return run(Input.once(ones(), sumRows(N, 0)));
  }

  sealed interface In {
    record Next() implements In, Effect.Op<In, Integer> {}
  }

  @Benchmark
  @OperationsPerInvocation(N)
  public Object onceOkayDirectLoom() {
    return new LoomEffects().handle(new Handler<In, Integer, Integer, RuntimeException>() {
      public Integer value(Integer a) { return a; }
      public Integer operation(In op, Resume<In, Integer, RuntimeException> k) {
        return switch (op) {
          case In.Next n -> k.resume(n, 1);
        };
      }
    }, (Effect<In> in) -> {
      int s = 0;
      for (int i = 0; i < N; i++) s += in.perform(new In.Next());
      return s;
    });
  }

  // ---- a choice: every path, multi-shot

  @Benchmark
  @OperationsPerInvocation(1000)
  public List<Integer> chooseDirect() {
    return run(Pick.all(PICK.of(Programs::choose)));
  }

  static final List<Integer> TEN = List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);

  @Benchmark
  @OperationsPerInvocation(1000)
  public List<Integer> chooseRows() {
    Comp<Integer, Eff<Pick, ?>> p = perform(new Pick.Among(TEN)).flatMap(a -> perform(new Pick.Among(TEN))
        .flatMap(b -> perform(new Pick.Among(TEN)).map(c -> a * 100 + b * 10 + c)));
    return run(Pick.all(p));
  }

  // ---- no suspension: a transformed method against itself untransformed

  @Benchmark
  @OperationsPerInvocation(N)
  public int noSuspensionTransformed() throws Counter {
    int s = 0;
    for (int i = 0; i < N; i++) s = Programs.plus((s ^ i) & 0xFFFF, x) * 31;
    return s;
  }

  @Benchmark
  @OperationsPerInvocation(N)
  public int noSuspensionPlain() throws Counter {
    int s = 0;
    for (int i = 0; i < N; i++) s = Plain.plus((s ^ i) & 0xFFFF, x) * 31;
    return s;
  }
}
