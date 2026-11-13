package payments.bank;

import static okay.rows.Effects.pure;

import okay.rows.Audit;
import okay.rows.Audited;
import okay.rows.Cell;
import okay.rows.Comp;
import okay.rows.InPlace;
import okay.rows.std.Pair;
import payments.Pay;

/** the handler that calls the bank: not exported either */
public final class Banking {
  private Banking() {}

  public static <A, R> Comp<Pair<Integer, A>, R> banked(Comp<A, ? super Audited<Pay, R>> p) {
    return Audit.handle(new InPlace<Pay, Integer, A, Pair<Integer, A>, R>() {
      public Comp<Pair<Integer, A>, R> ret(Integer s, A a) { return pure(new Pair<>(Bank.balance(), a)); }
      public Cell.Answered answer(Cell<Integer> cell, Pay op) {
        return switch (op) {
          case Pay.Transfer t -> cell.answer(t, Bank.transfer(t.to, t.amount));
          case Pay.Balance b -> cell.answer(b, Bank.balance());
        };
      }
    }, 0, p);
  }
}
