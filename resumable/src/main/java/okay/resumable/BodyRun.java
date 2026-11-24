package okay.resumable;

import java.util.function.Supplier;
import okay.rows.Comp;

/** A DIRECT-STYLE BODY THAT WAITS ON ITS OWN STACK for the answer of an operation whose handler took the rest: the
 * body stops where it asked, the program goes on with the handler's clause, and the answer starts the body again —
 * once. On the JVM's continuation ({@link LoomRun}), or on a virtual thread of its own ({@link ThreadRun}). */
abstract class BodyRun implements Supplier<Object> {
  /** not stopped: the frames are saved instead */
  static final Object NOT = new Object();

  /** how the boundary enters its outermost frame: a body's run, a clause's on or ret */
  final Driver.Entry entry;
  /** whether the body's class was NOT transformed: none of its frames can be saved, so every handler that takes
   * the rest is asked as one that resumes once, and one that resumes again is refused */
  final boolean plain;
  /** what the body came to: its value, its failure, or frames it saved */
  Object value;
  Throwable failure;
  Capture capture;
  /** the program it stopped to go on with; what it is given back: an answer, or a throwable thrown where it waits */
  Comp<Object, Object> pending;
  Object answer;
  Throwable thrown;
  /** the operations asked so far, the last answered: each is answered once */
  int asked;
  int answered;
  /** the frames that wait for an answer, where the body could not wait on its stack and they were saved instead */
  Frame waiting;
  /** whether the program's guard is in place: put before the first operation whose handler takes the rest */
  boolean guarded;
  /** the body run on from where it waits: one program, made once */
  Comp<Object, Object> next;

  BodyRun(Driver.Entry entry, boolean plain) {
    this.entry = entry;
    this.plain = plain;
  }

  @Override
  public final Object get() {
    run();
    return null;
  }

  /** run to its end or to where it next waits */
  abstract void run();

  abstract boolean isDone();

  /** whether this is the body running here: only it can wait */
  abstract boolean current();

  /** stopped, the program to go on with given to the boundary, and the answer returned — or {@link #NOT}, if it
   * cannot wait here */
  abstract Object yield(Comp<Object, Object> next);
}
