package okay.resumable;

import jdk.internal.vm.Continuation;
import jdk.internal.vm.ContinuationScope;
import okay.rows.Comp;
import okay.rows.Here;

/** A DIRECT-STYLE BODY ON THE JVM'S ONE-SHOT CONTINUATION: an operation whose handler is general and declared
 * {@code Once}, and whose clause does not resume at once, yields; the JVM freezes the frames and thaws them when the
 * answer comes. Only frames between the body and the yield are in it: nothing of the machine. A yield that cannot be
 * made (a native frame, a monitor held) falls back to saved frames. */
final class LoomRun extends BodyRun {
  static final ContinuationScope SCOPE = new ContinuationScope("okay.resumable");

  private static final class NotYielded extends RuntimeException {
    NotYielded() {
      super(null, null, false, false);
    }
  }

  final Continuation cont;

  LoomRun(Driver.Prog p, Driver.Run body, Driver.Entry entry, boolean plain) {
    super(entry, plain);
    next = Here.here(h -> Driver.step(p, this, h));
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
  @Override
  void run() {
    Object outside = Bindings.current();
    try {
      cont.run();
    } finally {
      Bindings.restore(outside);
    }
  }

  @Override
  boolean isDone() {
    return cont.isDone();
  }

  /** whether this is the continuation running here: only it can yield */
  @Override
  boolean current() {
    return Continuation.getCurrentContinuation(SCOPE) == cont;
  }

  /** yielded, the program to go on with given to the boundary, and the answer returned — or NOT, if it cannot
   * yield */
  @Override
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
