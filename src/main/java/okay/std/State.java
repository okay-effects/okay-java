package okay.std;

import java.util.function.Function;
import java.util.function.UnaryOperator;
import okay.core.Comp;
import okay.core.Prompt;

/** a state of type {@code S}: the handler's answer a function of the state, each operation's clause the rest given
 * the state it sees — so a k resumed twice resumes the state of its time, as a backtracking choice wants */
public interface State<L, S> {
  Comp<S, L> get();

  Comp<Void, L> put(S s);

  default Comp<Void, L> modify(UnaryOperator<S> f) {
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
    <M extends L> Comp<A, M> in(State<M, S> st);
  }

  static <L, S, A> Comp<Final<S, A>, L> state(S init, Body<L, S, A> body) {
    Comp<Function<S, Comp<Final<S, A>, L>>, L> handled = Comp.handle(new Comp.Body<L, A, Function<S, Comp<Final<S, A>, L>>>() {
      public <M extends L> Comp<A, M> in(Prompt<L, M, Function<S, Comp<Final<S, A>, L>>> p) {
        return body.in(new State<M, S>() {
          public Comp<S, M> get() {
            return p.shift0(k -> Comp.pure(s -> k.apply(s).flatMap(f -> f.apply(s))));
          }

          public Comp<Void, M> put(S s) {
            return p.shift0(k -> Comp.pure(_ -> k.apply(null).flatMap(f -> f.apply(s))));
          }
        });
      }
    }, a -> Comp.pure(s -> Comp.pure(new Final<>(s, a))));
    return handled.flatMap(f -> f.apply(init));
  }

  /** okay-core's {@code state}: answered IN PLACE from a cell, no prompt, no capture — one cell per RUN, made when
   * the loop gets to it. A resumption shares the cell: a body resumed twice sees one state, the second resumption
   * the first's last — {@link #state}'s backtracking traded for an operation that costs a read */
  static <L, S, A> Comp<Final<S, A>, L> cell(S init, Body<L, S, A> body) {
    return Comp.delay(() -> {
      Object[] cell = {init};
      @SuppressWarnings("unchecked")
      Comp<S, L> get = Comp.sync(() -> (S) cell[0]);
      return body.<L>in(new State<L, S>() {
        public Comp<S, L> get() {
          return get;
        }

        public Comp<Void, L> put(S s) {
          return Comp.sync(() -> { cell[0] = s; return null; });
        }
      }).flatMap(a -> get.map(s -> new Final<>(s, a)));
    });
  }
}
