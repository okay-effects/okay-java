package okay.rows;

import okay.effect.Sensitive;
import static okay.rows.Comp.DOOR;

import okay.core.Rows;

/** The handlers of the row notation as the machine sees them ({@link Rows.Spec}): the user's {@link Handler} and
 * {@link InPlace} behind them. The machine gives a handler the operations its place in the row sends it, as
 * {@code Object}: the casts here are where the types of {@link Effects} are trusted. */
final class Handlers {
  private Handlers() {}

  /** TRUSTED: a value of the type the program, built typed, gives here */
  @SuppressWarnings("unchecked")
  static <T> T trusted(Object v) {
    return (T) v;
  }

  /** a general handler, of a place ({@code row}) or an interceptor by the class {@code effect} */
  static final class General<F, A, B, R> extends Rows.Spec {
    private final Class<?> effect;
    private final Handler<F, A, B, R> h;
    private final boolean row;

    General(Class<?> effect, Handler<F, A, B, R> h, boolean row) {
      super(row, false);
      this.effect = effect;
      this.h = h;
      this.row = row;
    }

    @Override
    protected boolean accepts(Object op) {
      return effect.isInstance(op) && !(op instanceof Sensitive.Op) && h.mine(trusted(op));
    }

    @Override
    protected okay.core.Comp<?, ?> ret(Object entry, Object value) {
      return h.ret(trusted(value)).eff;
    }

    @Override
    protected okay.core.Comp<?, ?> on(Object op, Rows.Rest rest, int skip) {
      return h.on(trusted(op), new K<>(op, rest, row)).eff;
    }

    @Override
    protected boolean once() {
      return h instanceof Once;
    }

    @Override
    protected boolean eager() {
      return h instanceof Here.Eager e && e.eager();
    }
  }

  /** a handler that is one function ({@link Clause}), of a place: its clause called before the rest is taken */
  static final class Quick<F, A, R> extends Rows.Spec {
    private final Clause<F, A, R> clause;
    /** its clause for a set of operations, or {@code null}: a set is then asked one by one */
    private final Batch<F, A, R> batch;

    Quick(Clause<F, A, R> clause, Batch<F, A, R> batch) {
      this.batch = batch;
      super(true, false);
      this.clause = clause;
    }

    @Override
    protected boolean asksEarly() {
      return true;
    }

    @Override
    protected boolean takesMany() {
      return batch != null;
    }

    @Override
    protected okay.core.Comp<?, ?> ret(Object entry, Object value) {
      return okay.core.Comp.pure(value);
    }

    @Override
    protected Rows.Early early(Object entry, Object op) {
      K<A, R> k = new K<>(op);
      if (op instanceof Rows.Many many)
        return new Asked(k, batch.on(trusted(many.ops()), new KAll<>(k, many.ops())).eff, null);
      return new Asked(k, clause.on(trusted(op), k).eff, null);
    }
  }

  /** a handler with a state that is one function ({@link StateClause}): the state a value in its entry's cell,
   * given to each clause, kept for each path */
  static final class Stateful<F, S, A, B, R> extends Rows.Spec {
    private final StateClause<F, S, B, R> clause;
    /** its clause for a set of operations, or {@code null}: a set is then asked one by one */
    private final StateBatch<F, S, B, R> batch;
    private final java.util.function.BiFunction<? super S, ? super A, ? extends B> ret;

    Stateful(StateClause<F, S, B, R> clause, StateBatch<F, S, B, R> batch,
        java.util.function.BiFunction<? super S, ? super A, ? extends B> ret) {
      this.batch = batch;
      super(true, false);
      this.clause = clause;
      this.ret = ret;
    }

    @Override
    protected Object enter(Object init) {
      return new Object[] {init};
    }

    @Override
    protected okay.core.Comp<?, ?> ret(Object entry, Object value) {
      return okay.core.Comp.pure(ret.apply(trusted(((Object[]) entry)[0]), trusted(value)));
    }

    @Override
    protected boolean asksEarly() {
      return true;
    }

    @Override
    protected boolean takesMany() {
      return batch != null;
    }

    @Override
    protected boolean keepsState() {
      return true;
    }

    @Override
    protected Object state(Object entry) {
      return ((Object[]) entry)[0];
    }

    @Override
    protected void state(Object entry, Object state) {
      ((Object[]) entry)[0] = state;
    }

