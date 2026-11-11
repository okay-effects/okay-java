import static okay.rows.Effects.*;

import okay.rows.Audit;
import okay.rows.Audited;
import okay.rows.Comp;
import okay.rows.Eff;
import okay.rows.Interceptor;
import okay.rows.Pure;

/** A program that asks a sensitive effect run with no audit, or handled by its handler with no audit before it, or
 * an audit that a program passes by. */
final class SensitiveRun {
  sealed interface Pay {
    record Transfer(String to, int amount) implements Pay, Audited.Op<Pay, String, Transfer> {}
  }

  static Comp<String, Eff<Pay, ?>> transfer() { return Audit.perform(new Pay.Transfer("a", 1)); }

  static <A, R> Audit.Handler<Pay, A, A, R> bank() {
    return new Audit.Handler<>() {
      public Comp<A, R> ret(A a) { return pure(a); }
      public Comp<A, R> on(Pay op, Audited.Rest<Pay, A, R> k) {
        return switch (op) {
          case Pay.Transfer t -> k.resume(t, "ok");
        };
      }
    };
  }

  static <A, T extends Eff<Audited<Pay>, ?>> Interceptor<Pay, A, A, T> passing() {
    return new Interceptor<>() {
      public Comp<A, T> ret(A a) { return pure(a); }
      public Comp<A, T> on(Pay op, Interceptor.Pass<Pay> pass, Audited.Rest<Pay, A, T> k) {
        return switch (op) {
          case Pay.Transfer t -> pass.pass(t).flatMap(r -> k.resume(t, r));
        };
      }
    };
  }

  public static void main(String[] args) {
    // audited, then handled: this line is here to show that the ones after it are refused for theirs
    System.out.println(run(Audit.handle(Pay.class, bank(),
        Audit.audit(Pay.class, SensitiveRun.<String, Eff<Audited<Pay>, Pure>>passing(), transfer()))));
    System.out.println(run(transfer())); // refused: the payment has no handler
    System.out.println(run(Audit.handle(Pay.class, bank(), transfer()))); // refused: not audited
    // past the audit, to the audited place: the operation one place further out
    Comp<String, Eff<Audited<Pay>, Pure>> past =
        Audit.audit(Pay.class, SensitiveRun.<String, Eff<Audited<Pay>, Pure>>passing(), up(transfer())); // refused
  }
}
