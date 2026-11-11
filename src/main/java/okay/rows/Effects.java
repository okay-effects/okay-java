package okay.rows;

import static okay.rows.Comp.DOOR;

import java.util.function.Function;
import java.util.function.Supplier;
import okay.core.Rows;

/** The words of the model: {@code import static okay.rows.Effects.*}. */
public final class Effects {
  private Effects() {}

  /** a value: it asks nothing, and fits any row */
  public static <A, R> Comp<A, R> pure(A a) { return new Comp<>(okay.core.Eff.pure(a)); }

  /** an operation asked: its effect is the first of the row, whatever the rest. It goes to the handler of the
   * row's first effect: the nearest one put by {@code handle}. The operation's class {@code O} must be of the
   * effect it names: it names as itself a class above {@code O}, and that class is an {@code E} ({@link Op}).
   * Written {@code ? super O}, for javac refuses {@code Op<E, X, O>} here: it cannot see that {@code O} is an
   * {@code E}, and a type variable's bound cannot say both. */
  public static <E, X, O extends Op<E, X, ? super O>> Comp<X, Eff<E, ?>> perform(O op) {
    return new Comp<>(DOOR.ask(op, 0));
  }

  /** the computation, under one more handler: its effects are one step further — and so its operations go, each
   * one handler further out than it would */
  public static <A, R> Comp<A, Eff<?, ? extends R>> up(Comp<A, R> c) { return retyped(lifted(c, 0)); }

  /** the computation, with one more handler under its first effect: the rest of its effects are one step further —
   * the operations of the first effect go where they would, the others one handler further out */
  public static <A, E, R> Comp<A, Eff<E, ? extends Eff<?, ? extends R>>> under(Comp<A, ? super Eff<E, R>> c) {
    return retyped(lifted(c, 1));
  }

  /** the same computation, where exactly the row {@code Q} is wanted — a declared type; {@code Q} has its effects,
   * each at the same place: nothing changes at run time */
  public static <A, Q> Comp<A, Q> fit(Comp<A, ? super Q> c) { return retyped(c); }

  /** the one cast of `up`, `under` and `fit`, whose signatures say which rows are allowed */
  @SuppressWarnings("unchecked") // a row is only a type: no value of a computation depends on it
  private static <A, R> Comp<A, R> retyped(Comp<A, ?> c) { return (Comp<A, R>) c; }

  /** `c`, its operations that skip `keep` handlers or more skipping one more: an operation alone by its count,
   * anything else under a marker that the search for a handler counts */
  private static <A> Comp<A, ?> lifted(Comp<A, ?> c, int keep) {
    return new Comp<A, Object>(DOOR.lift(c.eff, keep));
  }

  /** a computation built when it is reached: a call in tail position takes no stack */
  public static <A, R> Comp<A, R> defer(Supplier<? extends Comp<A, ? super R>> p) {
    return new Comp<>(okay.core.Eff.delay(() -> p.get().eff));
  }

  /** {@code acquire}d, used, {@code release}d when the use is left: {@code ensuring} of the use */
  public static <S, A, R> Comp<A, R> bracket(Comp<S, ? super R> acquire,
      Function<S, ? extends Comp<A, ? super R>> use, Function<S, ? extends Comp<?, ? super R>> release) {
    return Effects.<S, R>fit(acquire).flatMap(s -> Effects.<A, R>fit(use.apply(s)).ensuring(() -> release.apply(s)));
  }

  /** the operations of {@code F}, the first effect of the program's row, answered by {@code h}: all those the row
   * sends here, and no other. {@code F} leaves the row: {@code R} is what is under it. An operation finds this
   * handler by its place in the row; {@code effect}, the class of the operations of {@code F}, only names the
   * effect: the machine does not test it, for the types say every operation that comes here is an {@code F}. */
  public static <F, A, B, R> Comp<B, R> handle(
      Class<? super F> effect, Handler<F, A, B, R> h, Comp<A, ? super Eff<F, R>> p) {
    return new Comp<>(DOOR.handle(new Handlers.General<>(effect, h, true), null, p.eff));
  }

  /** the same, in place, from the state {@code s0} */
  public static <F, S, A, B, R> Comp<B, R> handle(
      Class<? super F> effect, InPlace<F, S, A, B, R> h, S s0, Comp<A, ? super Eff<F, R>> p) {
    return new Comp<>(DOOR.handle(new Handlers.Local<>(effect, h, true), s0, p.eff));
  }

  /** AN INTERCEPTOR: a handler that is no place in the row, which stays as it is. It takes every operation of
   * the class {@code effect} that passes it on the way out and that is its ({@code mine}), wherever in the row
   * the operation is going; a clause may ask the operation again, and it goes on from here. Found by the class,
   * so the class must be the whole effect, {@code Class<F>}: an effect with type arguments, as {@code Reader<V>},
   * is refused — the class would not tell a {@code Reader<Integer>} from a {@code Reader<String>}. Such an effect
   * is intercepted at its place in the row: {@code handle(.., under(p))}, as {@code Reader.local}. */
  public static <F, A, B, R> Comp<B, R> intercept(Class<F> effect, Handler<F, A, B, R> h, Comp<A, ? super R> p) {
    return new Comp<>(DOOR.handle(new Handlers.General<>(effect, h, false), null, p.eff));
  }

  public static <F, S, A, B, R> Comp<B, R> intercept(
      Class<F> effect, InPlace<F, S, A, B, R> h, S s0, Comp<A, ? super R> p) {
    return new Comp<>(DOOR.handle(new Handlers.Local<>(effect, h, false), s0, p.eff));
  }

  /** a computation run to its next question that no handler in it answers */
  public static <A, R> Step<A, R> step(Comp<A, R> p) {
    try {
      return Handlers.trusted(DOOR.step(p.eff, Step.Done::new, Effects::asked));
    } catch (RuntimeException e) {
      if (DOOR.lostUnwinding(e)) throw new NoUnwinding();
      throw e;
    }
  }

  /** an operation that came to the top: the step {@code Asked} — unless it is sensitive, which nobody answers but
   * the handler under its audit */
  private static Object asked(Object op, Rows.Rest rest) {
    if (!(op instanceof Op<?, ?, ?> asked))
      throw new IllegalStateException("a sensitive operation with no audit, at the top: " + op);
    return new Step.Asked<>(asked, new Handlers.Stepped<>(rest));
  }

  /** a program with nothing left to ask, run to its value */
  public static <A> A run(Comp<A, ? super Pure> p) {
    return switch (step(p)) {
      case Step.Done<A, ?> done -> done.value();
      case Step.Asked<?, A, ?> asked -> throw new NoHandler(asked.op());
    };
  }
}
