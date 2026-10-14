package okay.core;

import java.util.function.Function;

/** THE LOOP of {@link Cont}: a tree read to its head, binds reassociated and followed, a delimiter entered, in
 * constant stack — one Java frame per delimiter in force, none per capture */
@SuppressWarnings({"unchecked", "rawtypes"})
final class Loop {
  private Loop() {}

  /** {@code m} with the rest as a stack already: what applying a captured k makes. Only {@link Frames} make it */
  record Push<I, O, B>(Cont<?, ?, ?> m, Frames k) implements Cont<I, O, B> {}

  /** a captured k applied to {@code x}: {@code ret $ K[x]}, {@code K} the stack — one node */
  record Resume<I, O, B>(Object x, Frames k, Function<Object, Object> ret) implements Cont<I, O, B> {}

  /** the loop's stack, innermost first; as a function, the rest */
  sealed interface Frames extends Function<Object, Cont<?, ?, ?>> {
    Frames END = new End();

    record End() implements Frames {}

    record Then(Function f, Frames next) implements Frames {}

    record Mapped(Function f, Frames next) implements Frames {}

    @Override
    default Cont<?, ?, ?> apply(Object a) {
      return switch (this) {
        case End _ -> new Cont.Return<>(a);
        case Then(var f, var next) -> next instanceof End ? (Cont) f.apply(a) : new Push<>((Cont) f.apply(a), next);
        case Mapped(var f, var next) ->
            next instanceof End ? new Cont.Return<>(f.apply(a)) : new Push<>(new Cont.Return<>(f.apply(a)), next);
      };
    }
  }

  /** THE DELIMITER'S LOOP, {@code ret $ c}, {@code k} bound under it, run to where it leaves the delimiter: a value,
   * answered by {@code ret}; a capture, its body the delimiter's program outside, in its place — NOT put under a
   * delimiter, as a shift's is put under its reset again — the rest under it again, {@code ret} included, as k. What
   * leaves is made here, no head between; nothing wraps the body, so nothing nests */
  private static Object delimited(Cont<?, ?, ?> program, Frames frames, Function<Object, Object> ret) {
    Cont c = program;
    Frames k = frames;
    while (true) {
      switch (c) {
        case Cont.Return r -> {
          Object v = r.value();
          while (true) {
            if (k instanceof Frames.Then(var f, var next)) {
              c = (Cont) f.apply(v);
              k = next;
              break;
            } else if (k instanceof Frames.Mapped(var f, var next)) {
              v = f.apply(v);
              k = next;
            } else {
              return ret.apply(v);
            }
          }
        }
        case Cont.Bind b -> {
          if (b.m() instanceof Cont.Return r) {
            c = (Cont) b.f().apply(r.value());
          } else {
            k = new Frames.Then(b.f(), k);
            c = b.m();
          }
        }
        case Cont.Map m -> {
          k = new Frames.Mapped(m.f(), k);
          c = m.m();
        }
        case Push p -> {
          k = k instanceof Frames.End ? p.k() : new Frames.Then(p.k(), k);
          c = p.m();
        }
        case Cont.Shift0 s -> { return s.f().apply(new Resumed(k, ret)); }
        case Cont.Dollar d -> c = (Cont) delimited(d.body(), Frames.END, d.ret());
        case Resume r -> c = (Cont) resume(r.x(), r.k(), r.ret());
      }
    }
  }

  /** {@code x} through a resumed stack under its delimiter: its first frame applied here */
  private static Object resume(Object x, Frames k, Function<Object, Object> ret) {
    return switch (k) {
      case Frames.End _ -> ret.apply(x);
      case Frames.Then(var f, var next) -> delimited((Cont) f.apply(x), next, ret);
      case Frames.Mapped(var f, var next) -> delimited(new Cont.Return<>(f.apply(x)), next, ret);
    };
  }

  /** a captured rest as k: applied, the stack it holds resumed under the delimiter with {@code ret}. One object */
  private record Resumed(Frames k, Function<Object, Object> ret) implements Function<Object, Cont<?, ?, ?>> {
    @Override
    public Cont<?, ?, ?> apply(Object x) {
      return new Resume<>(x, k, ret);
    }
  }

  /** the top is a delimiter, {@code identity $} */
  static <A> A value(Cont<A, A, A> c) {
    return (A) delimited(c, Frames.END, Function.identity());
  }
}
