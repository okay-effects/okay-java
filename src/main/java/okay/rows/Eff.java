package okay.rows;

/** THE ROW of a computation — the effects it may ask — is a list in its type, in the order of the handlers, the
 * nearest first: {@code Eff<State<Integer>, Eff<Choose, ?>>} is a state, a choice under it, and anything under
 * that. Only types: there is no value of a row.
 *
 * The machine follows the row. A place in the row is a handler put by {@code handle}: the first effect is
 * answered by the nearest such handler, the second by the next one out, and so on — whatever the classes of the
 * handlers between. So two effects of one class, {@code Eff<Reader<String>, Eff<Reader<Integer>, ?>>}, are two
 * places, and an operation goes to the one its type says. An operation asked plainly goes to the first place;
 * {@code up(c)} sends every operation of {@code c} one place further out; {@code under(c)} leaves those of the
 * first place and sends the others one further; {@code fit(c)} changes only the type. An interceptor
 * ({@code intercept}) is not a place: it is found by the class of the operations that pass it.
 *
 * A computation is good wherever the row has its effects. Java's types are not open inside: a row that is to be
 * used in longer rows, or under a handler that asks the effect next to its own, is written open at every step —
 * {@code Eff<State<Integer>, ? extends Eff<Choose, ?>>}. A row may be given a name, when no handler takes it apart:
 * {@code interface Mine extends Eff<Log, Eff<Store, Pure>> {}}. */
public interface Eff<E, R> {}
