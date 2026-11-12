package okay.resumable;

import jdk.internal.vm.Continuation;
import jdk.internal.vm.ContinuationScope;

/** A DIRECT-STYLE BODY ON THE JVM'S ONE-SHOT CONTINUATION: an operation whose handler is general and declared
 * {@code Once} yields; the JVM freezes the frames and thaws them when the answer comes. Only frames between the body
 * and the yield are in it: nothing of the machine. A yield that cannot be made (a native frame, a monitor held)
 * falls back to saved frames. */
final class LoomRun {
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
  /** the operation it yielded; what it is given back: an answer, or a throwable thrown at the yield */
  Object pending;
  Object answer;
  Throwable thrown;

  LoomRun(Driver.Run body) {
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

  /** run to its end or its next yield, with the scoped-value bindings of the stack it runs on put back after */
  void run() {
    Object outside = Bindings.current();
    try {
      cont.run();
    } finally {
      Bindings.restore(outside);
    }
  }

  /** the operation yielded and its answer returned — or NOT, if this is not the continuation running here or it
   * cannot yield */
  Object yield(Object op) {
    if (Continuation.getCurrentContinuation(SCOPE) != cont) return NOT;
    pending = op;
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
