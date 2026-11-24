package okay.rows;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import okay.effect.Eff;
import okay.effect.Op;

/**
 * PERFORM WRITTEN IN A PLAIN LOOP, the computations it made taken out of the loop: the operations asked in the
 * body, each as a computation, in their order — to {@code sequence}, {@code reduce}, {@code fold}, a stream.
 *
 * <pre>{@code
 * Iterable<Comp<Long, Eff<Prices<?>, ?>>> asked = new Gather<>() {{
 *   for (String item : cart) perform(new Prices.Price(item));
 * }};
 * }</pre>
 *
 * The body is the initializer of an anonymous subclass, so {@code perform} there is THIS class's method: it makes
 * the computation as {@code Effects.perform} does and keeps it. Nothing is hidden — no state of the thread, nothing
 * {@code Effects.perform} reads — and the compiler checks the body: {@code X} and {@code E} are the answer and the
 * effect of what is gathered, and an operation of another effect or another answer does not compile.
 *
 * <p>Nothing runs in the body: {@code perform} makes a computation, it does not answer, so the body cannot use an
 * answer — a loop that goes by the answers is {@code Effects.fold}. What is gathered is what {@code perform}
 * returned, not what the body made of it after: to gather a computation made of it, {@link #add}.
 */
public abstract class Gather<X, E> implements Iterable<Comp<X, Eff<E, ?>>> {
  private final ArrayList<Comp<X, Eff<E, ?>>> asked = new ArrayList<>();

  protected Gather() {}

  /** the operation asked: its computation, gathered */
  protected final <O extends Op<E, X, ? super O>> Comp<X, Eff<E, ?>> perform(O op) {
    Comp<X, Eff<E, ?>> made = Effects.perform(op);
    asked.add(made);
    return made;
  }

  /** a computation gathered as it is: one made of operations, {@code add(ask(op).map(f))} */
  protected final void add(Comp<X, ? super Eff<E, ?>> made) {
    asked.add(Effects.fit(made));
  }

  /** an operation's computation, NOT gathered: a part of one that {@link #add} takes */
  protected final <Y, O extends Op<E, Y, ? super O>> Comp<Y, Eff<E, ?>> ask(O op) {
    return Effects.perform(op);
  }

  /** the computations gathered, in their order */
  public final List<Comp<X, Eff<E, ?>>> list() {
    return Collections.unmodifiableList(asked);
  }

  @Override
  public final Iterator<Comp<X, Eff<E, ?>>> iterator() {
    return list().iterator();
  }
}
