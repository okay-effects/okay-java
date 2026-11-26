import okay.effect.Operation;
import okay.resumable.Counter;
import okay.resumable.Direct;
import okay.rows.Comp;

/** A body of a row that is a set performs no effect written as a class: its row does not name one. */
final class SetBodyClassEffect {
  sealed interface Ids { record Ref(String prefix) implements Ids, Operation<String, Ids> {} }

  static <R extends Ids> Comp<String, R> stamp() {
    return Direct.body(d -> {
      String ref = d.perform(new Ids.Ref("B"));
      return ref + Counter.get(); // refused: Counter is not of the body's row
    });
  }
}
