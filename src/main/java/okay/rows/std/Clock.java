package okay.rows.std;

import static okay.rows.Effects.*;

import java.util.function.LongSupplier;
import okay.rows.Cell;
import okay.rows.Comp;
import okay.rows.Eff;
import okay.rows.InPlace;
import okay.rows.Op;

/** CLOCK: the time, in milliseconds — the system's, or one a test sets. */
public sealed interface Clock {
  record Now() implements Clock, Op<Clock, Long, Now> {}

  static Comp<Long, Eff<Clock, ?>> now() { return perform(new Now()); }

  static <A, R> Comp<A, R> system(Comp<A, ? super Eff<Clock, R>> p) { return at(System::currentTimeMillis, p); }

  static <A, R> Comp<A, R> at(LongSupplier time, Comp<A, ? super Eff<Clock, R>> p) {
    return handle(Clock.class, new InPlace<Clock, Void, A, A, R>() {
      public Comp<A, R> ret(Void state, A a) { return pure(a); }
      public Cell.Answered answer(Cell<Void> state, Clock op) {
        return switch (op) {
          case Now now -> state.answer(now, time.getAsLong());
        };
      }
    }, null, p);
  }
}
