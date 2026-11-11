package okay.rows.bench;

import static okay.rows.Effects.*;

import java.util.List;
import okay.rows.Audit;
import okay.rows.Cell;
import okay.rows.Comp;
import okay.rows.Eff;
import okay.rows.InPlace;
import okay.rows.Interceptor;
import okay.rows.Pure;
import okay.rows.Sensitive;
import okay.rows.std.Choose;
import okay.rows.std.Reader;
import okay.rows.std.State;
import okay.rows.std.Writer;

/** A rough measure, the same in the three languages: nanoseconds an operation, the best of several rounds.
 * {@code state}: get and put in a loop. {@code reader3}: an ask answered three handlers away. {@code choose}: a
 * continuation taken twice at every step of a tree. {@code binds}: a loop of nothing but binds. {@code audited}: a
 * loop of operations of a sensitive effect, each passed by its audit to a handler in place. */
public final class Bench {
  static Comp<Integer, Eff<State<Integer>, ?>> count(int n) {
    if (n == 0) return State.get();
    return State.<Integer>get().flatMap(s -> State.put(s + 1)).flatMap(done -> count(n - 1));
  }

  static Comp<Integer, Eff<Reader<Integer>, ?>> asks(int n, int sum) {
    return n == 0 ? pure(sum) : Reader.<Integer>ask().flatMap(x -> asks(n - 1, sum + x));
  }

  static Comp<Integer, Eff<Choose, ?>> tree(int depth) {
    return depth == 0 ? pure(1) : Choose.among(List.of(0, 1)).flatMap(x -> tree(depth - 1));
  }

  static <R> Comp<Integer, R> binds(int n, int sum) {
    return n == 0 ? pure(sum) : pure(1).flatMap(x -> binds(n - 1, sum + x));
  }

  /** a sensitive effect: a tick of a meter */
  sealed interface Meter {
    record Tick() implements Meter, Sensitive.Op<Meter, Integer, Tick> {}
  }

  static Comp<Integer, Sensitive<Meter, ?>> meter(int n, int sum) {
    return n == 0 ? pure(sum) : Audit.perform(new Meter.Tick()).flatMap(x -> meter(n - 1, sum + x));
  }

  static final Interceptor<Meter, Integer, Pure> PASSING = (op, pass, k) -> switch (op) {
    case Meter.Tick tick -> k.after(tick, pass.pass(tick));
  };

  static final InPlace<Meter, Void, Integer, Integer, Pure> TICKED = new InPlace<>() {
    public Comp<Integer, Pure> ret(Void state, Integer a) { return pure(a); }
    public Cell.Answered answer(Cell<Void> state, Meter op) {
      return switch (op) {
        case Meter.Tick tick -> state.answer(tick, 1);
      };
    }
  };

  static void measure(String name, int ops, Runnable body) {
    long best = Long.MAX_VALUE;
    for (int round = 0; round < 7; round++) {
      long start = System.nanoTime();
      body.run();
      best = Math.min(best, System.nanoTime() - start);
    }
    System.out.println(String.format("%-8s %7.1f ns/op", name, (double) best / ops));
  }

  public static void main(String[] args) {
    int n = 1_000_000;
    measure("state", 2 * n, () -> run(State.run(0, count(n))));
    measure("reader3", n, () -> run(Reader.run(1, State.run(0, State.run("", Writer.run(up(up(up(asks(n, 0))))))))));
    measure("choose", 1 << 18, () -> run(Choose.all(tree(18))).size());
    measure("binds", n, () -> run(binds(n, 0)));
    measure("audited", n, () -> run(Audit.handle(TICKED, null, Audit.audit(PASSING, meter(n, 0)))));
  }
}
