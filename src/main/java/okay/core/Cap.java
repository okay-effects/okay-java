package okay.core;

import java.util.function.Function;

/** a capability of {@code E} at the level {@code L}, its handler's body's: its operations programs at {@code L} */
public final class Cap<L, E> {
  private final E e;

  Cap(E e) {
    this.e = e;
  }

  /** an operation: {@code cap.perform(State::get)} */
  public <X> Fx<L, X> perform(Function<? super E, Eff<X>> op) {
    return new Fx<>(op.apply(e));
  }

  /** the same capability one handler in: the type changed, nothing else */
  @SuppressWarnings("unchecked")
  public <E2> Cap<Fx.Under<L, E2>, E> lift() {
    return (Cap<Fx.Under<L, E2>, E>) (Cap<?, E>) this;
  }
}
