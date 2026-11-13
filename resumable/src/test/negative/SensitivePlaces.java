import static okay.rows.Effects.*;

import okay.resumable.Direct;
import okay.resumable.Log;
import okay.resumable.Mail;
import okay.resumable.Pay;
import okay.rows.Audit;
import okay.rows.Comp;
import okay.rows.Eff;
import okay.rows.Interceptor;
import okay.rows.Pure;
import okay.rows.Sensitive;

/** Sensitive places anywhere in a row, more than one: each is Sensitive<S, ..> in the row's type, which only its
 * audit takes once the places before it are handled. javac refuses the rest: run or handled plainly, a sensitive
 * effect at a plain place, a body that performs what the row does not name. */
final class SensitivePlaces {
  static int logThenPay() throws Log, Pay {
    Log.tell("paying");
    return Pay.balance();
  }

  static String payThenMail() throws Pay, Mail {
    return Pay.transfer("a", 1) + Mail.send("b");
  }

  static <A, R> Interceptor<Pay, A, R> passing() {
    return (op, pass, k) -> switch (op) {
      case Pay.Transfer t -> k.after(t, pass.pass(t));
      case Pay.Balance b -> k.after(b, pass.pass(b));
    };
  }

  public static void main(String[] args) {
    Comp<Integer, Eff<Log, Sensitive<Pay, ?>>> p =
        Direct.row(Log.class, Direct.sensitive(Pay.class)).of(SensitivePlaces::logThenPay);
    // the log handled, then the payments audited and banked: these compile, to show the lines after them do not
    System.out.println(run(Pay.bank(100, Audit.audit(passing(), Log.run(p)))));
    System.out.println(run(Log.run(p))); // refused: the payments have no handler
    System.out.println(run(Pay.bank(100, Log.run(p)))); // refused: the payments are not audited
    System.out.println(run(handle(Pay.class, null, Log.run(p)))); // refused: handle takes no sensitive place
    Comp<Integer, Eff<Log, Eff<Pay, Pure>>> plain = p; // refused: a sensitive place is no plain place
    Direct.row(Log.class, Pay.class); // refused: a sensitive effect is no plain place, at any place
    Direct.row(Log.class, Direct.sensitive(Log.class)); // refused: a plain effect is not sensitive
    Comp<String, Sensitive<Pay, ? extends Sensitive<Mail, ?>>> two =
        Direct.row(Direct.sensitive(Pay.class), Direct.sensitive(Mail.class)).of(SensitivePlaces::payThenMail);
    System.out.println(run(Audit.audit(passing(), two))); // refused: the mail is not audited
    Direct.row(Direct.sensitive(Pay.class), Log.class).of(SensitivePlaces::payThenMail); // refused: no Mail
  }
}
