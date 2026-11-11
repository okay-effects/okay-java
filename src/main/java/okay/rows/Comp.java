package okay.rows;

import java.lang.invoke.MethodHandles;
import java.util.function.Function;
import java.util.function.Supplier;
import okay.core.Rows;

/** A computation of an {@code A} that may ask the operations of the row {@code R}. Only data: nothing runs until
 * {@code step}. Made by the functions of {@link Effects}, never directly. Whoever takes a computation takes one
 * that asks no more than the row at hand: {@code Comp<A, ? super R>}.
 *
 * <p>On okay-java's machine: a computation is a program of {@code okay.core}, {@link okay.core.Eff}'s nodes, the row
 * a phantom as the level is there. */
public final class Comp<A, R> {
  /** the machine's door, given to this class alone */
  static final Rows.Door DOOR = Rows.door(MethodHandles.lookup());

  /** the program, at no level of its own: levels are phantom, the row stands for them here */
  final okay.core.Eff<Object, A> eff;

  @SuppressWarnings("unchecked") // the value is an A: the program was built typed, its row and level phantom
  Comp(okay.core.Eff<?, ?> eff) {
    this.eff = (okay.core.Eff<Object, A>) eff;
  }

  /** this, then what {@code f} makes of its value. The row {@code Q} is one that has the effects of both: the
   * compiler finds it. */
  public <B, Q extends R> Comp<B, Q> flatMap(Function<A, ? extends Comp<B, ? super Q>> f) {
    return new Comp<>(eff.flatMap(new Next<>(f)));
  }

  public <B, Q extends R> Comp<B, Q> map(Function<A, B> f) {
    return new Comp<>(eff.map(f));
  }

  /** the computation after this one, whose value is not needed. Where the two have different rows, write
   * {@code flatMap(done -> ..)}: javac finds the common row only through a lambda that has a parameter. */
  public <B, Q extends R> Comp<B, Q> then(Supplier<? extends Comp<B, ? super Q>> next) {
    return new Comp<>(eff.flatMap(a -> next.get().eff));
  }

  /** THIS, AND {@code finalizer} WHEN IT IS LEFT. With a value: the finalizer runs, and the value is this
   * program's. Without one — a handler outside does not go on with the rest of the program this is part of, and
   * unwinds it ({@link K#unwind}: a failure, a choice of nothing, a stream cut short): the finalizer runs then,
   * where it was installed — the handlers between it and the one that leaves still answer it, their states as the
   * path left them. Finalizers run the innermost first, each once for every path that ends or is unwound past it.
   * The finalizer is a program of the same row, made when it is run; its value is dropped. One that fails is left
   * as any program is: what waits beyond it is still unwound, and the failure goes to its handler. Not run: when
   * the rest of the program is kept and never resumed (an {@code Emit.pull} not read to its end). Run when a
   * function of the program throws (okay-java's failure, {@link okay.core.Eff#ensuring}'s guard): the reference
   * runs none then. */
  public <Q extends R> Comp<A, Q> ensuring(Supplier<? extends Comp<?, ? super Q>> finalizer) {
    return new Comp<>(DOOR.ensuring(eff, () -> finalizer.get().eff));
  }

  /** a bind's function: the next computation's program */
  private record Next<A, B>(Function<A, ? extends Comp<B, ?>> f) implements Function<A, okay.core.Eff<Object, B>> {
    @Override
    public okay.core.Eff<Object, B> apply(A a) {
      return f.apply(a).eff;
    }
  }
}
