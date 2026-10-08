package okay.std;

import java.util.function.Function;
import okay.core.Eff;
import okay.core.Prompt;
import okay.std.State.Final;

/**
 * a state whose TYPE may change: okay-core's {@code PState}, there carried by the answer type — here by the
 * capability, a typestate. {@code put} of a {@code T} gives the capability of a {@code PState<T>}; the one it was
 * put by is stale from then on, its use an {@link IllegalStateException}: Java has no linear types, so the check
 * is at run time, by a generation the state carries. The handler's answer is erased, so it needs no modifying: the
 * body ends with {@code done} of the last capability, and the type of the final state is its.
 */
public interface PState<S> {
  Eff<S> get();

  /** the state replaced by one of another type: this capability stale, the one given in force */
  <T> Eff<PState<T>> put(T t);

  default <A> Eff<Final<S, A>> done(A a) {
    return get().map(s -> new Final<>(s, a));
  }

  static <S, T, A> Eff<Final<T, A>> pstate(S init, Function<PState<S>, Eff<Final<T, A>>> body) {
    Eff<Function<Cell, Eff<Final<T, A>>>> handled = Eff.handle(
        (Prompt<Function<Cell, Eff<Final<T, A>>>> p) -> body.apply(new At<>(p, 0)),
        f -> Eff.pure(_ -> Eff.pure(f)));
    return handled.flatMap(f -> f.apply(new Cell(0, init)));
  }

  /** the state and the generation of the capability in force */
  record Cell(int generation, Object value) {}

  final class At<S, R> implements PState<S> {
    private final Prompt<Function<Cell, Eff<R>>> p;
    private final int generation;

    At(Prompt<Function<Cell, Eff<R>>> p, int generation) {
      this.p = p;
      this.generation = generation;
    }

    @SuppressWarnings("unchecked")
    public Eff<S> get() {
      return p.shift0(k -> Eff.pure(c -> k.apply((S) live(c).value()).flatMap(f -> f.apply(c))));
    }

    public <T> Eff<PState<T>> put(T t) {
      return p.shift0(k -> Eff.pure(c -> {
        Cell next = new Cell(live(c).generation() + 1, t);
        return k.apply(new At<T, R>(p, next.generation())).flatMap(f -> f.apply(next));
      }));
    }

    private Cell live(Cell c) {
      if (c.generation() != generation) throw new IllegalStateException("a state since put by another capability");
      return c;
    }
  }
}
