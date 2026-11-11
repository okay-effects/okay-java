package okay.rows;

import okay.core.Rows;

/**
 * AN OBSERVER OF EVERY EFFECT ({@link #observe}): each operation that goes out of the body it is around,
 * whatever its effect and wherever in the row it is going, stops at it first — seen as data, with the rest of the
 * body. Its clause runs outside it, over the same row: it may tell, pass the operation on as it is
 * ({@link Seen#proceed}: to where it was going, its answer typed), give the rest that answer or another of its type,
 * or refuse (not resume; fail, or answer for the body). Its own operations — a trail's — are not shown to it, nor
 * a sensitive effect's: those its audit sees, and only it ({@link Audit}). Beyond okay-effects' Java kernel, whose
 * {@link Effects#observe} shows one effect, at its place: okay-core's observer.
 */
public interface Observer<A, B, R> {
  Comp<B, R> ret(A a);

  <X> Comp<B, R> on(Seen<X, B, R> seen);

  /** the observer {@code o} around {@code p}: no place in the row, which is the same outside */
  static <A, B, R> Comp<B, R> observe(Observer<A, B, R> o, Comp<A, ? super R> p) {
    return new Comp<>(Comp.DOOR.handle(new Rows.Spec(false, false) {
      @Override
      protected boolean accepts(Object op) {
        return !(op instanceof Sensitive.Op);
      }

      @Override
      protected okay.core.Eff<?, ?> ret(Object entry, Object value) {
        return o.ret(Handlers.<A>trusted(value)).eff;
      }

      @Override
      protected okay.core.Eff<?, ?> on(Object op, Rows.Rest rest, int skip) {
        return o.on(new Seen<>(op, skip, rest)).eff;
      }
    }, null, p.eff));
  }

  /** An operation seen, answered by an {@code X}: what it is, the way on to its handler, and the rest. */
  final class Seen<X, B, R> {
    private final Object op;
    private final int skip;
    private final Rows.Rest rest;

    Seen(Object op, int skip, Rows.Rest rest) {
      this.op = op;
      this.skip = skip;
      this.rest = rest;
    }

    /** the operation, as it is: data */
    public Object op() {
      return op;
    }

    /** the effect's name: the sealed interface the operation is a case of */
    public String effect() {
      Class<?> enclosing = op.getClass().getEnclosingClass();
      return enclosing != null ? enclosing.getSimpleName() : op.getClass().getSimpleName();
    }

    /** the operation as text: its {@code toString} */
    public String describe() {
      return op.toString();
    }

    /** the operation asked past the observer, to where it was going: its answer */
    public Comp<X, R> proceed() {
      return new Comp<>(Comp.DOOR.ask(op, skip));
    }

    /** the rest of the body, given the answer */
    public Comp<B, R> resume(X answer) {
      return new Comp<>(Comp.DOOR.resume(rest, answer));
    }

    /** the rest of the body left: its finalizers run, nothing else of it */
    public Comp<Void, R> unwind() {
      return new Comp<>(Comp.DOOR.unwind(rest, Rows.NONE));
    }
  }
}
