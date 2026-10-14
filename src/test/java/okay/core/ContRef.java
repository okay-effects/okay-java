package okay.core;

import java.util.function.Function;

/**
 * THE REFERENCE of {@link Cont}: its nodes read by the textbook, no code of {@link Loop} — a program evaluated to
 * what it answers its delimiter, its value or a capture with the rest. {@code ret $ body}: a value, {@code ret}'s,
 * a program outside, evaluated there; a capture, its body in the delimiter's place, k the rest under the delimiter
 * again. Recursive, not stack safe: run on a thread of a large stack.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public final class ContRef {
  private ContRef() {}

  sealed interface Res {
    record Done(Object value) implements Res {}

    record Cut(Function f, Function<Object, Res> k) implements Res {}
  }

  static Res eval(Cont<?, ?, ?> c) {
    return switch (c) {
      case Cont.Return r -> new Res.Done(r.value());
      case Cont.Bind b -> then(eval(b.m()), x -> eval((Cont) b.f().apply(x)));
      case Cont.Map m -> then(eval(m.m()), x -> new Res.Done(m.f().apply(x)));
      case Cont.Shift0 s -> new Res.Cut(s.f(), Res.Done::new);
      case Cont.Dollar d -> delimit(d.ret(), eval(d.body()));
      case Loop.Push _, Loop.Resume _ -> throw new IllegalStateException("the loop's own node: " + c);
    };
  }

  private static Res then(Res r, Function<Object, Res> f) {
    return switch (r) {
      case Res.Done d -> f.apply(d.value());
      case Res.Cut cut -> new Res.Cut(cut.f(), x -> then(cut.k().apply(x), f));
    };
  }

  /** what leaves {@code ret $ body}: a program outside, evaluated there */
  private static Res delimit(Function ret, Res r) {
    return eval(outside(ret, r));
  }

  /** {@code ret $ body}, the body evaluated to {@code r}, as the program outside it */
  private static Cont<?, ?, ?> outside(Function ret, Res r) {
    return switch (r) {
      case Res.Done d -> (Cont) ret.apply(d.value());
      case Res.Cut cut -> (Cont) cut.f().apply((Function<Object, Cont<?, ?, ?>>) x ->
          Cont.delay(() -> outside(ret, cut.k().apply(x))));
    };
  }

  /** a program at the top, the top a delimiter, {@code identity $} */
  public static <A> A value(Cont<A, A, A> c) {
    return switch (eval(c)) {
      case Res.Done d -> (A) d.value();
      case Res.Cut cut -> (A) cut.f().apply((Function<Object, Object>) x -> atTop(cut.k().apply(x)));
    };
  }

  private static Object atTop(Res r) {
    return switch (r) {
      case Res.Done d -> d.value();
      case Res.Cut cut -> cut.f().apply((Function<Object, Object>) x -> atTop(cut.k().apply(x)));
    };
  }
}
