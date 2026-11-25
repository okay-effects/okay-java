package okay.effect;

/** An operation of the effect {@code E}: a question as data, answered by an {@code X}. An effect is a sealed
 * family of them.
 *
 * {@code Self} is the operation's own class, which names itself:
 * {@code record Get<S>() implements State<S>, Op<State<S>, S, Get<S>> {}}. The compiler then keeps two promises.
 * A class that names itself and is not of the effect does not compile: {@code Self} must be an {@code E}. And
 * {@code perform} takes only an operation that names itself, or a class above itself: one that names another
 * class is refused there. So every operation performed is of its effect, and its handler is given an {@code E}
 * with no test at run time. */
public interface Op<E, X, Self extends E> {
  /** this operation answered by {@code value}, of the type it names: what a handler that only answers gives —
   * {@code answering(op -> switch (op) { case Price p -> p.answer(table.get(p.item())); }, program)}. A value of
   * another type does not compile. */
  default Answer<X> answer(X value) {
    return new Answer<>(this, value);
  }
}
