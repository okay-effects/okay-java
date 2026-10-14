package okay.std;

import java.util.function.Function;
import okay.core.Cont;
import okay.std.State.Final;

/**
 * okay-core's {@code PState} on {@link Cont}: a state whose TYPE may change, carried by the ANSWER TYPE —
 * Danvy–Filinski's state passing, the type moved by answer-type modification. No operation, no handler: {@code get}
 * and {@code put} are {@code shift0}s up to {@code pstate}'s delimiter, whose answer is the rest of the run given the
 * state, {@code St<S> = S -> Cont<T, T, R>}. A {@code put} of another type moves the answer from {@code St<S2>} to
 * {@code St<S>}, and the binds compose the moves: a {@code get} of the wrong type does not compile.
 * {@code T} is the answer outside, {@code R} the run's.
 */
public final class ContPState {
  private ContPState() {}

  /** the state, its type {@code S} unchanged: {@code shift0 k. λs. k(s)(s)} */
  public static <T, R, S> Cont<Cont<T, T, Function<S, Cont<T, T, R>>>, Cont<T, T, Function<S, Cont<T, T, R>>>, S> get() {
    return Cont.shift0(k -> Cont.pure(s -> k.apply(s).flatMap(f -> f.apply(s))));
  }

  /** a state of another type, {@code S2}, for the rest; the one before was an {@code S}: {@code shift0 k. λ_. k()(s2)} */
  public static <T, R, S, S2> Cont<Cont<T, T, Function<S2, Cont<T, T, R>>>, Cont<T, T, Function<S, Cont<T, T, R>>>, Void> put(S2 s2) {
    return Cont.shift0(k -> Cont.pure(_ -> k.apply(null).flatMap(f -> f.apply(s2))));
  }

  /** the body run from {@code s0}, an {@code S0}, to a state of type {@code S1}: the last state and the value */
  public static <T, S0, S1, W> Cont<T, T, Final<S1, W>> pstate(S0 s0,
      Cont<Cont<T, T, Function<S1, Cont<T, T, Final<S1, W>>>>, Cont<T, T, Function<S0, Cont<T, T, Final<S1, W>>>>, W> body) {
    return Cont.<Cont<T, T, Function<S1, Cont<T, T, Final<S1, W>>>>, T, T, Function<S0, Cont<T, T, Final<S1, W>>>, W>dollar(
        w -> Cont.pure(s1 -> Cont.pure(new Final<>(s1, w))), body).flatMap(f -> f.apply(s0));
  }
}
