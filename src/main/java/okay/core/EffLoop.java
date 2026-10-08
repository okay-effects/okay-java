package okay.core;

import java.util.function.Function;

/** THE LOOP of {@link Eff}: {@link Loop0}'s, a delimiter its prompt with it — a capture to another prompt crosses it */
@SuppressWarnings({"unchecked", "rawtypes"})
final class EffLoop {
  private EffLoop() {}

  record Return<A>(A value) implements Eff<A> {}

  record Bind<A, B>(Eff<A> m, Function<? super A, ? extends Eff<B>> f) implements Eff<B> {}

  record Map<A, B>(Eff<A> m, Function<? super A, ? extends B> f) implements Eff<B> {}

  record Shift0<X, R>(Prompt<R> prompt, Function<Function<X, Eff<R>>, Eff<R>> f) implements Eff<X> {}

  record Handle<A, R>(Function<? super Prompt<R>, ? extends Eff<A>> body, Function<? super A, ? extends Eff<R>> ret)
      implements Eff<R> {}

  /** {@code m} with the rest as a stack already: what applying a captured k makes */
  record Push<B>(Eff<?> m, Frames k) implements Eff<B> {}

  /** a captured k applied to {@code x}: {@code ret $ K[x]} under {@code prompt} again — one node */
  record Resume<B>(Object x, Frames k, Prompt<?> prompt, Function<Object, Object> ret) implements Eff<B> {}

  static final Eff<Object> UNIT = new Return<>(null);

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

  sealed interface Head {
    record Done(Object value) implements Head {}

    /** a {@code shift0} to {@code prompt}, the rest up to here {@code k} */
    record Cut(Prompt<?> prompt, Function f, Frames k) implements Head {}
  }

  static Head step(Eff<?> c) {
    return c instanceof Push p ? loop(p.m(), p.k()) : loop(c, Frames.END);
  }

  private static Head loop(Eff<?> program, Frames frames) {
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
              return new Head.Done(v);
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
        case Shift0 s -> { return new Head.Cut(s.prompt(), s.f(), k); }
        case Handle h -> {
          Prompt<?> p = new Prompt<>();
          Head head = step((Eff) h.body().apply(p));
          if (head instanceof Head.Cut cut && cut.prompt() != p) return lifted(cut, p, h.ret(), k);
          c = delimited(head, p, h.ret());
        }
        case Resume r -> {
          Head head = resumed(r.x(), r.k());
          if (head instanceof Head.Cut cut && cut.prompt() != r.prompt()) return lifted(cut, r.prompt(), r.ret(), k);
          c = delimited(head, r.prompt(), r.ret());
        }
      }
    }
  }

  /** the delimiter's body at its head: a value is answered by {@code ret}; a capture to this prompt has its clause
   * here, outside, the rest under the delimiter again as k */
  private static Eff<?> delimited(Head head, Prompt<?> prompt, Function<Object, Object> ret) {
    return switch (head) {
      case Head.Done d -> (Eff) ret.apply(d.value());
      case Head.Cut cut -> (Eff) cut.f().apply(new Resumed(cut.k(), prompt, ret));
    };
  }

  /** a capture to another prompt CROSSES the delimiter — a lift, {@code shift0 k'. shift0 ... >>= k'}, as one step:
   * the same capture, its rest the rest in here under the delimiter again, then the rest out here, {@code k} */
  private static Head lifted(Head.Cut cut, Prompt<?> prompt, Function<Object, Object> ret, Frames k) {
    return new Head.Cut(cut.prompt(), cut.f(), new Frames.Then(new Resumed(cut.k(), prompt, ret), k));
  }

  private static Head resumed(Object x, Frames k) {
    return switch (k) {
      case Frames.End _ -> new Head.Done(x);
      case Frames.Then(var f, var next) -> loop((Eff) f.apply(x), next);
      case Frames.Mapped(var f, var next) -> loop(new Return<>(f.apply(x)), next);
    };
  }

  /** a captured rest as k: applied, the stack it holds resumed under the delimiter of {@code prompt} again */
  private record Resumed(Frames k, Prompt<?> prompt, Function<Object, Object> ret) implements Function<Object, Eff<?>> {
    @Override
    public Eff<?> apply(Object x) {
      return new Resume<>(x, k, prompt, ret);
    }
  }

  static <A> A run(Eff<A> p) {
    return switch (step(p)) {
      case Head.Done d -> (A) d.value();
      case Head.Cut _ -> throw new IllegalStateException(
          "an operation with no handler in force: its capability was used outside the handler that made it");
    };
  }
}
