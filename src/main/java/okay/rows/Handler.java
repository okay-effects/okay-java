package okay.rows;

/** THE GENERAL HANDLER of the effect {@code F}, in a program of an {@code A}: a {@code B}, asking what is left,
 * {@code R}. A clause is given the rest of the program, {@code k}, as a value: to resume once, many times, later,
 * or never. */
public interface Handler<F, A, B, R> {
  Comp<B, R> ret(A a);

  /** an operation of the effect, and the rest of the program: a {@code switch} over the sealed family */
  Comp<B, R> on(F op, K<B, R> k);

  /** for an interceptor: of the operations of its class, those it takes; any other passes on. A handler put by
   * {@code handle} is not asked: the row sends it the operations of its effect, and it answers them all. */
  default boolean mine(F op) { return true; }
}
