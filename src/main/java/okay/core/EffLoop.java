package okay.core;

import java.util.function.Function;

/** THE LOOP of {@link Eff}: {@link Loop0}'s, a delimiter its prompt with it — a capture to another prompt crosses
 * it. Each delimiter runs its body in a loop of its own, to where it leaves: no head made between, one Java frame
 * per delimiter in force, none per capture */
@SuppressWarnings({"unchecked", "rawtypes"})
final class EffLoop {
  private EffLoop() {}

  record Return<A>(A value) implements Eff<A> {}

  record Bind<A, B>(Eff<A> m, Function<? super A, ? extends Eff<B>> f) implements Eff<B> {}

  record Map<A, B>(Eff<A> m, Function<? super A, ? extends B> f) implements Eff<B> {}

  record Shift0<X, R>(Prompt<R> prompt, Function<Function<X, Eff<R>>, Eff<R>> f) implements Eff<X> {
    /** an operation bound: one node, {@link Suspend} */
    @Override
    public <B> Eff<B> flatMap(Function<? super X, ? extends Eff<B>> g) {
      return new Suspend<>(prompt, f, g);
    }
  }

  /** a {@code shift0} with the rest bound to its value: {@code Bind(Shift0(p, f), k)} as one node — what
   * {@code flatMap} on an operation makes, and what a capture crossing a delimiter goes on outside as */
  record Suspend<B>(Prompt<?> prompt, Function f, Function k) implements Eff<B> {}

  record Handle<A, R>(Function<? super Prompt<R>, ? extends Eff<A>> body, Function<? super A, ? extends Eff<R>> ret)
      implements Eff<R> {}

  /** {@code m} with the rest as a stack already: what applying a captured k makes */
  record Push<B>(Eff<?> m, Frames k) implements Eff<B> {}

  /** a clause's k applied to {@code x}: {@code ret $ K[x]} under {@code prompt} again — one node */
  record Resume<B>(Object x, Function k, Prompt<?> prompt, Function<Object, Object> ret) implements Eff<B> {}

  static final Eff<Object> UNIT = new Return<>(null);

  /** the prompt of the top: no capture is to it */
  private static final Prompt<Object> TOP = new Prompt<>();

  sealed interface Frames extends Function<Object, Eff<?>> {
    Frames END = new End();

    record End() implements Frames {}

    record Then(Function f, Frames next) implements Frames {}

    record Mapped(Function f, Frames next) implements Frames {}

    @Override
    default Eff<?> apply(Object a) {
      return switch (this) {
        case End _ -> new Return<>(a);
        case Then(var f, var next) -> next instanceof End ? (Eff) f.apply(a) : new Push<>((Eff) f.apply(a), next);
        case Mapped(var f, var next) ->
            next instanceof End ? new Return<>(f.apply(a)) : new Push<>(new Return<>(f.apply(a)), next);
      };
    }
  }

  /** THE DELIMITER'S LOOP, {@code ret $ c}, {@code k} bound under it: run to where it leaves the delimiter — a
   * value, answered by {@code ret}; a capture to this prompt, its clause in the delimiter's place, NOT under it, the
   * rest under it again as k; a capture to another, the same capture outside, the rest re-delimited when answered.
   * What leaves is the program outside, made here */
  private static Eff<?> delimited(Eff<?> program, Frames frames, Prompt<?> prompt, Function<Object, Object> ret) {
    Eff c = program;
    Frames k = frames;
    while (true) {
      switch (c) {
        case Return r -> {
          Object v = r.value();
          while (true) {
            if (k instanceof Frames.Then(var f, var next)) {
              c = (Eff) f.apply(v);
              k = next;
              break;
            } else if (k instanceof Frames.Mapped(var f, var next)) {
              v = f.apply(v);
              k = next;
            } else {
              return (Eff) ret.apply(v);
            }
          }
        }
        case Bind b -> {
          if (b.m() instanceof Return r) {
            c = (Eff) b.f().apply(r.value());
          } else {
            k = new Frames.Then(b.f(), k);
            c = b.m();
          }
        }
        case Map m -> {
          k = new Frames.Mapped(m.f(), k);
          c = m.m();
        }
        case Push p -> {
          k = k instanceof Frames.End ? p.k() : new Frames.Then(p.k(), k);
          c = p.m();
        }
        case Shift0 s -> { return capture(s.prompt(), s.f(), k, prompt, ret); }
        case Suspend s -> {
          return capture(s.prompt(), s.f(), k instanceof Frames.End ? s.k() : new Frames.Then(s.k(), k), prompt, ret);
        }
        case Handle h -> {
          Prompt<?> p = new Prompt<>();
          c = delimited((Eff) h.body().apply(p), Frames.END, p, h.ret());
        }
        case Resume r -> c = resume(r.x(), r.k(), r.prompt(), r.ret());
      }
    }
  }

  /** a capture of the rest {@code k} at the delimiter of {@code prompt}: its own — the clause, k the rest under the
   * delimiter again, lazily; another's — the same capture outside, the rest re-delimited at once when answered */
  private static Eff<?> capture(Prompt<?> to, Function f, Function k, Prompt<?> prompt, Function<Object, Object> ret) {
    if (to == prompt) return (Eff) f.apply(new Resumed(k, prompt, ret));
    if (prompt == TOP) throw new IllegalStateException(
        "an operation with no handler in force: its capability was used outside the handler that made it");
    return new Suspend<>(to, f, new Redelimit(k, prompt, ret));
  }

  /** {@code x} through the rest {@code k} under the delimiter of {@code prompt}: a stack's first frame applied here */
  private static Eff<?> resume(Object x, Function k, Prompt<?> prompt, Function<Object, Object> ret) {
    return switch (k) {
      case Frames.End _ -> (Eff) ret.apply(x);
      case Frames.Then(var f, var next) -> delimited((Eff) f.apply(x), next, prompt, ret);
      case Frames.Mapped(var f, var next) -> delimited(new Return<>(f.apply(x)), next, prompt, ret);
      default -> delimited((Eff) k.apply(x), Frames.END, prompt, ret);
    };
  }

  /** a clause's k: applied, the rest resumed under the delimiter again — LAZILY, a node, so a clause that keeps k
   * (a generator's next step) runs nothing by making it */
  private record Resumed(Function k, Prompt<?> prompt, Function<Object, Object> ret) implements Function<Object, Eff<?>> {
    @Override
    public Eff<?> apply(Object x) {
      return new Resume<>(x, k, prompt, ret);
    }
  }

  /** the rest of a delimiter's body once the capture that crossed it is answered outside: under the delimiter
   * again AT ONCE — applied by a loop already, one call deep per delimiter crossed */
  private record Redelimit(Function k, Prompt<?> prompt, Function<Object, Object> ret) implements Function<Object, Eff<?>> {
    @Override
    public Eff<?> apply(Object x) {
      return resume(x, k, prompt, ret);
    }
  }

  static <A> A run(Eff<A> p) {
    return ((Return<A>) delimited(p, Frames.END, TOP, Return::new)).value();
  }
}
