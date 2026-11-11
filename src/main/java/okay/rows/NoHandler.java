package okay.rows;

/** An operation that reached the top. The compiler refuses a program that could come to it: it is thrown only where
 * the row was not told the truth — by a cast. */
public final class NoHandler extends RuntimeException {
  private static final long serialVersionUID = 1L;
  public final transient Op<?, ?, ?> op;
  public NoHandler(Op<?, ?, ?> op) { super("an operation with no handler: " + op); this.op = op; }
}
