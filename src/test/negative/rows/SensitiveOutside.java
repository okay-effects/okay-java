import static okay.rows.Effects.*;

import okay.rows.Audit;
import okay.rows.Comp;
import okay.rows.Interceptor;
import okay.rows.Pure;
import okay.rows.Sensitive;

/** Outside its audit no program asks a sensitive operation: there the place is {@code Audited}, and
 * {@code Audit.perform} asks at a {@code Sensitive} one. Only the audit's {@code Pass} asks there. */
final class SensitiveOutside {
  sealed interface Pay {
    record Balance() implements Pay, Sensitive.Op<Pay, Integer, Balance> {}
  }

  static Comp<Integer, Sensitive<Pay, ?>> balance() { return Audit.perform(new Pay.Balance()); }

  public static void main(String[] args) {
    // the way on: what compiles
    Interceptor<Pay, Integer, Pure> passing = (op, pass, k) -> switch (op) {
      case Pay.Balance b -> k.after(b, pass.pass(b));
    };
    // the clause asks the operation itself, outside its audit, past the way on
    Interceptor<Pay, Integer, Pure> around = (op, pass, k) -> switch (op) {
      case Pay.Balance b -> k.after(b, Audit.perform(b)); // refused: a sensitive place, not the audited one
    };
    System.out.println(passing != around);
  }
}
