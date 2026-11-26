import static okay.rows.Sets.*;

import okay.effect.Eff;
import okay.effect.Operation;
import okay.rows.Comp;
import okay.rows.Sets;

/** Rows as sets: the list of a named set names only effects its type parameter is bounded by. */
final class SetsNamedListWider {
  sealed interface Ids { record Ref(String prefix) implements Ids, Operation<String, Ids> {} }
  sealed interface Clock { record Now() implements Clock, Operation<Long, Clock> {} }
  sealed interface Mail { record Send(String to) implements Mail, Operation<Void, Mail> {} }

  static <A, R> Comp<A, R> identified(Comp<A, ? super Eff<Ids, R>> c) {
    return answering(op -> switch (op) { case Ids.Ref r -> r.answer("B-1"); }, c);
  }
  static <A, R> Comp<A, R> clocked(Comp<A, ? super Eff<Clock, R>> c) {
    return answering(op -> switch (op) { case Clock.Now n -> n.answer(1L); }, c);
  }

  static final class Desk<V extends Ids> {
    <A> A run(Comp<A, ? super V> p) {
      var row = Sets.<V>exactly(Desk.class).row(of(Ids.class, Clock.class)); // refused: V is not bounded by Clock
      return null;
    }
  }
}
