package okay.std;

import java.util.function.Function;
import okay.core.Comp;
import okay.core.Prompt;
import okay.std.State.Final;

/**
 * a state whose TYPE may change: okay-core's {@code PState}, there carried by the answer type — here by the
 * capability, a typestate. {@code put} of a {@code T} gives the capability of a {@code PState<T>}; the one it was
 * put by is stale from then on, its use an {@link IllegalStateException}: Java has no linear types, so the check
 * is at run time, by a generation the state carries. The handler's answer is erased, so it needs no modifying: the
 * body ends with {@code done} of the last capability, and the type of the final state is its.
 */
public interface PState<L, S> {
  Comp<L, S> get();

  /** the state replaced by one of another type: this capability stale, the one given in force */
  <T> Comp<L, PState<L, T>> put(T t);

  default <A> Comp<L, Final<S, A>> done(A a) {
    return get().map(s -> new Final<>(s, a));
  }

  /** the body of {@link #pstate}: at a fresh level, given the capability there, ending with the last one's done */
  interface Body<L, S, T, A> {
    <M extends L> Comp<M, Final<T, A>> in(PState<M, S> st);
  }

  static <L, S, T, A> Comp<L, Final<T, A>> pstate(S init, Body<L, S, T, A> body) {
    Comp<L, Function<Cell, Comp<L, Final<T, A>>>> handled = Comp.handle(new Comp.Body<L, Final<T, A>, Function<Cell, Comp<L, Final<T, A>>>>() {
      public <M extends L> Comp<M, Final<T, A>> in(Prompt<L, M, Function<Cell, Comp<L, Final<T, A>>>> p) {
        return body.in(new At<>(p, 0));
      }
    }, f -> Comp.pure(_ -> Comp.pure(f)));
    return handled.flatMap(f -> f.apply(new Cell(0, init)));
  }

  /** the state and the generation of the capability in force */
  record Cell(int generation, Object value) {}

  final class At<L, M, S, R> implements PState<M, S> {
    private final Prompt<L, M, Function<Cell, Comp<L, R>>> p;
    private final int generation;

    At(Prompt<L, M, Function<Cell, Comp<L, R>>> p, int generation) {
      this.p = p;
      this.generation = generation;
    }

    @SuppressWarnings("unchecked")
    public Comp<M, S> get() {
      return p.shift0(k -> Comp.pure(c -> k.apply((S) live(c).value()).flatMap(f -> f.apply(c))));
    }

    public <T> Comp<M, PState<M, T>> put(T t) {
      return p.shift0(k -> Comp.pure(c -> {
        Cell next = new Cell(live(c).generation() + 1, t);
        return k.apply(new At<L, M, T, R>(p, next.generation())).flatMap(f -> f.apply(next));
      }));
    }

    private Cell live(Cell c) {
      if (c.generation() != generation) throw new IllegalStateException("a state since put by another capability");
      return c;
    }
  }
}
