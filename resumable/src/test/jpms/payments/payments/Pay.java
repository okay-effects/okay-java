package payments;

import okay.resumable.Direct;
import okay.resumable.SensitiveSuspend;
import okay.rows.Audited;
import okay.rows.Comp;
import okay.rows.Sensitive;
import okay.rows.std.Pair;
import payments.bank.Banking;

/** payments, a sensitive effect: what programs see */
public abstract sealed class Pay extends SensitiveSuspend {
  public static final class Transfer extends Pay implements Sensitive.Op<Pay, String, Transfer> {
    public final String to;
    public final int amount;

    public Transfer(String to, int amount) {
      this.to = to;
      this.amount = amount;
    }
  }

  public static final class Balance extends Pay implements Sensitive.Op<Pay, Integer, Balance> {}

  public static String transfer(String to, int amount) throws Pay {
    return Direct.perform(new Transfer(to, amount));
  }

  public static int balance() throws Pay {
    return Direct.perform(new Balance());
  }

  /** the bank's handler installed: the one way a program's payments reach the bank, under its audit */
  public static <A, R> Comp<Pair<Integer, A>, R> banked(Comp<A, ? super Audited<Pay, R>> p) {
    return Banking.banked(p);
  }
}
