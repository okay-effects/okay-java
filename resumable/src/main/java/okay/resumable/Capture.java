package okay.resumable;

import java.util.ArrayList;

/** An operation on its way out of the transformed frames, each adding itself, the innermost first. Thrown by
 * {@link Resume#perform}, caught by the program's boundary ({@link Direct}). An {@code Error}, without a stack trace:
 * a transformed method catches it before any handler of its own, and code that was not transformed should not catch
 * it. For transformed code only. */
public final class Capture extends Error {
  final transient Object op;
  final transient ArrayList<Frame> frames = new ArrayList<>(8);

  Capture(Object op) {
    super(null, null, false, false);
    this.op = op;
  }

  @Override
  public String getMessage() {
    return "okay.resumable: the operation " + op + " was performed outside a program made by Direct.of, or crossed "
        + "code that does not let it pass" + (frames.isEmpty() ? "" : "; it left " + frames.getLast());
  }
}
