package okay.rows;

import java.util.function.Function;

/** Where a computation came to: its value, or an operation it asks and the rest of it. */
public sealed interface Step<A, R> {
  record Done<A, R>(A value) implements Step<A, R> {}
  record Asked<X, A, R>(Op<?, X, ?> op, Rest<X, A, R> k) implements Step<A, R> {}

  /** the rest of a stepped program: {@code apply} resumes it with the answer; {@code unwind} leaves it — its
   * finalizers ({@code ensuring}) run, and nothing else of it */
  interface Rest<X, A, R> extends Function<X, Comp<A, R>> {
    Comp<Void, R> unwind();
  }
}
