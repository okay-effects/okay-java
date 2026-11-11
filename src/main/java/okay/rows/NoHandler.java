package okay.rows;

/** An operation that reached the top. The compiler refuses a program that could come to it: it is thrown only where
 * the row was not told the truth — by a cast —, and when a program stepped asks a sensitive operation, which only
 * its audit may see. */
public final class NoHandler extends RuntimeException {
  private static final long serialVersionUID = 1L;
  public final transient Object op;
  public NoHandler(Object op) { super("an operation with no handler: " + op); this.op = op; }
}
