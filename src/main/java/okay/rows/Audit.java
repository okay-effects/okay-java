package okay.rows;

import static okay.rows.Comp.DOOR;
import static okay.rows.Effects.pure;

/** SENSITIVE EFFECTS, answered only under their audit. A program asks a sensitive operation by {@link #perform},
 * at a {@link Sensitive} place of its row, which no {@code handle} takes. {@link #audit} gives each of its operations
 * to an {@link Interceptor} and leaves an {@link Audited} place; {@link #handle} takes only that. So every sensitive
 * operation that is answered has passed its audit. */
public final class Audit {
  private Audit() {}

  /** a sensitive operation asked: its effect is the first of the row, a sensitive place */
  public static <E, X, O extends Sensitive.Op<E, X, ? super O>> Comp<X, Sensitive<E, ?>> perform(O op) {
    return new Comp<>(DOOR.ask(op, 0));
  }

  /** the sensitive effect of the program's first place audited by {@code interceptor}: its clause runs outside the
   * audit, where the place is {@link Audited}. The audit and the handler of {@link Audited} are
   * one place of the type and two handlers at run time: the body is under the audit as {@code under} puts it, so its
   * other operations pass both */
  public static <E, A, R> Comp<A, Audited<E, R>> audit(Interceptor<E, A, R> interceptor,
      Comp<A, ? super Sensitive<E, R>> body) {
    Pass<E, R> pass = new Pass<>();
    return new Comp<>(DOOR.handle(new Handlers.General<>(Object.class, new Handler<E, A, A, Audited<E, R>>() {
      public Comp<A, Audited<E, R>> ret(A a) { return pure(a); }
      public Comp<A, Audited<E, R>> on(E op, K<A, Audited<E, R>> k) { return interceptor.on(op, pass, k); }
    }, true), null, DOOR.lift(body.eff, 1)));
  }

  /** the sensitive effect handled, under its audit only: the program's first place is {@link Audited} */
  public static <E, A, B, R> Comp<B, R> handle(Handler<E, A, B, R> h, Comp<A, ? super Audited<E, R>> p) {
    return new Comp<>(DOOR.handle(new Handlers.General<>(Object.class, h, true), null, p.eff));
  }

  /** the same, in place, from the state {@code s0} */
  public static <E, S, A, B, R> Comp<B, R> handle(InPlace<E, S, A, B, R> h, S s0, Comp<A, ? super Audited<E, R>> p) {
    return new Comp<>(DOOR.handle(new Handlers.Local<>(Object.class, h, true), s0, p.eff));
  }
}
