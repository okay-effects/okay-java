package okay.rows;

/** A HANDLER AS ONE FUNCTION: an operation of the effect {@code F} and the continuation {@code k}, the rest of the
 * program up to the handler — resumed once, many times, later, or never. A lambda with a {@code switch} over the
 * sealed family; the continuation's type refines the answer's: {@code case Price p -> k.resume(p, 4L)}, a
 * {@code Prices<Long>} answered by a {@code Long}. The handler's value is the program's, {@code A}: it has no
 * {@code ret} ({@link Handler} has).
 *
 * <p>A clause whose program is {@code k.resume(op, x)} and nothing else is answered IN PLACE: the rest is not
 * taken. Any other clause — one that goes on after the rest, resumes it twice, keeps it, leaves it — is given the
 * rest as {@link Handler}'s is. */
@FunctionalInterface
public interface Clause<F, A, R> {
  Comp<A, R> on(F op, K<A, R> k);
}
