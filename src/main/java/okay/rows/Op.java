package okay.rows;

/** An operation of the effect {@code E}: a question as data, answered by an {@code X}. An effect is a sealed
 * family of them.
 *
 * {@code Self} is the operation's own class, which names itself:
 * {@code record Get<S>() implements State<S>, Op<State<S>, S, Get<S>> {}}. The compiler then keeps two promises.
 * A class that names itself and is not of the effect does not compile: {@code Self} must be an {@code E}. And
 * {@code perform} takes only an operation that names itself, or a class above itself: one that names another
 * class is refused there. So every operation performed is of its effect, and its handler is given an {@code E}
 * with no test at run time. */
public interface Op<E, X, Self extends E> {}
