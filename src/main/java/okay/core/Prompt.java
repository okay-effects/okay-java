package okay.core;

import java.util.function.Function;

/** a handler's delimiter, by identity, its answer {@code R}: what an operation's {@code shift0} goes to. Made by the
 * loop when it enters the handler, so one per run of it — a program re-run, a k resumed again, gets its own */
public final class Prompt<R> {
  Prompt() {}

  /** {@code shift0} to this prompt: k the rest up to its delimiter, the delimiter included — applied, the handler
   * in force again (deep); the clause runs outside the delimiter, in its place, its value the handler's answer.
   * Handlers nearer than this one are crossed: each a lift, the clause none of their business */
  public <X> Eff<X> shift0(Function<Function<X, Eff<R>>, Eff<R>> clause) {
    return new EffLoop.Shift0<>(this, clause);
  }
}
