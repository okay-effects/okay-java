package okay.rows;

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
    protected okay.core.Eff<?, ?> ret(Object entry, Object value) {
      return h.ret(trusted(value)).eff;
    }

    @Override
    protected okay.core.Eff<?, ?> on(Object op, Rows.Rest rest, int skip) {
      return h.on(trusted(op), new K<>(op, rest, row)).eff;
    }

    @Override
    protected boolean once() {
      return h instanceof Once;
    }
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
    protected okay.core.Eff<?, ?> ret(Object entry, Object value) {
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
