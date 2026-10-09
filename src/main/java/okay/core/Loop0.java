package okay.core;

import java.util.function.Function;

/** THE LOOP of {@link Cont0}: a tree read to its head, binds reassociated and followed, a delimiter entered, in
 * constant stack — one Java frame per delimiter in force, none per capture */
@SuppressWarnings({"unchecked", "rawtypes"})
final class Loop0 {
  private Loop0() {}

  /** {@code m} with the rest as a stack already: what applying a captured k makes. Only {@link Frames0} make it */
  record Push<I, O, B>(Cont0<?, ?, ?> m, Frames0 k) implements Cont0<I, O, B> {}

  /** a captured k applied to {@code x}: {@code ret $ K[x]}, {@code K} the stack — one node */
  record Resume<I, O, B>(Object x, Frames0 k, Function<Object, Object> ret) implements Cont0<I, O, B> {}

  /** the loop's stack, innermost first; as a function, the rest */
  sealed interface Frames0 extends Function<Object, Cont0<?, ?, ?>> {
    Frames0 END = new End();

    record End() implements Frames0 {}

    record Then(Function f, Frames0 next) implements Frames0 {}

    record Mapped(Function f, Frames0 next) implements Frames0 {}

    @Override
    default Cont0<?, ?, ?> apply(Object a) {
      return switch (this) {
        case End _ -> new Cont0.Return<>(a);
        case Then(var f, var next) -> next instanceof End ? (Cont0) f.apply(a) : new Push<>((Cont0) f.apply(a), next);
        case Mapped(var f, var next) ->
            next instanceof End ? new Cont0.Return<>(f.apply(a)) : new Push<>(new Cont0.Return<>(f.apply(a)), next);
      };
    }
  }

  /** THE DELIMITER'S LOOP, {@code ret $ c}, {@code k} bound under it, run to where it leaves the delimiter: a value,
   * answered by {@code ret}; a capture, its body the delimiter's program outside, in its place — NOT put under a
   * delimiter, as a shift's is put under its reset again — the rest under it again, {@code ret} included, as k. What
   * leaves is made here, no head between; nothing wraps the body, so nothing nests */
  private static Object delimited(Cont0<?, ?, ?> program, Frames0 frames, Function<Object, Object> ret) {
    Cont0 c = program;
    Frames0 k = frames;
    while (true) {
      switch (c) {
        case Cont0.Return r -> {
          Object v = r.value();
          while (true) {
            if (k instanceof Frames0.Then(var f, var next)) {
              c = (Cont0) f.apply(v);
              k = next;
              break;
            } else if (k instanceof Frames0.Mapped(var f, var next)) {
              v = f.apply(v);
              k = next;
            } else {
              return ret.apply(v);
            }
          }
        }
        case Cont0.Bind b -> {
          if (b.m() instanceof Cont0.Return r) {
            c = (Cont0) b.f().apply(r.value());
          } else {
            k = new Frames0.Then(b.f(), k);
            c = b.m();
          }
        }
        case Cont0.Map m -> {
          k = new Frames0.Mapped(m.f(), k);
          c = m.m();
        }
        case Push p -> {
          k = k instanceof Frames0.End ? p.k() : new Frames0.Then(p.k(), k);
          c = p.m();
        }
        case Cont0.Shift0 s -> { return s.f().apply(new Resumed(k, ret)); }
        case Cont0.Dollar d -> c = (Cont0) delimited(d.body(), Frames0.END, d.ret());
        case Resume r -> c = (Cont0) resume(r.x(), r.k(), r.ret());
      }
    }
  }

  /** {@code x} through a resumed stack under its delimiter: its first frame applied here */
  private static Object resume(Object x, Frames0 k, Function<Object, Object> ret) {
    return switch (k) {
      case Frames0.End _ -> ret.apply(x);
      case Frames0.Then(var f, var next) -> delimited((Cont0) f.apply(x), next, ret);
      case Frames0.Mapped(var f, var next) -> delimited(new Cont0.Return<>(f.apply(x)), next, ret);
    };
  }

  /** a captured rest as k: applied, the stack it holds resumed under the delimiter with {@code ret}. One object */
  private record Resumed(Frames0 k, Function<Object, Object> ret) implements Function<Object, Cont0<?, ?, ?>> {
    @Override
    public Cont0<?, ?, ?> apply(Object x) {
      return new Resume<>(x, k, ret);
    }
  }

  /** the top is a delimiter, {@code identity $} */
  static <A> A value(Cont0<A, A, A> c) {
    return (A) delimited(c, Frames0.END, Function.identity());
  }
}
