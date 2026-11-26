import static okay.rows.Sets.*;

import okay.effect.Operation;
import okay.rows.Comp;
import okay.effect.Eff;

/** Rows as sets: a clause asks, through its continuation, only an effect of the list that stays. */
final class SetsClauseAsksOutside {
  sealed interface Ids { record Ref(String prefix) implements Ids, Operation<String, Ids> {} }
  sealed interface Clock { record Now() implements Clock, Operation<Long, Clock> {} }
  sealed interface Mail { record Send(String to) implements Mail, Operation<Void, Mail> {} }

  static <R extends Ids & Clock> Comp<String, R> stamp() {
    return perform(new Ids.Ref("B")).flatMap(ref -> perform(new Clock.Now()).map(now -> ref + now));
  }
  static <A, R> Comp<A, R> identified(Comp<A, ? super Eff<Ids, R>> c) {
    return answering(op -> switch (op) { case Ids.Ref r -> r.answer("B-1"); }, c);
  }
  static <A, R> Comp<A, R> clocked(Comp<A, ? super Eff<Clock, R>> c) {
    return answering(op -> switch (op) { case Clock.Now n -> n.answer(1L); }, c);
  }

  static <A, R> Comp<A, R> mailed(Comp<A, ? super Eff<Mail, R>> c) {
    return handle((op, k) -> switch (op) {
      case Mail.Send s -> ask(k, new Clock.Now()).flatMap(now -> k.resume(s, null)); // refused: R may have no Clock
    }, c);
  }
}
