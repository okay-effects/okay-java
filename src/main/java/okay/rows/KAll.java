package okay.rows;

import java.util.IdentityHashMap;
import java.util.List;
import okay.effect.Op;

/** The continuation of a set of operations asked at once ({@link Batch}): each answered, the operation the
 * witness of its answer's type; then the rest resumed with the answers in the operations' order. */
public sealed class KAll<B, R> permits KAllS {
  final K<B, R> k;
  private final List<Object> ops;
  private final IdentityHashMap<Object, Object> answers = new IdentityHashMap<>();

  KAll(K<B, R> k, List<Object> ops) {
    this.k = k;
    this.ops = ops;
  }

  /** the answer to one operation of the set: {@code case Price p -> k.answer(p, 4L)}. From any thread: the
   * operations of a set may be answered at the same time ({@link Batch#concurrently}) */
  public synchronized <X> KAll<B, R> answer(Op<?, X, ?> op, X answer) {
    answers.put(op, answer);
    return this;
  }

  /** an answer of a type the caller vouches for: for handlers of any effect (a cache), {@code okay.rows}' own */
  final synchronized void answerAny(Object op, Object answer) {
    answers.put(op, answer);
  }

  /** the answers, in the operations' order: every operation of the set must have one */
  final synchronized List<Object> all() {
    Object[] out = new Object[ops.size()];
    for (int i = 0; i < out.length; i++) {
      Object op = ops.get(i);
      if (!answers.containsKey(op)) throw new IllegalStateException("an operation of the set has no answer: " + op);
      out[i] = answers.get(op);
    }
    return java.util.Collections.unmodifiableList(java.util.Arrays.asList(out));
  }

  /** the rest resumed with the answers: every operation of the set must have one */
  public Comp<B, R> resume() {
    return new Comp<>(k.resumed(all(), false, null));
  }

  /** {@code c}, then the rest resumed with the answers — those given by then: a clause that asks further out for
   * what it does not know, and answers when that comes. If {@code c} is left, the rest is unwound there too
   * ({@link K#after}) */
  public Comp<B, R> resumeAfter(Comp<?, ? super R> c) {
    return k.afterAny(c.eff.map(done -> all()));
  }

  /** the rest left: its finalizers run ({@link K#unwind}) */
  public Comp<Void, R> unwind() {
    return k.unwind();
  }
}
