import static okay.rows.Sets.*;

import okay.effect.Operation;
import okay.rows.Comp;
import okay.rows.Sets.And;

/** The two kinds of row are different types: a handler by place takes no program of a set. */
final class SetsPlaceHandler {
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

  static <A, R> Comp<A, R> byPlace(Comp<A, ? super okay.effect.Eff<Ids, R>> c) {
    return okay.rows.Effects.answering(op -> switch (op) { case Ids.Ref r -> r.answer("B-1"); }, c);
  }
  static Object entry() {
    var app = row(of(Ids.class, Clock.class));
    return byPlace(app.of(stamp())); // refused: the list is not a row of places
  }
}
