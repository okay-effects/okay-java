import okay.effect.Operation;
import okay.resumable.Direct;
import okay.rows.Comp;

/** A body of a row that is a set asks only the effects its row is bounded by. */
final class SetBodyOperationNotInRow {
  sealed interface Ids { record Ref(String prefix) implements Ids, Operation<String, Ids> {} }
  sealed interface Clock { record Now() implements Clock, Operation<Long, Clock> {} }

  static <R extends Ids> Comp<String, R> stamp() {
    return Direct.body(d -> {
      String ref = d.perform(new Ids.Ref("B"));
      long now = d.perform(new Clock.Now()); // refused: Clock is not in the row
      return ref + now;
    });
  }
}
