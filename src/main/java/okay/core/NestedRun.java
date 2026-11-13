package okay.core;

/** A RUN BEGUN WHILE A PROGRAM RUNS ON THE SAME THREAD — a top-level runner ({@code Effects.run}, {@code Effects.step},
 * {@code Eff.run}, a standard effect's own) called from a program's function or a handler's clause: refused at once,
 * before its first step. Such a run is a second machine on the Java stack: it sees none of the program's handlers, a
 * capture cannot cross it, and done at each step it overflows the stack. */
public final class NestedRun extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public NestedRun() {
    super("okay-java: a program was run (Effects.run, Effects.step, Eff.run) inside a running program — from a "
        + "program's function (flatMap, map, defer) or a handler's clause. A nested run is a second machine on the "
        + "stack: it sees none of the program's handlers, and at each step it overflows the stack. Install the "
        + "handler as a part of the program instead — State.run(s0, p), handle(..) are programs: flatMap them; for "
        + "a closed sub-program run to its end on purpose, as a run of its own, Effects.runIsolated(p) "
        + "(Eff.runIsolated(p))");
  }

  /** thrown if a program runs on this thread: for a runner of the host's that runs no machine itself on this thread
   * ({@code okay.rows.std.Async.runBlocking}) */
  public static void check() {
    Machine.refuseNested();
  }
}
