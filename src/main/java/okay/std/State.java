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

  /** okay-core's {@code state}: answered IN PLACE from a cell, no prompt, no capture — one cell per RUN, made when
   * the loop gets to it. A resumption shares the cell: a body resumed twice sees one state, the second resumption
   * the first's last — {@link #state}'s backtracking traded for an operation that costs a read */
  static <S, A> Eff<Final<S, A>> cell(S init, Function<State<S>, Eff<A>> body) {
    return Eff.delay(() -> {
      Object[] cell = {init};
      @SuppressWarnings("unchecked")
      Eff<S> get = Eff.sync(() -> (S) cell[0]);
      return body.apply(new State<S>() {
        public Eff<S> get() {
          return get;
        }

        public Eff<Void> put(S s) {
          return Eff.sync(() -> { cell[0] = s; return null; });
        }
      }).flatMap(a -> get.map(s -> new Final<>(s, a)));
    });
  }
}
