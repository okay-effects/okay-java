package okay.rows;

import java.util.List;
import java.util.function.Consumer;
import okay.effect.Op;

/**
 * A BLOCK ({@link Block}) of a row of places: what {@code Effects.together(t -> ..)} gives its function. Its
 * {@code perform} asks an operation of the row's FIRST effect {@code E}; an operation of another effect is asked
 * with {@code t.ask(up(perform(op)))}. (A block of a row that is a set is {@link Sets.Together}.)
 */
public final class Together<E, R> extends Block<R> {
  Together() {
    super(0);
  }

  /** an operation of the block's first effect, asked: its answer's name. The same as
   * {@code t.ask(Effects.perform(op))}; an operation of another effect is refused by the compiler */
  public <T, O extends Op<E, T, ? super O>> Var<T> perform(O op) {
    return asked(op);
  }

  /** A PLAIN LOOP OF QUESTIONS: those asked through {@code g} in {@code loop}, all answered by one type, are
   * questions of this block; their answers, in their order, under one name —
   * {@code t.gather(g -> { for (String item : cart) g.perform(new Price(item)); })} */
  public <T> Var<List<T>> gather(Consumer<? super Gather<T, E, R>> loop) {
    Gather<T, E, R> g = new Gather<>(this);
    loop.accept(g);
    return list(g.asked);
  }
}
