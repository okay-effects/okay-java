package okay.resumable;

import java.util.concurrent.locks.LockSupport;
import okay.rows.Comp;
import okay.rows.Here;

/** A DIRECT-STYLE BODY ON A VIRTUAL THREAD OF ITS OWN, for code the plugin did not transform and a JVM whose
 * continuations are not opened: the public API, no flag. The body and the program take turns — one runs, the other
 * is parked — so the body asks the handlers in force from its own thread as it would from the program's: an
 * operation answered in place costs no turn. Where a handler takes the rest, the body parks, the program goes on
 * with the clause, and the answer unparks it: once — a parked thread is one rest, it cannot be copied. */
final class ThreadRun extends BodyRun {
  private static final int PROGRAM = 0;
  private static final int BODY = 1;
  /** how long a side looks for its turn before it parks: a turn that comes back at once — a clause that goes on, a
   * body that asks again — is taken without a park, which costs microseconds; 2^12 is what okay-direct measured
   * to be enough, 2^10 too few */
  private static final int SPINS = 1 << 12;

  private final Driver.Run body;
  private Thread thread;
  /** the program's thread, of this turn */
  private Thread program;
  /** where the program runs, of this turn: the body's thread takes it */
  private Driver.Ctx ctx;
  private volatile int turn = PROGRAM;
  private boolean done;
  /** whether a side is parked while it waits for its turn: only then is it unparked */
  private volatile boolean bodyParked;
  private volatile boolean programParked;

  ThreadRun(Driver.Prog p, Driver.Run body, Driver.Entry entry) {
    super(entry, true);
    this.body = body;
    next = Here.here(h -> Driver.step(p, this, h));
  }

  @Override
  void run() {
    program = Thread.currentThread();
    ctx = Driver.CTX.get();
    turn = BODY;
    if (thread == null) {
      thread = Thread.ofVirtual().name("okay.resumable body").unstarted(this::main);
      thread.start();
    } else if (bodyParked) LockSupport.unpark(thread);
    await(PROGRAM);
  }

  private void main() {
    Driver.CTX.set(ctx);
    try {
      value = body.run();
    } catch (Capture c) {
      capture = c;
    } catch (Throwable t) {
      failure = t;
    }
    done = true;
    toProgram();
  }

  private void await(int mine) {
    for (int i = 0; i < SPINS && turn != mine; i++) Thread.onSpinWait();
    if (turn == mine) return;
    // parked: said first, then the turn looked at again — so the side that gives the turn either sees this, and
    // unparks, or gave the turn before, and it is seen here. Each side its own flag: it alone clears it
    if (mine == BODY) {
      bodyParked = true;
      while (turn != mine) LockSupport.park(this);
      bodyParked = false;
    } else {
      programParked = true;
      while (turn != mine) LockSupport.park(this);
      programParked = false;
    }
  }

  private void toProgram() {
    Thread waiting = program;
    turn = PROGRAM;
    if (programParked) LockSupport.unpark(waiting);
  }

  @Override
  boolean isDone() {
    return done;
  }

  @Override
  boolean current() {
    return Thread.currentThread() == thread;
  }

  @Override
  Object yield(Comp<Object, Object> next) {
    pending = next;
    toProgram();
    await(BODY);
    Driver.CTX.set(ctx);
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
