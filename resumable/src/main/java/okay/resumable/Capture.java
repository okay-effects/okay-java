package okay.resumable;

import okay.rows.Comp;

/** An operation on its way out of the transformed frames, each adding itself, the innermost first. Thrown by
 * {@link Resume#perform}, caught by the program's boundary ({@link Direct}) — or returned to it by a resumed frame,
 * which only the boundary calls. An {@code Error}, without a stack trace: a transformed method catches it before any
 * handler of its own, and code that was not transformed should not catch it. For transformed code only. */
public final class Capture extends Error {
  final transient Object op;
  /** the frames saved, linked by {@link Frame#next}: the innermost, and the outermost so far */
  transient Frame first;
  transient Frame last;
  /** where the rest was taken already, by a clause called from the body's stack, and the body could not yield:
   * the clause's program, and what it gives the answer to */
  final transient Comp<Object, Object> next;
  final transient Driver.Answer later;

  Capture(Object op) {
    this(op, null, null);
  }

  Capture(Object op, Comp<Object, Object> next, Driver.Answer later) {
    super(null, null, false, false);
    this.op = op;
    this.next = next;
    this.later = later;
  }

  /** the frames linked to {@code tail}, the rest under which they were taken, and each told whether it or one
   * further out finalizes: the chain is then never changed. Two passes over the frames saved here, none over the
   * tail, nothing made */
  Frame link(Frame tail) {
    // reversed, the outermost first; then back, each finalizes as the frames outside it do
    Frame prev = null;
    for (Frame f = first; f != null; ) {
      Frame n = f.next;
      f.next = prev;
      prev = f;
      f = n;
    }
    boolean fin = tail != null && tail.finalizes;
    Frame out = tail;
    for (Frame f = prev; f != null; ) {
      Frame n = f.next;
      fin |= f.covered();
      f.finalizes = fin;
      f.next = out;
      out = f;
      f = n;
    }
    return out;
  }

  @Override
  public String getMessage() {
    return "okay.resumable: the operation " + op + " was performed outside a program made by Direct.of, or crossed "
        + "code that does not let it pass" + (last == null ? "" : "; it left " + last);
  }
}
