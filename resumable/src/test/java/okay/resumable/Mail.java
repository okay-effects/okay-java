package okay.resumable;

import static okay.rows.Effects.pure;

import java.util.List;
import okay.rows.Audit;
import okay.rows.Audited;
import okay.rows.Cell;
import okay.rows.Comp;
import okay.rows.InPlace;
import okay.rows.Sensitive;

/** MAIL, a second sensitive effect: answered only under its own audit */
public abstract sealed class Mail extends SensitiveSuspend {
  public static final class Send extends Mail implements Sensitive.Op<Mail, String, Send> {
    final String to;

    public Send(String to) { this.to = to; }

    @Override
    public String toString() { return "Send(" + to + ")"; }
  }

  public static String send(String to) throws Mail {
    return Direct.perform(new Send(to));
  }

  /** an outbox, in place: the handler of the audited place */
  public static <A, R> Comp<A, R> outbox(List<String> sent, Comp<A, ? super Audited<Mail, R>> p) {
    return Audit.handle(new InPlace<Mail, Integer, A, A, R>() {
      public Comp<A, R> ret(Integer n, A a) { return pure(a); }
      public Cell.Answered answer(Cell<Integer> n, Mail op) {
        return switch (op) {
          case Send s -> {
            sent.add(s.to);
            yield n.answer(s, "sent:" + s.to);
          }
        };
      }
    }, 0, p);
  }
}
