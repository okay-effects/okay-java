package okay.resumable;

/** A SENSITIVE EFFECT IN DIRECT STYLE: answered only under its audit ({@code okay.rows.Audit}). A sealed abstract
 * subclass of this, its operations its final subclasses, each an {@code okay.rows.Sensitive.Op} of it:
 *
 * <pre>{@code
 * public abstract static sealed class Pay extends SensitiveSuspend {
 *   public static final class Transfer extends Pay implements Sensitive.Op<Pay, String, Transfer> { ... }
 * }
 * }</pre>
 *
 * Not a {@link Suspend}: so javac refuses it where a plain effect is wanted — in {@code Direct.row(..)}, in the
 * plain {@code Direct.perform}. Its place in a program's row is given by {@code Direct.sensitive(Pay.class)}, and is
 * {@code okay.rows.Sensitive<Pay, ..>}: no handler takes it but an audit. */
public abstract class SensitiveSuspend extends Exception {
  protected SensitiveSuspend() {
    super(null, null, false, false);
  }
}
