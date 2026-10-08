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
public interface Emit<X> {
  Eff<Void> emit(X x);

  /** each value to {@code each}, its effects the context's: {@code shift0 k. each(x) >>= k} is {@code each(x)}
   * performed in place — the handler is the function, no prompt */
  static <X, A> Eff<A> forEach(Function<Emit<X>, Eff<A>> body, Function<? super X, ? extends Eff<Void>> each) {
    return Eff.delay(() -> body.apply(each::apply));
  }

  /** the values in a list, one per run of the handler: a k resumed twice from outside adds to the same */
  static <X, A> Eff<List<X>> collect(Function<Emit<X>, Eff<A>> body) {
    return Eff.delay(() -> {
      List<X> out = new ArrayList<>();
      return forEach(body, x -> { out.add(x); return Eff.pure(null); }).map(_ -> out);
    });
  }

  /** the body run as far as its next emit at each {@code next()}: the body pure but for {@code Emit} */
  static <X, A> Iterator<X> iterator(Function<Emit<X>, Eff<A>> body) {
    Eff<Step<X>> start = Eff.handle(
        (Prompt<Step<X>> p) -> body.apply(x -> p.shift0(k -> Eff.pure(new Step.Next<>(x, k)))),
        _ -> Eff.pure(new Step.End<>()));
    return new Iterator<>() {
      Step<X> step;
      Eff<Step<X>> rest = start;

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

    record Next<X>(X value, Function<Void, Eff<Step<X>>> rest) implements Step<X> {}
  }
}
