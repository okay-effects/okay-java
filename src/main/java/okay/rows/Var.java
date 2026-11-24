package okay.rows;

/** THE ANSWER OF A QUESTION ASKED IN A {@link Together}: a name for it, written where the question is asked, read
 * where the block goes on ({@code then}, {@code yield}). Read anywhere else — before the questions were answered,
 * or later, from a function made there and run after — it is refused: take the value into a local there. */
public final class Var<T> {
  private final Together<?> block;
  private final int index;

  Var(Together<?> block, int index) {
    this.block = block;
    this.index = index;
  }

  /** the answer */
  @SuppressWarnings("unchecked") // the answer of the computation this names: a T, by Together.ask
  public T get() {
    return (T) block.answer(index);
  }
}
