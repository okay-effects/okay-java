package okay.rows.std;

import static okay.rows.Effects.*;

import java.util.function.Function;
import okay.rows.Cell;
import okay.rows.Comp;
import okay.rows.Eff;
import okay.rows.Handler;
import okay.rows.InPlace;
import okay.rows.K;
import okay.rows.Op;

/** READER: a value of the surroundings. */
public sealed interface Reader<V> {
  record Ask<V>() implements Reader<V>, Op<Reader<V>, V, Ask<V>> {}

  static <V> Comp<V, Eff<Reader<V>, ?>> ask() { return perform(new Ask<>()); }

  static <V, A, R> Comp<A, R> run(V value, Comp<A, ? super Eff<Reader<V>, R>> p) {
    return handle(Reader.class, new InPlace<Reader<V>, Void, A, A, R>() {
      public Comp<A, R> ret(Void state, A a) { return pure(a); }
      public Cell.Answered answer(Cell<Void> state, Reader<V> op) {
        return switch (op) {
          case Ask<V> ask -> state.answer(ask, value);
        };
      }
    }, null, p);
  }

  /** the program, its asks answered by the reader outside, through {@code f}. An interceptor at the reader's
   * place in the row: a handler of the program's first effect, put over the reader — the rest of the program's
   * effects are one handler further ({@code under}), and the row is as it was. */
  static <V, A, T> Comp<A, Eff<Reader<V>, ? extends T>> local(
      Function<V, V> f, Comp<A, ? super Eff<Reader<V>, T>> p) {
    return handle(Reader.class, new Handler<Reader<V>, A, A, Eff<Reader<V>, ? extends T>>() {
      public Comp<A, Eff<Reader<V>, ? extends T>> ret(A a) { return pure(a); }
      public Comp<A, Eff<Reader<V>, ? extends T>> on(Reader<V> op, K<A, Eff<Reader<V>, ? extends T>> k) {
        return switch (op) {
          case Ask<V> ask -> Reader.<V>ask().flatMap(v -> k.resume(ask, f.apply(v)));
        };
      }
    }, under(p));
  }
}
