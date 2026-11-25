package okay.rows;

import java.util.List;

/** The continuation of a set of operations asked of a handler with a state ({@link StateBatch}): resumed with the
 * answers and the state the path goes on with. {@code resume()}, {@link KAll}'s, goes on with the state the set was
 * asked in. */
public final class KAllS<S, B, R> extends KAll<B, R> {
  KAllS(KS<S, B, R> k, List<Object> ops) {
    super(k, ops);
  }

  /** the rest resumed with the answers, the handler's state from here {@code state} */
  public Comp<B, R> resume(S state) {
    return new Comp<>(k.resumed(all(), true, state));
  }
}
