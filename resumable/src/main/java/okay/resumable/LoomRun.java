package okay.resumable;

import java.util.function.Supplier;
import jdk.internal.vm.Continuation;
import jdk.internal.vm.ContinuationScope;
import okay.rows.Comp;
import okay.rows.Here;

/** A DIRECT-STYLE BODY ON THE JVM'S ONE-SHOT CONTINUATION: an operation whose handler is general and declared
 * {@code Once}, and whose clause does not resume at once, yields; the JVM freezes the frames and thaws them when the
 * answer comes. Only frames between the body and the yield are in it: nothing of the machine. A yield that cannot be
 * made (a native frame, a monitor held) falls back to saved frames. */
final class LoomRun implements Supplier<Object> {
  static final ContinuationScope SCOPE = new ContinuationScope("okay.resumable");
  /** not yielded: the frames are saved instead */
  static final Object NOT = new Object();

  private static final class NotYielded extends RuntimeException {
    NotYielded() {
      super(null, null, false, false);
    }
  }

  final Continuation cont;
  /** what the body came to: its value, its failure, or frames it saved */
  Object value;
  Throwable failure;
  Capture capture;
  /** the program it yielded to go on with; what it is given back: an answer, or a throwable thrown at the yield */
  Comp<Object, Object> pending;
  Object answer;
  Throwable thrown;
  /** the operations asked so far, the last answered: each is answered once */
  int asked;
  int answered;
  /** the frames that wait for an answer, where a yield could not be made and they were saved instead */
  Frame waiting;
  /** whether the program's guard is in place: put before the first operation of a handler declared Once */
  boolean guarded;
  /** the body run on from where it waits: one program, made once */
  final Comp<Object, Object> next;

  LoomRun(Class<?>[] row, Driver.Run body) {
    next = Here.here(h -> Driver.step(row, this, h));
    cont = new Continuation(SCOPE, () -> {
      try {
        value = body.run();
      } catch (Capture c) {
        capture = c;
      } catch (Throwable t) {
        failure = t;
      }
    }) {
      @Override
      protected void onPinned(Continuation.Pinned reason) {
        throw new NotYielded();
      }
    };
  }

  @Override
  public Object get() {
    run();
    return null;
  }

  /** run to its end or its next yield, with the scoped-value bindings of the stack it runs on put back after */
  void run() {
    Object outside = Bindings.current();
    try {
      cont.run();
    } finally {
      Bindings.restore(outside);
    }
  }

  /** whether this is the continuation running here: only it can yield */
  boolean current() {
    return Continuation.getCurrentContinuation(SCOPE) == cont;
  }

  /** yielded, the program to go on with given to the boundary, and the answer returned — or NOT, if it cannot
   * yield */
  Object yield(Comp<Object, Object> next) {
    pending = next;
    Object inside = Bindings.current();
    try {
      Continuation.yield(SCOPE);
    } catch (NotYielded p) {
      pending = null;
      return NOT;
    }
    Bindings.restore(inside);
    Throwable t = thrown;
    if (t != null) {
      thrown = null;
      throw Driver.<RuntimeException>sneaky(t);
    }
    Object a = answer;
    answer = null;
    return a;
  }
}
