package okay.rows.std;

import static okay.rows.Effects.*;

import java.util.function.Function;
import okay.rows.Cell;
import okay.rows.Comp;
import okay.rows.Eff;
import okay.rows.InPlace;
import okay.rows.Op;

/** STATE. An effect is a sealed family of operations — data; each says what answers it. */
public sealed interface State<S> {
  record Get<S>() implements State<S>, Op<State<S>, S, Get<S>> {}
  record Put<S>(S value) implements State<S>, Op<State<S>, Void, Put<S>> {}

  static <S> Comp<S, Eff<State<S>, ?>> get() { return perform(new Get<>()); }
  static <S> Comp<Void, Eff<State<S>, ?>> put(S s) { return perform(new Put<>(s)); }
  static <S> Comp<Void, Eff<State<S>, ?>> modify(Function<S, S> f) {
    return State.<S>get().flatMap(s -> put(f.apply(s)));
  }

  /** the final state with the value */
  record Final<S, A>(S state, A value) {}

  /** the state kept by the handler, answered in place; each path of the program has its own */
  static <S, A, R> Comp<Final<S, A>, R> run(S s0, Comp<A, ? super Eff<State<S>, R>> p) {
    return handle(State.class, new InPlace<State<S>, S, A, Final<S, A>, R>() {
      public Comp<Final<S, A>, R> ret(S s, A a) { return pure(new Final<>(s, a)); }
      public Cell.Answered answer(Cell<S> state, State<S> op) {
        return switch (op) {
          case Get<S> get -> state.answer(get, state.value);
          case Put<S> put -> { state.value = put.value(); yield state.answer(put, null); }
        };
      }
    }, s0, p);
  }
}
