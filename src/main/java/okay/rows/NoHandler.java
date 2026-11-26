package okay.rows;

/** An operation that reached the top. The compiler refuses a program that could come to it: it is thrown only where
 * the row was not told the truth — by a cast —, and when a program stepped asks a sensitive operation, which only
 * its audit may see. */
public final class NoHandler extends RuntimeException {
  private static final long serialVersionUID = 1L;
  public final transient Object op;
  public NoHandler(Object op) {
    super("an operation with no handler: " + op + " — if it was asked by its effect's class (okay.rows.Sets), no "
        + "handler around it knows that class: a handler learns it where it is made, so make it where its effect "
        + "is a named type, not a type variable");
    this.op = op;
  }
}
