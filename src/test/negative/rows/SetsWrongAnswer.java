import static okay.rows.Sets.*;

import okay.effect.Operation;
import okay.rows.Comp;
import okay.rows.Sets.And;

/** Rows as sets: a continuation is resumed with the answer of the operation asked. */
final class SetsWrongAnswer {
  sealed interface Ids { record Ref(String prefix) implements Ids, Operation<String, Ids> {} }
  sealed interface Clock { record Now() implements Clock, Operation<Long, Clock> {} }
  sealed interface Mail { record Send(String to) implements Mail, Operation<Void, Mail> {} }

  static <R extends Ids & Clock> Comp<String, R> stamp() {
    return perform(new Ids.Ref("B")).flatMap(ref -> perform(new Clock.Now()).map(now -> ref + now));
  }
  static <A, R> Comp<A, R> identified(Comp<A, ? super And<Ids, R>> c) {
    return answering(op -> switch (op) { case Ids.Ref r -> r.answer("B-1"); }, c);
  }
  static <A, R> Comp<A, R> clocked(Comp<A, ? super And<Clock, R>> c) {
    return answering(op -> switch (op) { case Clock.Now n -> n.answer(1L); }, c);
  }

  static <A, R> Comp<A, R> numbered(Comp<A, ? super And<Ids, R>> c) {
    return handle((op, k) -> switch (op) {
      case Ids.Ref r -> k.resume(r, 4L); // refused: a Ref is answered by a String
    }, c);
  }
}
