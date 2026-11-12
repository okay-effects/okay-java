package okay.resumable;

import static okay.rows.Effects.pure;

import okay.rows.Audit;
import okay.rows.Audited;
import okay.rows.Cell;
import okay.rows.Comp;
import okay.rows.InPlace;
import okay.rows.Sensitive;
import okay.rows.std.Pair;

/** PAYMENTS in direct style, a sensitive effect: performed by plain methods that declare it, answered only under
 * its audit — the effect of okay.rows' {@code Audits}, as exception classes */
public abstract sealed class Pay extends SensitiveSuspend {
  public static final class Transfer extends Pay implements Sensitive.Op<Pay, String, Transfer> {
    final String to;
    final int amount;

    public Transfer(String to, int amount) {
      this.to = to;
      this.amount = amount;
    }

    @Override
    public String toString() { return "Transfer(" + to + ", " + amount + ")"; }
  }

  public static final class Balance extends Pay implements Sensitive.Op<Pay, Integer, Balance> {
    @Override
    public String toString() { return "Balance"; }
  }

  public static String transfer(String to, int amount) throws Pay {
    return Direct.perform(new Transfer(to, amount));
  }

  public static int balance() throws Pay {
    return Direct.perform(new Balance());
  }

  /** the bank, in place, from a balance: the handler of the audited place */
  public static <A, R> Comp<Pair<Integer, A>, R> bank(int start, Comp<A, ? super Audited<Pay, R>> p) {
    return Audit.handle(new InPlace<Pay, Integer, A, Pair<Integer, A>, R>() {
      public Comp<Pair<Integer, A>, R> ret(Integer s, A a) { return pure(new Pair<>(s, a)); }
      public Cell.Answered answer(Cell<Integer> balance, Pay op) {
        return switch (op) {
          case Transfer t -> {
            balance.value -= t.amount;
            yield balance.answer(t, "ok:" + t.to + ":" + t.amount);
          }
          case Balance b -> balance.answer(b, balance.value);
        };
      }
    }, start, p);
  }
}
