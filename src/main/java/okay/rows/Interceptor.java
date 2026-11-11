package okay.rows;

/** AN INTERCEPTOR of the sensitive effect {@code E}, in a program of an {@code A}: each operation, the way on past
 * it, and the rest. It may tell (record, to a trail outside), pass the operation on ({@code pass.pass(op)}) as it is
 * or another one, change the answer, or refuse — fail by an effect outside, or end the program with an {@code A}
 * of its own ({@code k.unwind()} first). It goes on by {@code k.after(op, c)}: if the handler that answers {@code c}
 * leaves, the rest is left with it and its finalizers run. Its row is the one outside the audit. */
@FunctionalInterface
public interface Interceptor<E, A, R> {
  Comp<A, Audited<E, R>> on(E op, Pass<E, R> pass, K<A, Audited<E, R>> k);
}
