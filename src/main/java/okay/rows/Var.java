package okay.rows;

/** THE ANSWER OF A QUESTION ASKED IN A {@link Together}: a name for it, written where the question is asked. Given
 * to the block's own words — {@code t.ask(a, b, (x, y) -> ..)}, {@code t.yield(a, b, (x, y) -> ..)} — it is read
 * for them, as a value of its type. Read by {@link #get} it is there only where the block goes on, or in a question
 * asked later ({@code t.ask(() -> ..)}, {@code then}, {@code yield}); anywhere else — before it was answered, or
 * from a function made there and run after — it is refused: take the value into a local there. */
public final class Var<T> {
  final Together<?, ?> block;
  final int index;

  Var(Together<?, ?> block, int index) {
    this.block = block;
    this.index = index;
  }

  /** the answer */
  @SuppressWarnings("unchecked") // the answer of the computation this names: a T, by Together.ask
  public T get() {
    return (T) block.answer(index);
  }
}
