import static okay.rows.Sets.*;

import okay.effect.Operation;
import okay.rows.Comp;
import okay.rows.Var;

/** Rows as sets: a block asks only the effects its row is bounded by. */
final class SetsBlockOperationNotInRow {
  sealed interface Ids { record Ref(String prefix) implements Ids, Operation<String, Ids> {} }
  sealed interface Clock { record Now() implements Clock, Operation<Long, Clock> {} }

  static <R extends Ids> Comp<String, R> stamp() {
    return together(t -> {
      Var<String> ref = t.perform(new Ids.Ref("B"));
      Var<Long> now = t.perform(new Clock.Now()); // refused: Clock is not in the row
      return t.yield(ref.with(now, (r, n) -> r + n));
    });
  }
}
