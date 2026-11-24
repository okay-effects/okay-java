package okay.rows;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
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
 * <p>The same as a lambda, the gather its parameter ({@code Effects.gather}): no anonymous class, {@code perform}
 * said of the gather —
 *
 * <pre>{@code
 * Iterable<Comp<Long, Eff<Prices<?>, ?>>> asked = gather(g -> {
 *   for (String item : cart) g.perform(new Prices.Price(item));
 * });
 * }</pre>
 *
 * <p>Nothing runs in the body: {@code perform} makes a computation, it does not answer, so the body cannot use an
 * answer — a loop that goes by the answers is {@code Effects.fold}. What is gathered is what {@code perform}
 * returned, not what the body made of it after: to gather a computation made of it, {@link #add}.
 */
public abstract class Gather<X, E> implements Iterable<Comp<X, Eff<E, ?>>> {
  private final ArrayList<Comp<X, Eff<E, ?>>> asked = new ArrayList<>();
  /** beside each computation, the operation it is, or {@code null}: one made of operations ({@link #add}) */
  private final ArrayList<Op<E, X, ?>> operations = new ArrayList<>();

  protected Gather() {}

  /** the gather a lambda is given ({@code Effects.gather}) */
  static final class Given<X, E> extends Gather<X, E> {}

  /** the operation asked: its computation, gathered */
  public final <O extends Op<E, X, ? super O>> Comp<X, Eff<E, ?>> perform(O op) {
    Comp<X, Eff<E, ?>> made = Effects.perform(op);
    asked.add(made);
    operations.add(op);
    return made;
  }

  /** a computation gathered as it is: one made of operations, {@code add(ask(op).map(f))} */
  public final void add(Comp<X, ? super Eff<E, ?>> made) {
    asked.add(Effects.fit(made));
    operations.add(null);
  }

  /** an operation's computation, NOT gathered: a part of one that {@link #add} takes */
  public final <Y, O extends Op<E, Y, ? super O>> Comp<Y, Eff<E, ?>> ask(O op) {
    return Effects.perform(op);
  }

  // -- what to do with what was gathered: each a computation of the same effect

  /** all of them run in their order: their values */
  public final Comp<List<X>, Eff<E, ?>> toList() {
    return Effects.<X, Eff<E, ?>>sequence(asked);
  }

  /** all of them run in their order, their values combined: {@code acc = combine(acc, x)} from {@code zero} */
  public final <S> Comp<S, Eff<E, ?>> reduce(S zero, BiFunction<? super S, ? super X, ? extends S> combine) {
    return Effects.<X, S, Eff<E, ?>>reduce(asked, zero, combine);
  }

  /** run in their order UNTIL a value is {@code wanted}: that value, and the ones after it are never asked;
   * none wanted, all were asked and the value is empty */
  public final Comp<Optional<X>, Eff<E, ?>> first(Predicate<? super X> wanted) {
    return firstFrom(0, wanted);
  }

  private Comp<Optional<X>, Eff<E, ?>> firstFrom(int i, Predicate<? super X> wanted) {
    if (i == asked.size()) return Effects.pure(Optional.empty());
    return asked.get(i).flatMap(x -> wanted.test(x)
        ? Effects.<Optional<X>, Eff<E, ?>>pure(Optional.ofNullable(x)) : firstFrom(i + 1, wanted));
  }

  /** whether a value is {@code wanted}: asked no further than the first that is */
  public final Comp<Boolean, Eff<E, ?>> any(Predicate<? super X> wanted) {
    return first(wanted).map(Optional::isPresent);
  }

  /** whether every value is {@code wanted}: asked no further than the first that is not */
  public final Comp<Boolean, Eff<E, ?>> all(Predicate<? super X> wanted) {
    return first(wanted.negate()).map(Optional::isEmpty);
  }

  /** the same computations, each value through {@code f} */
  public final <Y> Gather<Y, E> map(Function<? super X, ? extends Y> f) {
    Gather<Y, E> mapped = new Given<>();
    for (Comp<X, Eff<E, ?>> c : asked) mapped.add(c.map(f::apply));
    return mapped;
  }

  /** THE OPERATIONS THEMSELVES, as data, before anything runs: what a program is about to ask — to ask them all
   * in one (a batch operation of the effect), to drop the repeated, to show or to count. Refused if a computation
   * made of operations was gathered ({@link #add}): it is not one operation */
  public final List<Op<E, X, ?>> operations() {
    if (operations.contains(null))
      throw new IllegalStateException("a computation made of operations was gathered: it is not one operation");
    return Collections.unmodifiableList(operations);
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
