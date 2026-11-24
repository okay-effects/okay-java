package okay.rows;

import java.util.IdentityHashMap;
import java.util.List;
import okay.effect.Op;

/** The continuation of a set of operations asked at once ({@link Batch}): each answered, the operation the
 * witness of its answer's type; then the rest resumed with the answers in the operations' order. */
public final class KAll<B, R> {
  private final K<B, R> k;
  private final List<Object> ops;
  private final IdentityHashMap<Object, Object> answers = new IdentityHashMap<>();

  KAll(K<B, R> k, List<Object> ops) {
    this.k = k;
    this.ops = ops;
  }

  /** the answer to one operation of the set: {@code case Price p -> k.answer(p, 4L)} */
  public <X> KAll<B, R> answer(Op<?, X, ?> op, X answer) {
    answers.put(op, answer);
    return this;
  }

  /** the rest resumed with the answers: every operation of the set must have one */
  public Comp<B, R> resume() {
    Object[] out = new Object[ops.size()];
    for (int i = 0; i < out.length; i++) {
      Object op = ops.get(i);
      if (!answers.containsKey(op)) throw new IllegalStateException("an operation of the set has no answer: " + op);
      out[i] = answers.get(op);
    }
    return new Comp<>(k.resumed(java.util.Collections.unmodifiableList(java.util.Arrays.asList(out)), false, null));
  }

  /** the rest left: its finalizers run ({@link K#unwind}) */
  public Comp<Void, R> unwind() {
    return k.unwind();
  }
}
