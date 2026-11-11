package okay.rows;

/** A handler's state, while a clause answers; and where the clause leaves the answer. One for each entry of a
 * handler in place: its state there. */
public final class Cell<S> {
  public S value;
  Object asked;
  Object answer;

  Cell() {}

  /** What a clause returns: that it has answered. */
  public static final class Answered {
    private Answered() {}
  }

  private static final Answered ANSWERED = new Answered();

  /** that a clause has answered, for {@link Bridge}'s clauses, whose answer is a program's value */
  static Answered answered() {
    return ANSWERED;
  }

  /** the operation is the witness of the answer's type: a {@code Get<S>} is answered by an {@code S} */
  public <X> Answered answer(Op<?, X, ?> op, X x) {
    if (op != asked) throw new IllegalArgumentException("not the operation asked: " + op);
    answer = x;
    return ANSWERED;
  }
}
