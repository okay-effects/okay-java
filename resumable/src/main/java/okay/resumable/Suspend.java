package okay.resumable;

/** AN EFFECT IN DIRECT STYLE is a sealed abstract subclass of this, and its operations are its final subclasses, each
 * an {@code okay.rows.Op} of it:
 *
 * <pre>{@code
 * public abstract static sealed class Cart extends Suspend {
 *   public static final class Add extends Cart implements Op<Cart, Void, Add> { ... }
 *   public static final class Total extends Cart implements Op<Cart, Integer, Total> { ... }
 * }
 * }</pre>
 *
 * A method lists in {@code throws} the effects it uses, and javac checks that it does. The effect's class is also its
 * type in an {@code okay.rows} row, {@code Eff<Cart, ..>}, so the row's handlers answer it. An operation is an
 * exception only for the types: nothing throws it; it is never given a stack trace. An effect cannot have type
 * arguments: Java refuses a generic subclass of {@code Throwable}. */
public abstract class Suspend extends Exception {
  protected Suspend() {
    super(null, null, false, false);
  }
}
