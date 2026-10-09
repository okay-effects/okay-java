package okay.core;

import java.util.function.Function;
import java.util.function.Supplier;

/** {@link Eff}'S NODES, what a program is made of, and the frames a value goes through — run by the {@link Machine} */
@SuppressWarnings({"unchecked", "rawtypes"})
final class Nodes {
  private Nodes() {}

  record Return<L, A>(A value) implements Eff<L, A> {}

  record Bind<L, A, B>(Eff<L, A> m, Function f) implements Eff<L, B> {}

  record Map<L, A, B>(Eff<L, A> m, Function<? super A, ? extends B> f) implements Eff<L, B> {}

  /** a program built when the loop gets to it: a tail call, one node */
  record Delay<L, A>(Supplier<? extends Eff<?, A>> c) implements Eff<L, A> {}

  /** a value computed when the loop gets to it: an operation answered in place from a cell, one node */
  record Sync<L, A>(Supplier<? extends A> a) implements Eff<L, A> {}

  record Shift0<M, X>(Prompt<?, ?, ?> prompt, Function f) implements Eff<M, X> {
    /** an operation bound: one node, {@link Suspend} */
    @Override
    public <B> Eff<M, B> flatMap(Function<? super X, ? extends Eff<? super M, B>> g) {
      return new Suspend<>(prompt, f, g);
    }
  }

  /** a {@code shift0} with the rest bound to its value: {@code Bind(Shift0(p, f), k)} as one node — what
   * {@code flatMap} on an operation makes, and what a capture crossing a delimiter goes on outside as */
  record Suspend<L, B>(Prompt<?, ?, ?> prompt, Function f, Function k) implements Eff<L, B> {}

  record Handle<L, A, R>(Eff.Body<L, A, R> body, Function ret) implements Eff<L, R> {}

  /** a delimiter under a prompt there already: {@code p.reset(body)}, its answer the body's value */
  record Reset<L, R>(Prompt<?, ?, ?> prompt, Eff<?, R> body) implements Eff<L, R> {}

  /** {@code m} with the rest as a stack already: what applying a captured k makes */
  record Push<L, B>(Eff<?, ?> m, Frames k) implements Eff<L, B> {}

  sealed interface Frames extends Function<Object, Eff<?, ?>> {
    Frames END = new End();

    record End() implements Frames {}

    record Then(Function f, Frames next) implements Frames {}

    record Mapped(Function f, Frames next) implements Frames {}

    @Override
    default Eff<?, ?> apply(Object a) {
      return switch (this) {
        case End _ -> new Return<>(a);
        case Then(var f, var next) -> next instanceof End ? (Eff) f.apply(a) : new Push<>((Eff) f.apply(a), next);
        case Mapped(var f, var next) ->
            next instanceof End ? new Return<>(f.apply(a)) : new Push<>(new Return<>(f.apply(a)), next);
      };
    }
  }

  /** a value through the maps on top of the stack, to the first bind — its program and the rest — or to the end. A
   * method of its own, small: compiled apart from the machine's loop, its own loop gets the JIT's full care (okay-core's
   * {@code feed}) */
  record Fed(Eff<?, ?> c, Object v, Frames k) {}

  static Fed feed(Object v, Frames k) {
    while (k instanceof Frames.Mapped(var f, var next)) {
      v = f.apply(v);
      k = next;
    }
    return k instanceof Frames.Then(var f, var next) ? new Fed((Eff) f.apply(v), null, next) : new Fed(null, v, k);
  }

}
