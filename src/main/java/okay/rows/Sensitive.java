package okay.rows;

/** A PLACE OF THE ROW whose effect {@code E} is SENSITIVE, asked and not yet audited: what {@link Audit#perform}
 * gives. It is no {@code Eff} that {@code handle} takes: {@code handle} wants a row above {@code Eff<F, R>}, and this
 * is below {@code Eff<E, R>}. So no handler takes it, and {@code run} does not; only {@link Audit#audit} does, and
 * leaves {@link Audited} in its place.
 *
 * A sensitive effect is a sealed family like any, its operations {@link Op}s of this interface, not of the kernel's:
 * <pre>{@code
 * sealed interface Pay {
 *   record Transfer(String to, int amount) implements Pay, Sensitive.Op<Pay, String, Transfer> {}
 *   record Balance() implements Pay, Sensitive.Op<Pay, Integer, Balance> {}
 * }
 * }</pre>
 * {@code Effects.perform} refuses them; an interceptor by class is not given them. */
public interface Sensitive<E, R> extends Eff<E, R> {
  /** An operation of the sensitive effect {@code E}, answered by an {@code X}; {@code Self} as in the kernel's
   * {@link okay.rows.Op}. */
  interface Op<E, X, Self extends E> {}
}
