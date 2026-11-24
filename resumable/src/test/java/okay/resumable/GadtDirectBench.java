package okay.resumable;

import static okay.rows.Effects.handle;
import static okay.rows.Effects.run;

import okay.effect.Eff;
import okay.effect.Pure;
import okay.rows.Cell;
import okay.rows.Comp;
import okay.rows.InPlace;

/** A rough measure: nanoseconds an operation of a plain loop, under a clause (one function) and under a handler in
 * place. Run by hand: ./gradlew :okay-java-resumable:gadtDirectBench */
public final class GadtDirectBench {
  sealed interface Tick<T> extends Performed<T, Tick<?>, Tick.Fx> {
    final class Fx extends Suspend implements Marks<Tick<?>> {}
    record Next() implements Tick<Integer> {}
  }

  static long loop(int n) throws Tick.Fx {
    long sum = 0;
    for (int i = 0; i < n; i++) sum += Direct.perform(new Tick.Next());
    return sum;
  }

  static final InPlace<Tick<?>, Void, Long, Long, Pure> IN_PLACE = new InPlace<>() {
    public Comp<Long, Pure> ret(Void state, Long a) { return okay.rows.Effects.pure(a); }
    public Cell.Answered answer(Cell<Void> state, Tick<?> op) {
      return switch (op) {
        case Tick.Next next -> state.answer(next, 1);
      };
    }
  };

  static void measure(String name, int ops, Runnable body) {
    long best = Long.MAX_VALUE;
    for (int round = 0; round < 9; round++) {
      long start = System.nanoTime();
      body.run();
      best = Math.min(best, System.nanoTime() - start);
    }
    System.out.println(String.format("%-52s %9.1f ns/op", name, (double) best / ops));
  }

  /** the same loop in a class the plugin did not transform ({@link UntransformedGadt}) */
  static final InPlace<UntransformedGadt.Prices<?>, Void, Long, Long, Pure> PLAIN_IN_PLACE = new InPlace<>() {
    public Comp<Long, Pure> ret(Void state, Long a) { return okay.rows.Effects.pure(a); }
    public Cell.Answered answer(Cell<Void> state, UntransformedGadt.Prices<?> op) {
      return switch (op) {
        case UntransformedGadt.Prices.Price price -> state.answer(price, 1L);
        case UntransformedGadt.Prices.InStock s -> state.answer(s, true);
      };
    }
  };

  public static void main(String[] args) {
    int n = 1_000_000;
    measure("inplace", n, () -> run(handle(Tick.class, IN_PLACE, null,
        Direct.effects(Tick.Fx.class).of(() -> loop(n)))));
    measure("no plugin, inplace", n, () -> run(handle(UntransformedGadt.Prices.class, PLAIN_IN_PLACE, null,
        Direct.effects(UntransformedGadt.Prices.Fx.class).of(() -> UntransformedGadt.count(n)))));
    measure("no plugin, clause", n, () -> run(
        okay.rows.Effects.<UntransformedGadt.Prices<?>, Long, Pure>handle((op, k) -> switch (op) {
          case UntransformedGadt.Prices.Price price -> k.resume(price, 1L);
          case UntransformedGadt.Prices.InStock s -> k.resume(s, true);
        }, Direct.effects(UntransformedGadt.Prices.Fx.class).of(() -> UntransformedGadt.count(n)))));
    int m = 50_000;
    String stack = Direct.continuations().startsWith("the JVM") ? "a continuation" : "a virtual thread";
    measure("no plugin, the rest (" + stack + ")", m, () -> run(
        okay.rows.Effects.<UntransformedGadt.Prices<?>, Long, Pure>handle((op, k) -> switch (op) {
          case UntransformedGadt.Prices.Price price -> k.resume(price, 1L).map(sum -> sum);
          case UntransformedGadt.Prices.InStock s -> k.resume(s, true);
        }, UntransformedGadt.counting(m))));
    measure("clause", n, () -> run(okay.rows.Effects.<Tick<?>, Long, Pure>handle((op, k) -> switch (op) {
      case Tick.Next next -> k.resume(next, 1);
    }, Direct.effects(Tick.Fx.class).of(() -> loop(n)))));
  }
}
