package okay.std;

import java.util.function.Function;
import java.util.function.UnaryOperator;
import okay.core.Eff;
import okay.core.Prompt;

/** a state of type {@code S}: the handler's answer a function of the state, each operation's clause the rest given
 * the state it sees — so a k resumed twice resumes the state of its time, as a backtracking choice wants */
public interface State<L, S> {
  Eff<L, S> get();

  Eff<L, Void> put(S s);

  default Eff<L, Void> modify(UnaryOperator<S> f) {
    return get().flatMap(s -> put(f.apply(s)));
  }

  /** this capability at a level further in */
  @SuppressWarnings("unchecked")
  default <N extends L> State<N, S> at() {
    return (State<N, S>) (State<?, S>) this;
  }

  /** the final state and the value */
  record Final<S, A>(S state, A value) {}

  /** the body of a state's handler: at a fresh level, given the capability there */
  interface Body<L, S, A> {
    <M extends L> Eff<M, A> in(State<M, S> st);
  }

  static <L, S, A> Eff<L, Final<S, A>> state(S init, Body<L, S, A> body) {
    Eff<L, Function<S, Eff<L, Final<S, A>>>> handled = Eff.handle(new Eff.Body<L, A, Function<S, Eff<L, Final<S, A>>>>() {
      public <M extends L> Eff<M, A> in(Prompt<L, M, Function<S, Eff<L, Final<S, A>>>> p) {
        return body.in(new State<M, S>() {
          public Eff<M, S> get() {
            return p.shift0(k -> Eff.pure(s -> k.apply(s).flatMap(f -> f.apply(s))));
          }

          public Eff<M, Void> put(S s) {
            return p.shift0(k -> Eff.pure(_ -> k.apply(null).flatMap(f -> f.apply(s))));
          }
        });
      }
    }, a -> Eff.pure(s -> Eff.pure(new Final<>(s, a))));
    return handled.flatMap(f -> f.apply(init));
  }

  /** okay-core's {@code state}: answered IN PLACE from a cell, no prompt, no capture — one cell per RUN, made when
   * the loop gets to it. A resumption shares the cell: a body resumed twice sees one state, the second resumption
   * the first's last — {@link #state}'s backtracking traded for an operation that costs a read */
  static <L, S, A> Eff<L, Final<S, A>> cell(S init, Body<L, S, A> body) {
    return Eff.delay(() -> {
      Object[] cell = {init};
      @SuppressWarnings("unchecked")
      Eff<L, S> get = Eff.sync(() -> (S) cell[0]);
      return body.<L>in(new State<L, S>() {
        public Eff<L, S> get() {
          return get;
        }

        public Eff<L, Void> put(S s) {
          return Eff.sync(() -> { cell[0] = s; return null; });
        }
      }).flatMap(a -> get.map(s -> new Final<>(s, a)));
    });
  }
}
