package okay.effect;

/** AN EFFECT AS A GADT: the effect's own interface is indexed by its operations' answers, and says so once —
 * {@code sealed interface Prices<T> extends Operation<T, Prices<?>>}. Its operations are then plain records, each
 * naming its answer by the index: {@code record Price(String item) implements Prices<Long> {}}.
 *
 * <p>It is an {@link Op} of the effect {@code F} answered by a {@code T}, which names as itself the effect: every
 * record of the family is an {@code F}, so {@code perform} takes it, and the two promises of {@code Op} hold. For an
 * effect with type parameters of its own, whose records cannot be told by an index ({@code Get<S>} of a
 * {@code State<S>}), {@code Op} is written on each record as before. */
public interface Operation<T, F> extends Op<F, T, F> {}
