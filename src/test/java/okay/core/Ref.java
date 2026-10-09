package okay.core;

import java.util.function.Function;

/**
 * THE REFERENCE: {@link Eff}'s nodes read by the textbook, no code of {@link EffLoop} — a program evaluated to what
 * it answers its delimiter, its value or a capture: the prompt, the clause, the rest. A delimiter takes a capture
 * to its prompt — the clause run in its place, k the rest under it again — and passes another's on, itself put back
 * on the rest: the lift. Recursive, not stack safe: run on a thread of a large stack. Slow, and the definition.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public final class Ref {
  private Ref() {}

  /** what a body answers its delimiter */
  sealed interface Res {
    record Done(Object value) implements Res {}

    /** a {@code shift0} to {@code prompt}, its clause {@code f}, the rest up to here {@code k} */
    record Cap(Prompt<?, ?, ?> prompt, Function f, Function<Object, Res> k) implements Res {}
  }

  static Res eval(Eff<?, ?> c) {
    return switch (c) {
      case EffLoop.Return r -> new Res.Done(r.value());
      case EffLoop.Bind b -> then(eval(b.m()), x -> eval((Eff) b.f().apply(x)));
      case EffLoop.Map m -> then(eval(m.m()), x -> new Res.Done(m.f().apply(x)));
      case EffLoop.Delay d -> eval((Eff) d.c().get());
      case EffLoop.Sync s -> new Res.Done(s.a().get());
      case EffLoop.Shift0 s -> new Res.Cap(s.prompt(), s.f(), Res.Done::new);
      case EffLoop.Suspend s -> new Res.Cap(s.prompt(), s.f(), x -> eval((Eff) s.k().apply(x)));
      case EffLoop.Handle h -> {
        Prompt<?, ?, ?> p = new Prompt<>();
        yield delimit(p, h.ret(), eval(h.body().in(p)));
      }
      case EffLoop.Reset r -> delimit(r.prompt(), Eff::pure, eval(r.body()));
      case EffLoop.Push _, EffLoop.Resume _, MetaLoop.Resumption _ -> throw new IllegalStateException("the machine's own node: " + c);
    };
  }

  /** the rest {@code f} after {@code r}: a capture keeps it, on its own rest */
  private static Res then(Res r, Function<Object, Res> f) {
    return switch (r) {
      case Res.Done d -> f.apply(d.value());
      case Res.Cap cap -> new Res.Cap(cap.prompt(), cap.f(), x -> then(cap.k().apply(x), f));
    };
  }

  /** {@code ret $ body} under {@code p}, the body evaluated to {@code r}: a value, {@code ret}'s; a capture to {@code p},
   * its clause, k the rest under {@code p} again; another's, on outside, the rest under {@code p} again */
  private static Res delimit(Prompt<?, ?, ?> p, Function<Object, Eff<?, ?>> ret, Res r) {
    return switch (r) {
      case Res.Done d -> eval(ret.apply(d.value()));
      case Res.Cap cap when cap.prompt() == p ->
          eval((Eff) cap.f().apply((Function<Object, Eff<?, ?>>) x -> Eff.delay(() -> reify(delimit(p, ret, cap.k().apply(x))))));
      case Res.Cap cap -> new Res.Cap(cap.prompt(), cap.f(), x -> delimit(p, ret, cap.k().apply(x)));
    };
  }

  /** an answer as a program again: a value, or the capture it is */
  private static Eff<?, ?> reify(Res r) {
    return switch (r) {
      case Res.Done d -> Eff.pure(d.value());
      case Res.Cap cap -> new EffLoop.Suspend<>(cap.prompt(), cap.f(), x -> reify(cap.k().apply(x)));
    };
  }

  /** a program at the top: its value — a capture there had no handler */
  public static <A> A run(Eff<?, A> p) {
    return switch (eval(p)) {
      case Res.Done d -> (A) d.value();
      case Res.Cap _ -> throw new IllegalStateException(
          "an operation with no handler in force: its capability was used outside the handler that made it");
    };
  }
}
