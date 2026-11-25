package okay.rows;

import java.util.List;

/** A CLAUSE FOR A SET OF OPERATIONS, of a handler with a state ({@link StateClause}): the state of this path, all the
 * operations of the set before any is answered, and the continuation — each answered through it, then resumed with
 * the next state, {@code k.resume(next)}, or as it is. As {@link Batch}: a handler without it is asked the
 * operations of a set one by one. */
@FunctionalInterface
public interface StateBatch<F, S, B, R> {
  Comp<B, R> on(S state, List<F> ops, KAllS<S, B, R> k);
}
