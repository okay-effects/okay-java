package okay.rows;

/**
 * AN INTERCEPTOR of a sensitive effect {@code S}: an audit's ({@link Audit#audit}). Each operation of {@code S} the
 * body asks comes to it first, with the rest of the body, {@code k}; its clause runs outside the audit, over the row
 * {@code T}, whose first place is {@code Audited<S>}. It may
 * <ul>
 * <li>tell: ask effects of its own, a trail ({@code std.Trail}), a log;</li>
 * <li>pass the operation on, {@code pass.pass(op)}, to the handler of {@code S} ({@link Audit#handle}), and give
 *     {@code k} what came back;</li>
 * <li>change the operation, passing on another one, or change the answer;</li>
 * <li>refuse: not pass it on, and fail ({@code Throws.raise}), or answer for the whole body ({@code B}).</li>
 * </ul>
 * {@code k} is a value, as a handler's: resumed once, many times, or never, or unwound.
 */
public interface Interceptor<S, A, B, T> {
  Comp<B, T> ret(A a);

  Comp<B, T> on(S op, Pass<S> pass, Audited.Rest<S, B, T> k);

  /** The operation passed on, past the audit, to the place of {@code Audited<S>}: the first of the row outside.
   * Made by {@link Audit#audit} alone. */
  final class Pass<S> {
    Pass() {}

    public <X, O extends Audited.Op<S, X, ? super O>> Comp<X, Eff<Audited<S>, ?>> pass(O op) {
      return new Comp<>(Comp.DOOR.ask(op, 0));
    }
  }
}
