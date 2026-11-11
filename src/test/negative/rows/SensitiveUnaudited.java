import static okay.rows.Effects.*;

import okay.rows.Audit;
import okay.rows.Audited;
import okay.rows.Cell;
import okay.rows.Comp;
import okay.rows.Eff;
import okay.rows.Handler;
import okay.rows.InPlace;
import okay.rows.K;
import okay.rows.Op;

/** A sensitive effect asked or answered past its audit: its operations are not {@code Op}s, so {@code perform} does
 * not take them, and a handler written with {@code handle} has no witness to answer them with. */
final class SensitiveUnaudited {
  sealed interface Pay {
    record Transfer(String to, int amount) implements Pay, Audited.Op<Pay, String, Transfer> {}
  }

  static Comp<String, Eff<Pay, ?>> transfer() {
    // asked as a sensitive operation: this line is here to show that the ones after it are refused for theirs
    return Audit.perform(new Pay.Transfer("a", 1));
  }

  static Comp<String, Eff<Pay, ?>> plain() {
    return perform(new Pay.Transfer("a", 1)); // refused: not an operation that perform takes
  }

  static <A, R> Comp<A, R> byHand(Comp<A, ? super Eff<Pay, R>> p) {
    return handle(Pay.class, new Handler<Pay, A, A, R>() {
      public Comp<A, R> ret(A a) { return pure(a); }
      public Comp<A, R> on(Pay op, K<A, R> k) {
        return switch (op) {
          case Pay.Transfer t -> k.resume(t, "ok").map(a -> a); // refused: no witness of its answer
        };
      }
    }, p);
  }

  static <A, R> Comp<A, R> inPlace(Comp<A, ? super Eff<Pay, R>> p) {
    return handle(Pay.class, new InPlace<Pay, Void, A, A, R>() {
      public Comp<A, R> ret(Void s, A a) { return pure(a); }
      public Cell.Answered answer(Cell<Void> state, Pay op) {
        return switch (op) {
          case Pay.Transfer t -> state.answer(t, "ok"); // refused: no witness of its answer
        };
      }
    }, null, p);
  }

  /** the place of the audited effect: nothing an operation of Pay is can be had there */
  static <A, R> Comp<A, R> theVault(Comp<A, ? super Eff<Audited<Pay>, R>> p) {
    return handle(Audited.class, new Handler<Audited<Pay>, A, A, R>() {
      public Comp<A, R> ret(A a) { return pure(a); }
      public Comp<A, R> on(Audited<Pay> op, K<A, R> k) {
        if (op instanceof Pay.Transfer t) return pure(null); // refused: no operation is an Audited<Pay>
        return pure(null);
      }
    }, p);
  }

  /** an operation forged for the audited place */
  record Forged() implements Op<Audited<Pay>, String, Forged> {} // refused: a Forged is not an Audited<Pay>
}
