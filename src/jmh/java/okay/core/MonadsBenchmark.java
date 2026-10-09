package okay.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
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

/** okay-core master's MonadsBenchmark on {@link Eff}, its programs and its names: `n` operations each but where
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

  static final Eff<Void> UNIT = Eff.pure(null);

  static Eff<Boolean> isEven(int i) {
    return i == 0 ? Eff.pure(true) : UNIT.flatMap(_ -> isOdd(i - 1));
  }

  static Eff<Boolean> isOdd(int i) {
    return i == 0 ? Eff.pure(false) : UNIT.flatMap(_ -> isEven(i - 1));
  }

  @Benchmark
  public boolean pure() {
    return Eff.run(isEven(n));
  }

  interface Tick {
    Eff<Integer> tick(int a);
  }

  static Eff<Boolean> isEvenT(Tick t, int i) {
    return i == 0 ? Eff.pure(true) : t.tick(i).flatMap(_ -> isOddT(t, i - 1));
  }

  static Eff<Boolean> isOddT(Tick t, int i) {
    return i == 0 ? Eff.pure(false) : t.tick(i).flatMap(_ -> isEvenT(t, i - 1));
  }

  /** a handler of Tick answering in place: the capability its value, the handler there all the same */
  static <A> Eff<A> ticking(Function<Tick, Eff<A>> body) {
    return Eff.handle((Prompt<A> _) -> body.apply(Eff::pure));
  }

  /** the clause written out, {@code k(a)}: a capture each */
  static <A> Eff<A> tickingK(Function<Tick, Eff<A>> body) {
    return Eff.handle((Prompt<A> p) -> body.apply(a -> p.shift0(k -> k.apply(a))));
  }

  /** a handler of another effect, answering, never asked */
  static <A> Eff<A> skipping(Eff<A> body) {
    return Eff.handle((Prompt<A> _) -> body);
  }

  @Benchmark
  public boolean answered() {
    return Eff.run(ticking(t -> isEvenT(t, n)));
  }

  @Benchmark
  public boolean general() {
    return Eff.run(tickingK(t -> isEvenT(t, n)));
  }

  @Benchmark
  public boolean forwarded() {
    return Eff.run(ticking(t -> skipping(isEvenT(t, n))));
  }

  Eff<Boolean> skips(Tick t, int d) {
    return d == 0 ? isEvenT(t, n) : skipping(skips(t, d - 1));
  }

  @Benchmark
  public boolean forwarded16() {
    return Eff.run(ticking(t -> skips(t, 16)));
  }

  @Benchmark
  public boolean forwarded4() {
    return Eff.run(ticking(t -> skips(t, 4)));
  }

  /** the general clause across 1 and 16 handlers: okay-core's forwarded are answering */
  @Benchmark
  public boolean forwardedGeneral() {
    return Eff.run(tickingK(t -> skipping(isEvenT(t, n))));
  }

  @Benchmark
  public boolean forwardedGeneral16() {
    return Eff.run(tickingK(t -> skips(t, 16)));
  }

  static Eff<Integer> counting(State<Integer> st, int i) {
    return i == 0 ? st.get() : st.get().flatMap(x -> st.put(x + 1)).flatMap(_ -> counting(st, i - 1));
  }

  @Benchmark
  public int state() {
    return Eff.run(State.<Integer, Integer>cell(0, st -> counting(st, n))).state();
  }

  @Benchmark
  public int stateFunctional() {
    return Eff.run(State.<Integer, Integer>state(0, st -> counting(st, n))).state();
  }

  static Eff<Void> telling(Writer<Integer> w, int i) {
    return i == 0 ? UNIT : w.tell(i).flatMap(_ -> telling(w, i - 1));
  }

  @Benchmark
  public int writer() {
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
  public int throwing() {
    return Eff.run(raising(n, 0));
  }

  @Benchmark
  public int choose() {
    return Eff.run(Choose.all(ch -> ch.among(range).map(x -> x + 1))).size();
  }

  static Eff<Void> fibs(Emit<Long> e, int i, long a, long b) {
    return i == 0 ? UNIT : e.emit(a).flatMap(_ -> fibs(e, i - 1, b, a + b));
  }

  @Benchmark
  public long collect() {
    long sum = 0;
    for (long x : Eff.run(Emit.<Long, Void>collect(e -> fibs(e, n, 0, 1)))) sum += x;
    return sum;
  }

  @Benchmark
  public int leftMaps() {
    Eff<Integer> p = Eff.pure(0);
    for (int i = 0; i < n; i++) p = p.map(x -> x + 1);
    return Eff.run(p);
  }

  static Eff<Integer> rightMapping(int i) {
    return i == 0 ? Eff.pure(0) : Eff.delay(() -> rightMapping(i - 1)).map(x -> x + 1);
  }

  @Benchmark
  public int rightMaps() {
    return Eff.run(rightMapping(n));
  }

  static Eff<Integer> shots(Function<Void, Eff<Integer>> k, int i, int acc) {
    return i == 0 ? Eff.pure(acc) : k.apply(null).flatMap(r -> shots(k, i - 1, acc + r));
  }

  /** a shift up to {@code p}, k {@code times} times, the rest {@code depth} maps */
  static Eff<Integer> captured(Prompt<Integer> p, int depth, int times) {
    Eff<Integer> c = p.<Void>shift(k -> shots(k, times, 0)).map(_ -> 1);
    for (int i = 0; i < depth; i++) c = c.map(x -> x + 1);
    return c;
  }

  @Benchmark
  public int capture() {
    return Eff.run(Eff.<Integer>reset(p -> captured(p, 16, 8)));
  }

  @Benchmark
  public int captureShallow() {
    return Eff.run(Eff.<Integer>reset(p -> captured(p, 1, 8)));
  }

  /** a shift to a handler's prompt: k the rest with the handler */
  @Benchmark
  public int captureHandled() {
    return Eff.run(Eff.<Integer, Integer>handle((Prompt<Integer> p) -> captured(p, 16, 8), Eff::pure));
  }

  @Benchmark
  public int captureHandledShallow() {
    return Eff.run(Eff.<Integer, Integer>handle((Prompt<Integer> p) -> captured(p, 1, 8), Eff::pure));
  }

  @Benchmark
  public int captureDeep() {
    return Eff.run(Eff.<Integer>reset(p -> captured(p, 256, 8)));
  }

  @Benchmark
  public int captureOnce() {
    return Eff.run(Eff.<Integer>reset(p -> captured(p, 1, 1)));
  }

  static Eff<Boolean> evenDelayed(int i) {
    return i == 0 ? Eff.pure(true) : Eff.delay(() -> oddDelayed(i - 1));
  }

  static Eff<Boolean> oddDelayed(int i) {
    return i == 0 ? Eff.pure(false) : Eff.delay(() -> evenDelayed(i - 1));
  }

  @Benchmark
  public boolean tailcallDelay() {
    return Eff.run(evenDelayed(n));
  }

  @Benchmark
  public int leftBinds() {
    Eff<Integer> p = Eff.pure(0);
    for (int i = 0; i < n; i++) p = p.flatMap(x -> Eff.pure(x + 1));
    return Eff.run(p);
  }

  static Eff<Long> sums(int i, long a, long b, long acc) {
    return i == 0 ? Eff.pure(acc) : UNIT.flatMap(_ -> sums(i - 1, b, a + b, acc + a));
  }

  @Benchmark
  public long fib() {
    return Eff.run(sums(n, 0, 1, 0));
  }

  static boolean safe(int col, List<Integer> placed) {
    for (int d = 0; d < placed.size(); d++) {
      int c = placed.get(d);
      if (c == col || Math.abs(c - col) == d + 1) return false;
    }
    return true;
  }

  static Eff<List<Integer>> place(Choose ch, int row, List<Integer> placed) {
    if (row == 8) return Eff.pure(placed);
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
    return Eff.run(Choose.all(ch -> place(ch, 0, List.of()))).size();
  }

  static Eff<Void> forever(Emit<Long> e, long a, long b) {
    return e.emit(a).flatMap(_ -> forever(e, b, a + b));
  }

  /** n elements of an infinite generator pulled, each a run of the rest */
  @Benchmark
  public long generate() {
    Iterator<Long> it = Emit.<Long, Void>iterator(e -> forever(e, 0, 1));
    long sum = 0;
    for (int i = 0; i < n; i++) sum += it.next();
    return sum;
  }
}
