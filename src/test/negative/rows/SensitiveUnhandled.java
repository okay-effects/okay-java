import static okay.rows.Effects.*;

import okay.rows.Audit;
import okay.rows.Audited;
import okay.rows.Comp;
import okay.rows.Eff;
import okay.rows.Handler;
import okay.rows.Interceptor;
import okay.rows.K;
import okay.rows.Pass;
import okay.rows.Pure;
import okay.rows.Sensitive;

/** A sensitive effect handled, or a program that asks it run, anywhere but under its audit: its place in the row is
 * {@code Sensitive}, below {@code Eff}, which no {@code handle}, {@code under}, {@code fit} to a plain place or
 * {@code run} takes; and its handler takes only the {@code Audited} place an audit leaves. */
final class SensitiveUnhandled {
  sealed interface Pay {
    record Transfer(String to, int amount) implements Pay, Sensitive.Op<Pay, String, Transfer> {}
  }

  static Comp<String, Sensitive<Pay, ?>> transfer() { return Audit.perform(new Pay.Transfer("a", 1)); }

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
    // audited, then handled: these lines are here to show that the ones after them are refused for theirs
    Comp<String, Audited<Pay, Pure>> audited = Audit.audit(passing(), transfer());
    System.out.println(run(Audit.handle(bank(), audited)));
    System.out.println(run(transfer())); // refused: the payment has no handler
    System.out.println(run(handle(Pay.class, bank(), transfer()))); // refused: handle takes no sensitive place
    System.out.println(run(Audit.handle(bank(), transfer()))); // refused: the payment is not audited
    System.out.println(run(handle(Pay.class, bank(), audited))); // refused: handle takes no audited place
    Comp<String, Eff<Pay, Pure>> plain = fit(transfer()); // refused: a sensitive place is no plain place
    Comp<String, ?> between = under(transfer()); // refused: under takes no sensitive place
  }
}
