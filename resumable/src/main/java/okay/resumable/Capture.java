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
  /** the outermost frame saved here whose call a handler of its method covers */
  transient Frame covered;
  /** the outermost frame saved here whose call is not in tail position */
  transient Frame nonTail;
  /** whether it is on its way out of twins, by returns */
  transient boolean out;
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
   * further out finalizes: the chain is then never changed. One pass over the frames saved here, none over the
   * tail, nothing made */
  Frame link(Frame tail) {
    boolean outside = tail != null && tail.finalizes;
    boolean outsideTails = tail == null || tail.tails;
    // the frames up to the outermost covered one finalize; those outside it only if the tail does; the
    // same for the frames suspended in tail position
    boolean inside = covered != null;
    boolean insideNonTail = nonTail != null;
    for (Frame f = first; ; f = f.next) {
      f.finalizes = inside || outside;
      f.tails = !insideNonTail && outsideTails;
      if (f == covered) inside = false;
      if (f == nonTail) insideNonTail = false;
      if (f == last) {
        f.next = tail;
        return first;
      }
    }
  }

  @Override
  public String getMessage() {
    return "okay.resumable: the operation " + op + " was performed outside a program made by Direct.of, or crossed "
        + "code that does not let it pass" + (last == null ? "" : "; it left " + last);
  }
}
