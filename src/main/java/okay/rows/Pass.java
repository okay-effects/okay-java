package okay.rows;

/** THE WAY ON past an audit of {@code E}: the operation asked outside it, of the handler {@link Audit#handle} put.
 * Only {@link Audit#audit} makes one. */
public final class Pass<E, R> {
  Pass() {}

  public <X> Comp<X, Audited<E, R>> pass(Sensitive.Op<E, X, ?> op) { return new Comp<>(Comp.DOOR.ask(op, 0)); }
}
