package okay.resumable;

import static okay.rows.Effects.handle;
import static okay.rows.Effects.pure;

import okay.rows.Cell;
import okay.rows.Comp;
import okay.rows.Eff;
import okay.rows.InPlace;
import okay.rows.Op;
import okay.rows.std.State.Final;

/** A STATE OF AN INT in direct style: the effect is a sealed exception class, its operations its final subclasses */
public abstract sealed class Counter extends Suspend {
  public static final class Get extends Counter implements Op<Counter, Integer, Get> {
    @Override
    public String toString() { return "Get"; }
  }

  public static final class Put extends Counter implements Op<Counter, Void, Put> {
    final int value;

    public Put(int value) { this.value = value; }

    @Override
    public String toString() { return "Put(" + value + ")"; }
  }

  public static int get() throws Counter {
    return Direct.perform(new Get());
  }

  public static void put(int value) throws Counter {
    Direct.perform(new Put(value));
  }

  /** the okay.rows handler, in place: a state per path */
  public static <A, R> Comp<Final<Integer, A>, R> run(int s0, Comp<A, ? super Eff<Counter, R>> p) {
    return handle(Counter.class, new InPlace<Counter, Integer, A, Final<Integer, A>, R>() {
      public Comp<Final<Integer, A>, R> ret(Integer s, A a) { return pure(new Final<>(s, a)); }
      public Cell.Answered answer(Cell<Integer> state, Counter op) {
        return switch (op) {
          case Get get -> state.answer(get, state.value);
          case Put put -> {
            state.value = put.value;
            yield state.answer(put, null);
          }
        };
      }
    }, s0, p);
  }
}
