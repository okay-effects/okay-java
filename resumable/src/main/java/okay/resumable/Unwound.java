package okay.resumable;

/** Thrown at a suspended call when the rest of the program is left without an answer (okay.rows' {@code k.unwind()}):
 * the method's {@code finally} blocks run as the frames are left, and nothing else of them. An {@code Error}, so
 * that {@code catch (Exception e)} does not stop it; {@code catch (Throwable t)} does, and ends the unwinding of that
 * frame. */
final class Unwound extends Error {
  static final Unwound INSTANCE = new Unwound();

  private Unwound() {
    super("okay.resumable: unwound", null, false, false);
  }
}
