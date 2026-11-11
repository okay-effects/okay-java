package okay.rows;

import static okay.rows.Comp.DOOR;

import java.util.function.Function;
import okay.core.Rows;

/**
 * CONTROL AND AUDIT OF EFFECTS. A sensitive effect ({@link Audited}) is answered only under its audit: the body that
 * asks it is put under {@link #audit}, whose interceptor sees each operation and passes it on, changed or not, to the
 * place {@code Audited<S>} outside; {@link #handle} answers it there. Every one of the wrong programs is refused by
 * javac: a sensitive operation asked with {@code perform}, answered by a handler written with {@code handle}, a
 * program run that asks it unaudited, a handler of {@code Audited<S>} given a program that did not pass an audit
 * ({@code src/test/negative/rows}).
 *
 * <p>{@link #observe} puts an observer around a program: every operation of every effect on its way out. A plain
 * effect is intercepted by {@link Effects#intercept} (by its class) or at its place ({@code handle(.., under(p))}).
 */
public final class Audit {
  private Audit() {}

  /** an operation of a sensitive effect asked: its effect the first of the row, as {@link Effects#perform}'s */
  public static <S, X, O extends Audited.Op<S, X, ? super O>> Comp<X, Eff<S, ?>> perform(O op) {
    return new Comp<>(DOOR.ask(op, 0));
  }

  /** THE AUDIT of the sensitive effect {@code S}, the first of the body's row: each of its operations through
   * {@code i}, which passes it on to the place of {@code Audited<S>}, the first of the row outside, {@code T}.
   * {@code effect} names {@code S} */
  public static <S, A, B, T extends Eff<Audited<S>, ?>> Comp<B, T> audit(
      Class<? super S> effect, Interceptor<S, A, B, T> i, Comp<A, ? super Eff<S, T>> body) {
    Interceptor.Pass<S> pass = new Interceptor.Pass<>();
    return new Comp<>(DOOR.handle(new Clauses<>(true, i::ret,
        (op, rest, skip) -> i.on(Handlers.trusted(op), pass, new Audited.Rest<>(op, rest))), null, body.eff));
  }

  /** the handler of a sensitive effect {@code S}: given the operations an audit passes on, with the rest */
  public interface Handler<S, A, B, R> {
    Comp<B, R> ret(A a);

    Comp<B, R> on(S op, Audited.Rest<S, B, R> k);
  }

  /** the operations of the sensitive effect {@code S} answered by {@code h}: those an audit inside passed on to the
   * place of {@code Audited<S>}, the first of the program's row; it leaves the row */
  public static <S, A, B, R> Comp<B, R> handle(
      Class<? super S> effect, Handler<S, A, B, R> h, Comp<A, ? super Eff<Audited<S>, R>> p) {
    return new Comp<>(DOOR.handle(new Clauses<>(true, h::ret,
        (op, rest, skip) -> h.on(Handlers.trusted(op), new Audited.Rest<>(op, rest))), null, p.eff));
  }

  /** AN OBSERVER around {@code p}: every operation that goes out of it, of whatever effect, seen first. It is no
   * place in the row: the row is the same outside */
  public static <A, B, R> Comp<B, R> observe(Observer<A, B, R> o, Comp<A, ? super R> p) {
    return new Comp<>(DOOR.handle(new Clauses<>(false, o::ret,
        (op, rest, skip) -> o.on(new Observer.Seen<>(op, skip, rest))), null, p.eff));
  }

  /** a general handler of the machine's from its two clauses */
  private interface Clause {
    Comp<?, ?> on(Object op, Rows.Rest rest, int skip);
  }

  private static final class Clauses<A> extends Rows.Spec {
    private final Function<A, ? extends Comp<?, ?>> ret;
    private final Clause on;

    Clauses(boolean row, Function<A, ? extends Comp<?, ?>> ret, Clause on) {
      super(row, false);
      this.ret = ret;
      this.on = on;
    }

    @Override
    protected okay.core.Eff<?, ?> ret(Object entry, Object value) {
      return ret.apply(Handlers.trusted(value)).eff;
    }

    @Override
    protected okay.core.Eff<?, ?> on(Object op, Rows.Rest rest, int skip) {
      return on.on(op, rest, skip).eff;
    }
  }
}
