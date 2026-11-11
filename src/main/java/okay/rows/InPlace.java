package okay.rows;

/** THE HANDLER IN PLACE: every operation answered at once, from a state the handler keeps — nothing is captured,
 * the program goes on. The state belongs to the path: a continuation resumed twice starts twice from the state it
 * was taken with. */
public interface InPlace<F, S, A, B, R> {
  Comp<B, R> ret(S state, A a);

  /** the answer, given to the cell with the operation as the witness of its type:
   * {@code case Get<S> get -> state.answer(get, state.value)} */
  Cell.Answered answer(Cell<S> state, F op);

  /** for an interceptor: of the operations of its class, those it takes; see {@link Handler#mine} */
  default boolean mine(F op) { return true; }
}
