package okay.rows;

/** A HANDLER WITH A STATE AS ONE FUNCTION: the state of this path, an operation of the effect {@code F}, and the
 * continuation — resumed with the answer and the next state, {@code k.resume(op, x, next)}, or with the answer
 * alone, the state as it is. The state is never changed in place: it is a value given to each clause, so a
 * continuation resumed twice goes on twice from the state it was asked in.
 *
 * <p>As a {@link Clause}: a clause whose program is one resumption and nothing else is answered in place. */
@FunctionalInterface
public interface StateClause<F, S, B, R> {
  Comp<B, R> on(S state, F op, KS<S, B, R> k);
}
