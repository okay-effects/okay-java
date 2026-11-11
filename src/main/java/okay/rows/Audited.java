package okay.rows;

import okay.core.Rows;

/**
 * A SENSITIVE EFFECT {@code S}, AUDITED: what an audit of {@code S} ({@link Audit#audit}) leaves in the row outside
 * it, and what the handler of {@code S} ({@link Audit#handle}) takes off. Only a type: no value is one, and no
 * class can be one, so no operation is of it — nothing reaches its place but what an audit passes on.
 *
 * <p>An effect is sensitive when its author says so: its operations are {@link Audited.Op}s, not {@link Op}s.
 * <pre>{@code
 * sealed interface Pay {
 *   record Transfer(String to, int amount) implements Pay, Audited.Op<Pay, String, Transfer> {}
 *   record Balance() implements Pay, Audited.Op<Pay, Integer, Balance> {}
 * }
 * }</pre>
 * From then on: {@code perform} does not take them ({@link Audit#perform} does); a handler written with
 * {@code handle} or {@code intercept} gets them and cannot answer them — {@code K.resume} and {@code Cell.answer}
 * take an {@code Op} as the witness — it can only leave the rest; their answers are given by the handler of
 * {@link Audit#handle} alone ({@link Rest}), and only from the place an audit sends them to.
 */
public final class Audited<S> {
  private Audited() {}

  /** An operation of the sensitive effect {@code S}, answered by an {@code X}; {@code Self} its own class, as
   * {@link Op}'s */
  public interface Op<S, X, Self extends S> {}

  /** the rest of a program at an operation of a sensitive effect: resumed with its answer, the operation the
   * witness of its type, or unwound — what an audit's interceptor and the handler under the audit are given */
  public static final class Rest<S, B, R> {
    private final Object asked;
    private final Rows.Rest rest;

    Rest(Object asked, Rows.Rest rest) {
      this.asked = asked;
      this.rest = rest;
    }

    public <X> Comp<B, R> resume(Audited.Op<S, X, ?> op, X answer) {
      if (op != asked) throw new IllegalArgumentException("not the operation asked: " + op);
      return new Comp<>(Comp.DOOR.resume(rest, answer));
    }

    /** the rest left, as {@link K#unwind}: its finalizers run, nothing else of it */
    public Comp<Void, R> unwind() {
      return new Comp<>(Comp.DOOR.unwind(rest, Rows.GONE));
    }
  }
}
