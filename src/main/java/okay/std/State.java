package okay.std;

import java.util.function.Function;
import java.util.function.UnaryOperator;
import okay.core.Eff;
import okay.core.Prompt;

/** a state of type {@code S}: the handler's answer a function of the state, each operation's clause the rest
 * given the state it sees — so a k resumed twice resumes the state of its time, as a backtracking choice wants */
public interface State<S> {
  Eff<S> get();

  Eff<Void> put(S s);

  default Eff<Void> modify(UnaryOperator<S> f) {
    return get().flatMap(s -> put(f.apply(s)));
  }

  /** the final state and the value */
  record Final<S, A>(S state, A value) {}

  static <S, A> Eff<Final<S, A>> state(S init, Function<State<S>, Eff<A>> body) {
    Eff<Function<S, Eff<Final<S, A>>>> handled = Eff.handle(
        (Prompt<Function<S, Eff<Final<S, A>>>> p) -> body.apply(new State<S>() {
          public Eff<S> get() {
            return p.shift0(k -> Eff.pure(s -> k.apply(s).flatMap(f -> f.apply(s))));
          }

          public Eff<Void> put(S s) {
            return p.shift0(k -> Eff.pure(_ -> k.apply(null).flatMap(f -> f.apply(s))));
          }
        }),
        a -> Eff.pure(s -> Eff.pure(new Final<>(s, a))));
    return handled.flatMap(f -> f.apply(init));
  }
}
