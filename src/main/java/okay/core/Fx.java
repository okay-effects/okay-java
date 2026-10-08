package okay.core;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A PROGRAM AT A LEVEL, {@code L}: {@link Eff} with its ROW in the type — the handlers in force, innermost first,
 * {@code Under<Under<Top, E1>, E2>}, each a delimiter: Cont0's levels, the nesting of its answer types, with the
 * answers left out. A capability is at the level of its handler's body, {@link Cap}{@code <L, E>}, and gives
 * programs at that level only; a program or a capability of a level outside goes in by {@link #lift} — one per
 * handler, as a {@code shift0} over each delimiter crossed, but here a change of type only: at run time an
 * operation finds its handler by prompt, a lift is nothing.
 *
 * <p>So the type says what a capability cannot do: be used where its handler is not in force — {@link #run} takes
 * {@code Fx<Top, A>}, and a capability out of its handler is at a level no program outside is at. Two handlers of one
 * effect at the same level remain apart only by prompt, at run time.
 */
public record Fx<L, A>(Eff<A> eff) {

  /** the level outside every handler */
  public static final class Top {
    private Top() {}
  }

  /** the level under a handler of {@code E}, in force over {@code L} */
  public static final class Under<L, E> {
    private Under() {}
  }

  /** an erased handler of {@code E}, as {@code okay.std}'s are: the body given the capability, handled */
  @FunctionalInterface
  public interface Handler<E, A, R> {
    Eff<R> handle(Function<E, Eff<A>> body);
  }

  public <B> Fx<L, B> flatMap(Function<? super A, Fx<L, B>> f) {
    return new Fx<>(eff.flatMap(a -> f.apply(a).eff));
  }

  public <B> Fx<L, B> map(Function<? super A, ? extends B> f) {
    return new Fx<>(eff.map(f));
  }

  public <B> Fx<L, B> then(Fx<L, B> next) {
    return new Fx<>(eff.then(next.eff));
  }

  public static <L, A> Fx<L, A> pure(A a) {
    return new Fx<>(Eff.pure(a));
  }

  public static <L, A> Fx<L, A> delay(Supplier<Fx<L, A>> c) {
    return new Fx<>(Eff.delay(() -> c.get().eff));
  }

  /** a program of the level outside, under one handler more: the type changed, nothing else */
  @SuppressWarnings("unchecked")
  public static <L, E, A> Fx<Under<L, E>, A> lift(Fx<L, A> m) {
    return (Fx<Under<L, E>, A>) (Fx<?, A>) m;
  }

  /** the body under the handler {@code h}, one level in, given its capability at that level */
  public static <L, E, A, R> Fx<L, R> handle(Handler<E, A, R> h, Function<Cap<Under<L, E>, E>, Fx<Under<L, E>, A>> body) {
    return new Fx<>(h.handle(e -> body.apply(new Cap<>(e)).eff));
  }

  /** a body at a level of its own, {@code M}, FRESH — a type variable no one outside the body can name: runST's
   * rank-2 trick. Its capability is {@code Cap<M, E>}; the level outside comes in by {@code up}. Java writes it as a
   * generic method, so an anonymous class, not a lambda */
  public interface Body<L, E, A> {
    <M> Fx<M, A> apply(Cap<M, E> cap, Lift<L, M> up);
  }

  /** {@code L} is outside {@code M}, one handler or more: a program or a capability of {@code L} goes in */
  public static final class Lift<L, M> {
    private static final Lift<?, ?> ANY = new Lift<>();

    private Lift() {}

    @SuppressWarnings("unchecked")
    public <A> Fx<M, A> fx(Fx<L, A> m) {
      return (Fx<M, A>) (Fx<?, A>) m;
    }

    @SuppressWarnings("unchecked")
    public <E> Cap<M, E> cap(Cap<L, E> c) {
      return (Cap<M, E>) (Cap<?, E>) c;
    }

    /** {@code L} outside {@code M} outside {@code N}: {@code L} outside {@code N} */
    @SuppressWarnings("unchecked")
    public <N> Lift<L, N> then(Lift<M, N> inner) {
      return (Lift<L, N>) ANY;
    }
  }

  /** the body under the handler {@code h} at a fresh level: its capability cannot leave it — not as a value, its
   * type unnameable outside; not into another handler's body, whose level is another variable */
  @SuppressWarnings("unchecked")
  public static <L, E, A, R> Fx<L, R> scoped(Handler<E, A, R> h, Body<L, E, A> body) {
    return new Fx<>(h.handle(e -> body.<Object>apply(new Cap<>(e), (Lift<L, Object>) Lift.ANY).eff));
  }

  /** a program with no handler left to reach */
  public static <A> A run(Fx<Top, A> p) {
    return Eff.run(p.eff);
  }
}