    @Override
    protected Rows.Early early(Object entry, Object op) {
      Object[] cell = (Object[]) entry;
      KS<S, B, R> k = new KS<>(op);
      if (op instanceof Rows.Many many)
        return new Asked(k, batch.on(trusted(cell[0]), trusted(many.ops()), new KAllS<>(k, many.ops())).eff, cell);
      return new Asked(k, clause.on(trusted(cell[0]), trusted(op), k).eff, cell);
    }
  }

  /** a clause's program, made before the rest was taken */
  private static final class Asked extends Rows.Early {
    private final K<?, ?> k;
    private final okay.core.Comp<?, ?> made;
    /** the state of the handler's entry, of a handler with a state; else {@code null} */
    private final Object[] cell;

    Asked(K<?, ?> k, okay.core.Comp<?, ?> made, Object[] cell) {
      this.cell = cell;
      this.k = k;
      this.made = made;
    }

    @Override
    protected boolean answered() {
      if (made != k.first) return false;
      k.answeredInPlace = true;
      if (k.firstGiven) cell[0] = k.firstState;
      return true;
    }

    @Override
    protected Object answer() {
      return k.firstAnswer;
    }

    @Override
    protected okay.core.Comp<?, ?> taken(Rows.Rest rest) {
      k.take(rest);
      return made;
    }
  }

  /** a handler that only answers ({@code Effects.answering}): one function, the operation to its answer — in place,
   * nothing taken; a set is asked of it one by one */
  static final class Answering<F> extends Rows.Spec {
    private final java.util.function.Function<? super F, ? extends okay.effect.Answer<?>> answers;

    Answering(java.util.function.Function<? super F, ? extends okay.effect.Answer<?>> answers) {
      super(true, true);
      this.answers = answers;
    }

    @Override
    protected okay.core.Comp<?, ?> ret(Object entry, Object value) {
      return okay.core.Comp.pure(value);
    }

    @Override
    protected Object answer(Object entry, Object op) {
      return given(answers, trusted(op));
    }
  }

  /** the answer {@code answers} gives for {@code op}: it must be this operation's, {@code op.answer(x)} */
  static <F> Object given(java.util.function.Function<? super F, ? extends okay.effect.Answer<?>> answers, F op) {
    okay.effect.Answer<?> a = answers.apply(op);
    if (a == null || a.operation() != op)
      throw new IllegalArgumentException("not the answer of the operation asked: " + op + " was answered by "
          + (a == null ? "nothing" : "the answer of " + a.operation()));
    return a.value();
  }

  /** a handler in place: its state in the cell of its entry */
  static final class Local<F, S, A, B, R> extends Rows.Spec {
    private final Class<?> effect;
    private final InPlace<F, S, A, B, R> h;

    Local(Class<?> effect, InPlace<F, S, A, B, R> h, boolean row) {
      super(row, true);
      this.effect = effect;
      this.h = h;
    }

    @Override
    protected boolean accepts(Object op) {
      return effect.isInstance(op) && !(op instanceof Sensitive.Op) && h.mine(trusted(op));
    }

    @Override
    protected Object enter(Object init) {
      Cell<S> cell = new Cell<>();
      cell.value = trusted(init);
      return cell;
    }

    @Override
    protected okay.core.Comp<?, ?> ret(Object entry, Object value) {
      Cell<S> cell = trusted(entry);
      return h.ret(cell.value, trusted(value)).eff;
    }

    @Override
    protected Object answer(Object entry, Object op) {
      Cell<S> cell = trusted(entry);
      cell.asked = op;
      h.answer(cell, trusted(op));
      return cell.answer;
    }

    @Override
    protected Object state(Object entry) {
      Cell<S> cell = trusted(entry);
      return cell.value;
    }

    @Override
    protected void state(Object entry, Object state) {
      Cell<S> cell = trusted(entry);
      cell.value = trusted(state);
    }
  }

  /** the rest of a stepped program */
  static final class Stepped<X, A, R> implements Step.Rest<X, A, R> {
    private final Rows.Rest rest;

    Stepped(Rows.Rest rest) {
      this.rest = rest;
    }

    @Override
    public Comp<A, R> apply(X x) {
      return new Comp<>(DOOR.resume(rest, x));
    }

    @Override
    public Comp<Void, R> unwind() {
      return new Comp<>(DOOR.unwind(rest, Rows.NONE));
    }
  }
}
