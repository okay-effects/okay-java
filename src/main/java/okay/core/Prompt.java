package okay.core;

import java.util.function.Function;

/** a handler's delimiter, by identity: its body at the level {@code M}, the handler at {@code L}, outside, its answer
 * {@code R}. Made by the loop when it enters the handler, one per run of it, and given to the body only: its type
 * names the body's fresh level, so it does not leave it */
public final class Prompt<L, M, R> {
  Prompt() {}

  /** {@code shift0} to this prompt: an operation at the body's level; k the rest up to the delimiter, the delimiter
   * included — applied, a program OUTSIDE, the handler in force again (deep); the clause runs outside, in the
   * delimiter's place, its value the handler's answer. Handlers nearer are crossed: none of their business */
  public <X> Eff<M, X> shift0(Function<Function<X, Eff<L, R>>, Eff<L, R>> clause) {
    return new EffLoop.Shift0<>(this, clause);
  }

  /** Danvy–Filinski's {@code shift}: {@code shift0} with its clause under the delimiter again, at the body's level —
   * {@code shift k. e = shift0 k. reset e}. At a handler's prompt k is the rest WITH the handler, its {@code ret} in
   * each resumption; the clause under the handler with no {@code ret}, its operations the handler's still */
  public <X> Eff<M, X> shift(Function<Function<X, Eff<L, R>>, Eff<M, R>> clause) {
    return shift0(k -> reset(clause.apply(k)));
  }

  /** {@code body}, of the body's level, under this prompt's delimiter again: its value the answer, outside */
  public Eff<L, R> reset(Eff<M, R> body) {
    return new EffLoop.Reset<>(this, body);
  }
}
