import static okay.rows.Effects.*;

import okay.rows.Audit;
import okay.rows.Comp;
import okay.rows.Eff;
import okay.rows.Handler;
import okay.rows.Interceptor;
import okay.rows.K;
import okay.rows.Pure;
import okay.rows.Sensitive;

/** A sensitive effect is handled under its audit only. Its operations are asked at a {@code Sensitive} place, which
 * is below {@code Eff<Pay, R>}: {@code handle}, {@code under} and {@code run} want a row above, and
 * {@code Audit.handle} wants an {@code Audited} place, which only {@code Audit.audit} gives. */
final class SensitiveHandled {
  sealed interface Pay {
    record Balance() implements Pay, Sensitive.Op<Pay, Integer, Balance> {}
  }

  static Comp<Integer, Sensitive<Pay, ?>> balance() { return Audit.perform(new Pay.Balance()); }

  static <A, R> Handler<Pay, A, A, R> bank() {
    return new Handler<Pay, A, A, R>() {
      public Comp<A, R> ret(A a) { return pure(a); }
      public Comp<A, R> on(Pay op, K<A, R> k) {
        return switch (op) {
          case Pay.Balance b -> k.resume(b, 100);
        };
      }
    };
  }

  public static void main(String[] args) {
    Interceptor<Pay, Integer, Pure> passing = (op, pass, k) -> switch (op) {
      case Pay.Balance b -> k.after(b, pass.pass(b));
    };
    // audited, then handled: these lines are here to show that the ones after them are refused for what they lack
    System.out.println(run(Audit.handle(SensitiveHandled.<Integer, Pure>bank(), Audit.audit(passing, balance()))));
    System.out.println(run(handle(Pay.class, SensitiveHandled.<Integer, Pure>bank(), balance()))); // refused: plain
    System.out.println(run(handle(Pay.class, SensitiveHandled.<Integer, Pure>bank(), under(balance())))); // refused
    System.out.println(run(Audit.handle(SensitiveHandled.<Integer, Pure>bank(), balance()))); // refused: no audit
    System.out.println(run(balance())); // refused: no handler
    Comp<Integer, Eff<Pay, ?>> asked = perform(new Pay.Balance()); // refused: not an operation of the kernel's
    Comp<Integer, Eff<Pay, ?>> plain = balance(); // refused: a sensitive place is not a plain one
    System.out.println(asked != plain);
  }
}
