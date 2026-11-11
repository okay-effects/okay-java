package okay.rows;

/** The rest of an unwinding resumed where the unwinding is not: a continuation taken from a finalizer while it ran
 * in an unwinding, by a handler inside the part unwound, was kept and resumed on another stack. The finalizers
 * have run; there is no value to go on with. */
public final class NoUnwinding extends RuntimeException {
  private static final long serialVersionUID = 1L;
  public NoUnwinding() { super("the rest of an unwinding was resumed outside the unwinding"); }
}
