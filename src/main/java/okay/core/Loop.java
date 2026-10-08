package okay.core;

import java.util.function.Function;

/** THE LOOP: a tree read to its head, binds reassociated and followed, a delimiter entered, in constant stack */
@SuppressWarnings({"unchecked", "rawtypes"})
final class Loop {
  private Loop() {}

  static Head step(Cont<?, ?, ?> program) {
    Cont c = program;
    Frames k = Frames.END;
    while (true) {
      switch (c) {
        case Cont.Return<?, ?> r -> {
          // a value through the stack: maps applied in place, until a bind gives the next program
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
              return new Head.Done(v);
            }
          }
        }
        case Cont.Bind<?, ?, ?, ?, ?> b -> {
          if (b.m() instanceof Cont.Return<?, ?> r) {
            c = (Cont) ((Function) b.f()).apply(r.value());
          } else {
            k = new Frames.Then(b.f(), k);
            c = b.m();
          }
        }
        case Cont.Map<?, ?, ?, ?> m -> {
          k = new Frames.Mapped(m.f(), k);
          c = m.m();
        }
        case Push<?, ?, ?, ?, ?> p -> {
          k = k instanceof Frames.End ? p.k() : new Frames.Then(p.k(), k);
          c = p.m();
        }
        case Cont.Shift<?, ?, ?> s -> { return new Head.Cut((Function) s.f(), k); }
        case Cont.Reset<?, ?, ?, ?> rs -> c = delimited(rs.body(), (Function) rs.ret());
      }
    }
  }

  /** the delimiter's body to its head: a value is answered by {@code ret}; a capture's body goes under the
   * delimiter in place of the rest, with the rest under a delimiter of its own, {@code ret} included, as {@code k} */
  static Cont<?, ?, ?> delimited(Cont<?, ?, ?> body, Function<Object, Object> ret) {
    return switch (step(body)) {
      case Head.Done d -> new Cont.Return<>(ret.apply(d.value()));
      case Head.Cut cut -> {
        Cont b = (Cont) cut.f().apply(under(cut.k(), ret));
        // a body under a delimiter already — {@code k(x)} in tail position, most often — is not put under a
        // second: the two would nest, each the next capture's {@code step} inside the last's, a frame a capture
        yield b instanceof Cont.Reset ? b : new Cont.Reset<>(b, Function.identity());
      }
    };
  }

  /** a captured rest as {@code k}: applied, the stack it holds resumed, under a delimiter of its own with {@code ret} */
  static Function<Object, Cont<?, ?, ?>> under(Frames k, Function<Object, Object> ret) {
    return x -> new Cont.Reset<>(new Push<>(new Cont.Return<>(x), k), ret);
  }
}
