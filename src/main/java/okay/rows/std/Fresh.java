package okay.rows.std;

import static okay.rows.Effects.*;

import okay.rows.Cell;
import okay.rows.Comp;
import okay.rows.Eff;
import okay.rows.InPlace;
import okay.rows.Op;

/** FRESH: a number never given before, in this run. */
public sealed interface Fresh {
  record Next() implements Fresh, Op<Fresh, Long, Next> {}

  static Comp<Long, Eff<Fresh, ?>> fresh() { return perform(new Next()); }

  static <A, R> Comp<A, R> run(Comp<A, ? super Eff<Fresh, R>> p) {
    return handle(Fresh.class, new InPlace<Fresh, Long, A, A, R>() {
      public Comp<A, R> ret(Long next, A a) { return pure(a); }
      public Cell.Answered answer(Cell<Long> next, Fresh op) {
        return switch (op) {
          case Next n -> { next.value += 1; yield next.answer(n, next.value - 1); }
        };
      }
    }, 0L, p);
  }
}
