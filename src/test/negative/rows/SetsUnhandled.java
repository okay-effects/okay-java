import static okay.rows.Sets.*;

import okay.effect.Operation;
import okay.rows.Comp;
import okay.effect.Eff;

/** Rows as sets: run takes only a program whose list has no effect left. */
final class SetsUnhandled {
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

  static String entry() {
    var app = row(of(Ids.class, Clock.class));
    return run(identified(app.of(stamp()))); // refused: Clock is left
  }
}
