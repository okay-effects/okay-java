package okay.std;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Function;
import okay.core.Eff;
import okay.core.Prompt;

/** values emitted one by one: a generator. Handled three ways — each value to a function of the context outside,
 * answered in place, {@link #forEach}; into a list, {@link #collect}; or as a lazy {@link Iterator},
 * {@link #iterator}, the body suspended at each emit, a k kept until {@code next()} */
@FunctionalInterface
public interface Emit<L, X> {
  Eff<L, Void> emit(X x);

  /** this capability at a level further in */
  @SuppressWarnings("unchecked")
  default <N extends L> Emit<N, X> at() {
    return (Emit<N, X>) (Emit<?, X>) this;
  }

  /** the body of a generator: at a fresh level, given the capability there */
  interface Body<L, X, A> {
    <M extends L> Eff<M, A> in(Emit<M, X> e);
  }

  /** each value to {@code each}, its effects the context's: {@code shift0 k. each(x) >>= k} is {@code each(x)}
   * performed in place — the handler is the function, no prompt */
  static <L, X, A> Eff<L, A> forEach(Body<L, X, A> body, Function<? super X, ? extends Eff<? super L, Void>> each) {
    return Eff.delay(() -> body.<L>in(x -> each.apply(x).at()));
  }

  /** the values in a list, one per run of the handler: a k resumed twice from outside adds to the same */
  static <L, X, A> Eff<L, List<X>> collect(Body<L, X, A> body) {
    return Eff.delay(() -> {
      List<X> out = new ArrayList<>();
      return Emit.<L, X, A>forEach(body, x -> { out.add(x); return Eff.<L, Void>pure(null); }).map(_ -> out);
    });
  }

  /** the body run as far as its next emit at each {@code next()}: the body pure but for {@code Emit} */
  static <X, A> Iterator<X> iterator(Body<Eff.Top, X, A> body) {
    Eff<Eff.Top, Step<X>> start = Eff.handle(new Eff.Body<Eff.Top, A, Step<X>>() {
      public <M extends Eff.Top> Eff<M, A> in(Prompt<Eff.Top, M, Step<X>> p) {
        return body.in(x -> p.shift0(k -> Eff.pure(new Step.Next<>(x, k))));
      }
    }, _ -> Eff.pure(new Step.End<>()));
    return new Iterator<>() {
      Step<X> step;
      Eff<Eff.Top, Step<X>> rest = start;

      private Step<X> step() {
        if (step == null) step = Eff.run(rest);
        return step;
      }

      public boolean hasNext() {
        return step() instanceof Step.Next;
      }

      public X next() {
        if (!(step() instanceof Step.Next<X>(var x, var k))) throw new NoSuchElementException();
        step = null;
        rest = k.apply(null);
        return x;
      }
    };
  }

  /** where the generator is: done, or at a value with the rest */
  sealed interface Step<X> {
    record End<X>() implements Step<X> {}

    record Next<X>(X value, Function<Void, Eff<Eff.Top, Step<X>>> rest) implements Step<X> {}
  }
}
