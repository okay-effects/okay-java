package okay.effect;

/** AN OPERATION AND ITS ANSWER, of the operation's own type: what {@link Op#answer} makes, and what a handler that
 * only answers gives for each operation — {@code case Price p -> p.answer(4L)}. */
public record Answer<X>(Op<?, X, ?> operation, X value) {}
