package okay.rows.bench;

import static okay.rows.Effects.*;

import java.util.List;
import okay.rows.Audit;
import okay.rows.Cell;
import okay.rows.Comp;
import okay.effect.Eff;
import okay.effect.Operation;
import okay.rows.Handler;
import okay.rows.InPlace;
import okay.rows.K;
import okay.rows.Var;
import okay.rows.Interceptor;
import okay.effect.Pure;
import okay.effect.Sensitive;
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

  /** a GADT of one operation, asked in a loop: answered by a handler in place, by a clause that only resumes
   * (in place too), by a clause that goes on after the rest and by a general handler (the rest taken) */
  sealed interface Tick<T> extends Operation<T, Tick<?>> {
    record Next() implements Tick<Integer> {}
  }

  static Comp<Integer, Eff<Tick<?>, ?>> ticks(int n, int sum) {
    return n == 0 ? pure(sum) : perform(new Tick.Next()).flatMap(x -> ticks(n - 1, sum + x));
  }

  static final InPlace<Tick<?>, Void, Integer, Integer, Pure> TICK_IN_PLACE = new InPlace<>() {
    public Comp<Integer, Pure> ret(Void state, Integer a) { return pure(a); }
    public Cell.Answered answer(Cell<Void> state, Tick<?> op) {
      return switch (op) {
        case Tick.Next next -> state.answer(next, 1);
      };
    }
  };

  static final Handler<Tick<?>, Integer, Integer, Pure> TICK_GENERAL = new Handler<>() {
    public Comp<Integer, Pure> ret(Integer a) { return pure(a); }
    public Comp<Integer, Pure> on(Tick<?> op, K<Integer, Pure> k) {
      return switch (op) {
        case Tick.Next next -> k.resume(next, 1);
      };
    }
  };

  /** the state lane's loop over a GADT, its handler one function with a state */
  sealed interface Counter<T> extends Operation<T, Counter<?>> {
    record Get() implements Counter<Integer> {}
    record Put(int value) implements Counter<Void> {}
  }

  static Comp<Integer, Eff<Counter<?>, ?>> counted(int n) {
    if (n == 0) return perform(new Counter.Get());
    return perform(new Counter.Get()).flatMap(s -> perform(new Counter.Put(s + 1))).flatMap(done -> counted(n - 1));
  }

  // ---- blocks beside the same programs as chains: nanoseconds a QUESTION

  static <A> Comp<A, Pure> ticked(Comp<A, ? super Eff<Tick<?>, Pure>> p) {
    return answering(op -> switch (op) {
      case Tick.Next next -> next.answer(1);
    }, p);
  }

  /** two questions that need nothing, a value of both: as a chain, as a block */
  static Comp<Integer, Eff<Tick<?>, ?>> chain2(int n, int sum) {
    return n == 0 ? pure(sum) : perform(new Tick.Next()).flatMap(a ->
        perform(new Tick.Next()).flatMap(b -> chain2(n - 1, sum + a + b)));
  }

  static Comp<Integer, Eff<Tick<?>, ?>> block2(int n, int sum) {
    if (n == 0) return pure(sum);
    Comp<Integer, Eff<Tick<?>, ?>> block = together(t -> {
      Var<Integer> a = t.perform(new Tick.Next());
      Var<Integer> b = t.perform(new Tick.Next());
      return t.yield(a, b, Integer::sum);
    });
    return block.flatMap(x -> block2(n - 1, sum + x));
  }

  /** a question that needs the answer of another: two rounds */
  static Comp<Integer, Eff<Tick<?>, ?>> blockThen(int n, int sum) {
    if (n == 0) return pure(sum);
    Comp<Integer, Eff<Tick<?>, ?>> block = together(t -> {
      Var<Integer> a = t.perform(new Tick.Next());
      Var<Integer> b = a.then(x -> t.perform(new Tick.Next()));
      return t.yield(a, b, Integer::sum);
    });
    return block.flatMap(x -> blockThen(n - 1, sum + x));
  }

  /** a hundred questions: a chain of them, a plain loop gathered, an each over a list */
  static final List<Integer> HUNDRED = java.util.stream.IntStream.range(0, 100).boxed().toList();

  static Comp<Integer, Eff<Tick<?>, ?>> chain100(int n, int sum) {
    if (n == 0) return pure(sum);
    Comp<Integer, Eff<Tick<?>, ?>> hundred = fold(HUNDRED, 0, (acc, i) -> perform(new Tick.Next()).map(x -> acc + x));
    return hundred.flatMap(x -> chain100(n - 1, sum + x));
  }

  static Comp<Integer, Eff<Tick<?>, ?>> gather100(int n, int sum) {
    if (n == 0) return pure(sum);
    Comp<List<Integer>, Eff<Tick<?>, ?>> hundred = gather(g -> {
      for (int i = 0; i < 100; i++) g.perform(new Tick.Next());
    });
    return hundred.flatMap(xs -> gather100(n - 1, sum + xs.size()));
  }

  static Comp<Integer, Eff<Tick<?>, ?>> each100(int n, int sum) {
    if (n == 0) return pure(sum);
    Comp<Integer, Eff<Tick<?>, ?>> hundred = together(t -> {
      Var<List<Integer>> xs = t.each(t.value(HUNDRED), i -> t.perform(new Tick.Next()));
      return t.yield(xs, List::size);
    });
    return hundred.flatMap(x -> each100(n - 1, sum + x));
  }

  static void measure(String name, int ops, Runnable body) {
    long best = Long.MAX_VALUE;
    for (int round = 0; round < 7; round++) {
      long start = System.nanoTime();
      body.run();
      best = Math.min(best, System.nanoTime() - start);
    }
    System.out.println(String.format("%-10s %7.1f ns/op", name, (double) best / ops));
  }

  public static void main(String[] args) {
    int n = 1_000_000;
    measure("state", 2 * n, () -> run(State.run(0, count(n))));
    measure("reader3", n, () -> run(Reader.run(1, State.run(0, State.run("", Writer.run(up(up(up(asks(n, 0))))))))));
    measure("choose", 1 << 18, () -> run(Choose.all(tree(18))).size());
    measure("binds", n, () -> run(binds(n, 0)));
    measure("audited", n, () -> run(Audit.handle(TICKED, null, Audit.audit(PASSING, meter(n, 0)))));
    measure("inplace", n, () -> run(handle(Tick.class, TICK_IN_PLACE, null, ticks(n, 0))));
    measure("answering", n, () -> run(answering(op -> switch (op) {
      case Tick.Next next -> next.answer(1);
    }, ticks(n, 0))));
    measure("clause", n, () -> run(handle((op, k) -> switch (op) {
      case Tick.Next next -> k.resume(next, 1);
    }, ticks(n, 0))));
    measure("general", n, () -> run(handle(Tick.class, TICK_GENERAL, ticks(n, 0))));
    measure("stateful", 2 * n, () -> run(handle(0,
        (state, op, k) -> switch (op) {
          case Counter.Get g -> k.resume(g, state);
          case Counter.Put p -> k.resume(p, null, p.value());
        }, counted(n))));
    int b = 200_000;
    measure("chain2", 2 * b, () -> run(ticked(chain2(b, 0))));
    measure("block2", 2 * b, () -> run(ticked(block2(b, 0))));
    measure("blockThen", 2 * b, () -> run(ticked(blockThen(b, 0))));
    int h = 5_000;
    measure("chain100", 100 * h, () -> run(ticked(chain100(h, 0))));
    measure("gather100", 100 * h, () -> run(ticked(gather100(h, 0))));
    measure("each100", 100 * h, () -> run(ticked(each100(h, 0))));
    int m = 100_000;
    measure("after", m, () -> run(handle((op, k) -> switch (op) {
      case Tick.Next next -> k.resume(next, 1).map(r -> r + 1);
    }, ticks(m, 0))));
  }
}
