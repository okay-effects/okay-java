package okay.std;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Function;
import okay.core.Comp;
import okay.core.Prompt;

/** values emitted one by one: a generator. Handled three ways — each value to a function of the context outside,
 * answered in place, {@link #forEach}; into a list, {@link #collect}; or lazily, the body suspended at each emit —
 * {@link #generate}, of the consumer's own type and under any effects, and on it a {@link Iterator},
 * {@link #iterator}, and a {@link java.util.stream.Stream}, {@link #stream} */
@FunctionalInterface
public interface Emit<L, X> {
  Comp<L, Void> emit(X x);

  /** this capability at a level further in */
  @SuppressWarnings("unchecked")
  default <N extends L> Emit<N, X> at() {
    return (Emit<N, X>) (Emit<?, X>) this;
  }

  /** the body of a generator: at a fresh level, given the capability there */
  interface Body<L, X, A> {
    <M extends L> Comp<M, A> in(Emit<M, X> e);
  }

  /** each value to {@code each}, its effects the context's: {@code shift0 k. each(x) >>= k} is {@code each(x)}
   * performed in place — the handler is the function, no prompt */
  static <L, X, A> Comp<L, A> forEach(Body<L, X, A> body, Function<? super X, ? extends Comp<? super L, Void>> each) {
    return Comp.delay(() -> body.<L>in(x -> each.apply(x).at()));
  }

  /** the values in a list, one per run of the handler: a k resumed twice from outside adds to the same */
  static <L, X, A> Comp<L, List<X>> collect(Body<L, X, A> body) {
    return Comp.delay(() -> {
      List<X> out = new ArrayList<>();
      return Emit.<L, X, A>forEach(body, x -> { out.add(x); return Comp.<L, Void>pure(null); }).map(_ -> out);
    });
  }

  /** what a generator's consumer makes of an element and the rest — the rest a program at the generator's own
   * level, run when it pulls: so the consumer, as the body, may be under effects still to be handled */
  @FunctionalInterface
  interface Next<L, X, G> {
    G next(X x, Comp<L, G> rest);
  }

  /** okay-core's {@code generate}: the body as a lazy generator of the consumer's own type {@code G} — each element
   * with the rest by {@code next}, the end {@code done}. Nothing runs past an element until the rest is run. At any
   * level: the body's other effects go to their handlers outside, from the rest as from the start */
  static <L, X, A, G> Comp<L, G> generate(Body<L, X, A> body, Next<L, X, G> next, G done) {
    return Comp.handle(new Comp.Body<L, A, G>() {
      public <M extends L> Comp<M, A> in(Prompt<L, M, G> p) {
        return body.in(x -> p.shift0(k -> Comp.pure(next.next(x, k.apply(null)))));
      }
    }, _ -> Comp.pure(done));
  }

  /** the body run as far as its next emit at each {@code next()}: the body pure but for {@code Emit} */
  static <X, A> Iterator<X> iterator(Body<Comp.Top, X, A> body) {
    Comp<Comp.Top, Step<X>> start = generate(body, Step.Next::new, new Step.End<>());
    return new Iterator<>() {
      Step<X> step;
      Comp<Comp.Top, Step<X>> rest = start;

      private Step<X> step() {
        if (step == null) step = Comp.runIsolated(rest);
        return step;
      }

      public boolean hasNext() {
        return step() instanceof Step.Next;
      }

      public X next() {
        if (!(step() instanceof Step.Next<X>(var x, var r))) throw new NoSuchElementException();
        step = null;
        rest = r;
        return x;
      }
    };
  }

  /** the body as a lazy, sequential {@link java.util.stream.Stream}: an element pulled, a run of the rest — so
   * {@code limit}, {@code takeWhile} and the like stop the body where they stop */
  static <X, A> java.util.stream.Stream<X> stream(Body<Comp.Top, X, A> body) {
    return java.util.stream.StreamSupport.stream(
        java.util.Spliterators.spliteratorUnknownSize(iterator(body), java.util.Spliterator.ORDERED), false);
  }

  /** where an iterator is: done, or at a value with the rest */
  sealed interface Step<X> {
    record End<X>() implements Step<X> {}

    record Next<X>(X value, Comp<Comp.Top, Step<X>> rest) implements Step<X> {}
  }
}
