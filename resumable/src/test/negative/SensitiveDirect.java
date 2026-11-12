import static okay.rows.Effects.*;

import okay.resumable.Direct;
import okay.resumable.SensitiveSuspend;
import okay.resumable.Suspend;
import okay.rows.Audit;
import okay.rows.Audited;
import okay.rows.Comp;
import okay.rows.Eff;
import okay.rows.Handler;
import okay.rows.Interceptor;
import okay.rows.K;
import okay.rows.Pure;
import okay.rows.Sensitive;

/** A sensitive effect in direct style is placed by Direct.sensitive only: its program's row begins with a
 * Sensitive place, which only an audit takes. javac refuses it at a plain place, its program run or handled outside
 * its audit, and a sensitive operation of a plain effect (SensitiveUndeclared.java: performed where its effect is
 * not declared). */
final class SensitiveDirect {
  abstract static sealed class Pay extends SensitiveSuspend {
    static final class Transfer extends Pay implements Sensitive.Op<Pay, String, Transfer> {}
  }

  /** a plain effect, one of whose operations claims to be sensitive */
  abstract static sealed class Note extends Suspend {
    static final class Sneak extends Note implements Sensitive.Op<Note, Void, Sneak> {}
  }

  static String transfer() throws Pay {
    return Direct.perform(new Pay.Transfer());
  }

  static void sneak() throws Note {
    Direct.perform(new Note.Sneak()); // refused: a sensitive operation of a plain effect
  }

  static <A, R> Handler<Pay, A, A, R> bank() {
    return new Handler<>() {
      public Comp<A, R> ret(A a) { return pure(a); }
      public Comp<A, R> on(Pay op, K<A, R> k) {
        return switch (op) {
          case Pay.Transfer t -> k.resume(t, "ok");
        };
      }
    };
  }

  static <A, R> Interceptor<Pay, A, R> passing() {
    return (op, pass, k) -> switch (op) {
      case Pay.Transfer t -> k.after(t, pass.pass(t));
    };
  }

  public static void main(String[] args) {
    // placed sensitive, audited, then handled: these lines are here to show that the ones after them are refused
    Comp<String, Sensitive<Pay, ?>> p = Direct.row(Direct.sensitive(Pay.class)).of(SensitiveDirect::transfer);
    Comp<String, Audited<Pay, Pure>> audited = Audit.audit(passing(), p);
    System.out.println(run(Audit.handle(bank(), audited)));
    Direct.row(Pay.class); // refused: a sensitive effect is no plain place
    Direct.row(Direct.sensitive(Pay.class), Pay.class); // refused: nor after the sensitive one
    Direct.sensitive(Note.class); // refused: a plain effect is not sensitive
    Direct.row(Note.class).of(SensitiveDirect::transfer); // refused: the body performs Pay, not in its row
    System.out.println(run(p)); // refused: the payment has no handler
    System.out.println(run(handle(Pay.class, bank(), p))); // refused: handle takes no sensitive place
    System.out.println(run(Audit.handle(bank(), p))); // refused: the payment is not audited
    Comp<String, Eff<Pay, Pure>> plain = fit(p); // refused: a sensitive place is no plain place
    Comp<String, ?> between = under(p); // refused: under takes no sensitive place
  }
}
