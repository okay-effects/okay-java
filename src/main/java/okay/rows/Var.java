package okay.rows;

import java.util.function.BiFunction;
import java.util.function.Function;

/** THE ANSWER OF A QUESTION ASKED IN A {@link Together}: a name for it, written where the question is asked. It goes
 * on by itself, as a value that is not there yet does — {@link #map} to a value made of it, {@link #with} another
 * to a value made of both, {@link #then} to the questions that need it:
 *
 * <pre>{@code
 * Var<Order> order = t.perform(new Shop.OrderOf(id));
 * Var<String> name = order.then(o -> t.perform(new Shop.CustomerName(o.customer())));
 * Var<List<String>> items = order.map(Order::items);
 * }</pre>
 *
 * Each is a name of the same block, asked in its rounds. Read by {@link #get} it is there only where the block goes
 * on, or in a question asked later ({@code t.ask(() -> ..)}, {@code then}, {@code yield}); anywhere else — before it
 * was answered, or from a function made there and run after — it is refused: take the value into a local there. */
public final class Var<T> {
  final Block<?> block;
  final int index;

  Var(Block<?> block, int index) {
    this.block = block;
    this.index = index;
  }

  /** a value made of this answer: no question */
  public <U> Var<U> map(Function<? super T, ? extends U> value) {
    return block.let(this, value);
  }

  /** a value made of this answer and another */
  public <B, U> Var<U> with(Var<B> other, BiFunction<? super T, ? super B, ? extends U> value) {
    return block.let(this, other, value);
  }

  /** a value made of this answer and two others */
  public <B, C, U> Var<U> with(Var<B> second, Var<C> third,
      Together.F3<? super T, ? super B, ? super C, ? extends U> value) {
    return block.let(this, second, third, value);
  }

  /** THE QUESTIONS THAT NEED THIS ANSWER: written when it is known, through the block — {@code t.perform(..)},
   * {@code t.ask(..)}, another name's {@code then} — and the name they give */
  public <U> Var<U> then(Function<? super T, Var<U>> next) {
    return block.follow(this, next);
  }

  /** the answer */
  @SuppressWarnings("unchecked") // the answer of the computation this names: a T, by Together.ask
  public T get() {
    return (T) block.answer(index);
  }
}
