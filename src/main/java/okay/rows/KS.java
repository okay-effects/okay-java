package okay.rows;

import okay.effect.Op;

/** The continuation of a handler with a state ({@link StateClause}): resumed with the answer and the state the
 * path goes on with. {@code resume(op, x)}, {@link K}'s, goes on with the state the operation was asked in. */
public final class KS<S, B, R> extends K<B, R> {
  KS(Object asked, boolean place) {
    super(asked, place);
  }

  /** the rest resumed with the answer, the handler's state from here {@code state}:
   * {@code case Put p -> k.resume(p, null, p.value())} */
  public <X> Comp<B, R> resume(Op<?, X, ?> op, X answer, S state) {
    witness(op);
    return new Comp<>(resumed(answer, true, state));
  }
}
