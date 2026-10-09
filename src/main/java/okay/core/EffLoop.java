package okay.core;

import java.util.function.Function;
import java.util.function.Supplier;

/** THE LOOP of {@link Eff}: {@link Loop0}'s, a delimiter its prompt with it — a capture to another prompt crosses
 * it. Each delimiter runs its body in a loop of its own, to where it leaves: no head made between, one Java frame
 * per delimiter in force, none per capture */
@SuppressWarnings({"unchecked", "rawtypes"})
final class EffLoop {
  private EffLoop() {}

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

  /** a clause's k applied to {@code x}: {@code ret $ K[x]} under {@code prompt} again — one node */
  record Resume<L, B>(Object x, Function k, Prompt<?, ?, ?> prompt, Function<Object, Object> ret) implements Eff<L, B> {}

  /** a reset's return: the value as it is */
  private static final Function<Object, Object> PURE = Return::new;

  /** the prompt of the top: no capture is to it */
  private static final Prompt<?, ?, ?> TOP = new Prompt<>();

  /** how deep delimiters of one prompt run nested in Java frames, at most: past it, out and back */
  private static final int NESTED = 64;

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

  /** THE DELIMITER'S LOOP, {@code ret $ c}, {@code k} bound under it: run to where it leaves the delimiter — a
   * value, answered by {@code ret}; a capture to this prompt, its clause in the delimiter's place, NOT under it, the
   * rest under it again as k; a capture to another, the same capture outside, the rest re-delimited when answered.
   * What leaves is the program outside, made here */
  private static Eff<?, ?> delimited(Eff<?, ?> program, Frames frames, Prompt<?, ?, ?> prompt, Function<Object, Object> ret,
                                     int depth) {
    Eff c = program;
    Frames k = frames;
    while (true) {
      switch (c) {
        case Return r -> {
          if (k instanceof Frames.End) return (Eff) ret.apply(r.value());
          Fed fed = feed(r.value(), k);
          if (fed.c() == null) return (Eff) ret.apply(fed.v());
          c = fed.c();
          k = fed.k();
        }
        case Bind b -> {
          if (b.m() instanceof Return r) {
            c = (Eff) b.f().apply(r.value());
          } else if (b.m() instanceof Sync s) {
            // a value computed in place, bound: to the function at once, no frame
            c = (Eff) b.f().apply(s.a().get());
          } else if (b.m() instanceof Handle h) {
            // a handler bound: entered at once — its value, the usual, to the function with no frame pushed
            Eff out = enter(h, depth);
            if (out instanceof Return r) {
              c = (Eff) b.f().apply(r.value());
            } else {
              k = new Frames.Then(b.f(), k);
              c = out;
            }
          } else {
            k = new Frames.Then(b.f(), k);
            c = b.m();
          }
        }
        case Map m -> {
          k = new Frames.Mapped(m.f(), k);
          c = m.m();
        }
        case Delay d -> c = (Eff) d.c().get();
        case Sync s -> c = new Return<>(s.a().get());
        case Push p -> {
          k = k instanceof Frames.End ? p.k() : new Frames.Then(p.k(), k);
          c = p.m();
        }
        case Shift0 s -> { return capture(s.prompt(), s.f(), k, prompt, ret); }
        case Suspend s -> {
          return capture(s.prompt(), s.f(), k instanceof Frames.End ? s.k() : new Frames.Then(s.k(), k), prompt, ret);
        }
        case Handle h -> c = enter(h, depth);
        // a reset or a resumption under a delimiter of its own prompt: whatever it captures to that prompt its own
        // delimiter takes, so it may run OUTSIDE this one, its value back into the rest under it. Nested here, a
        // Java frame, while not too deep; past it, out and back — the delimiter a frame on the heap: shifts' bodies
        // nest in constant stack
        case Reset r -> {
          if (r.prompt() == prompt && depth >= NESTED) return new Bind(r, new Rejoin(k, prompt, ret, depth));
          c = delimited(r.body(), Frames.END, r.prompt(), PURE, depth + 1);
        }
        case Resume r -> {
          if (r.prompt() == prompt && depth >= NESTED) return new Bind(r, new Rejoin(k, prompt, ret, depth));
          c = resume(r.x(), r.k(), r.prompt(), r.ret(), depth + 1);
        }
      }
    }
  }

  /** a value through the maps on top of the stack, to the first bind — its program and the rest — or to the end. A
   * method of its own, small: compiled apart from the loop's, its own loop gets the JIT's full care — a capture's
   * rest, maps over maps, 40% faster so than walked inside the loop (okay-core's {@code feed}) */
  private record Fed(Eff<?, ?> c, Object v, Frames k) {}

  private static Fed feed(Object v, Frames k) {
    while (k instanceof Frames.Mapped(var f, var next)) {
      v = f.apply(v);
      k = next;
    }
    return k instanceof Frames.Then(var f, var next) ? new Fed((Eff) f.apply(v), null, next) : new Fed(null, v, k);
  }

  /** a handler entered: its body in a loop of its own under a fresh prompt, to the program leaving it */
  private static Eff<?, ?> enter(Handle h, int depth) {
    Prompt<?, ?, ?> p = new Prompt<>();
    return delimited(h.body().in(p), Frames.END, p, h.ret(), depth + 1);
  }

  /** a capture of the rest {@code k} at the delimiter of {@code prompt}: its own — the clause, k the rest under the
   * delimiter again, lazily; another's — the same capture outside, the rest re-delimited at once when answered */
  private static Eff<?, ?> capture(Prompt<?, ?, ?> to, Function f, Function k, Prompt<?, ?, ?> prompt, Function<Object, Object> ret) {
    if (to == prompt) return (Eff) f.apply(new Resumed(k, prompt, ret));
    if (prompt == TOP) throw new IllegalStateException(
        "an operation with no handler in force: its capability was used outside the handler that made it");
    return new Suspend<>(to, f, new Redelimit(k, prompt, ret));
  }

  /** {@code x} through the rest {@code k} under the delimiter of {@code prompt}: a stack's first frame applied here */
  private static Eff<?, ?> resume(Object x, Function k, Prompt<?, ?, ?> prompt, Function<Object, Object> ret, int depth) {
    return switch (k) {
      case Frames.End _ -> (Eff) ret.apply(x);
      case Frames.Then(var f, var next) -> delimited((Eff) f.apply(x), next, prompt, ret, depth);
      case Frames.Mapped(var f, var next) -> delimited(new Return<>(f.apply(x)), next, prompt, ret, depth);
      default -> delimited((Eff) k.apply(x), Frames.END, prompt, ret, depth);
    };
  }

  /** a clause's k: applied, the rest resumed under the delimiter again — LAZILY, a node, so a clause that keeps k
   * (a generator's next step) runs nothing by making it */
  private record Resumed(Function k, Prompt<?, ?, ?> prompt, Function<Object, Object> ret) implements Function<Object, Eff<?, ?>> {
    @Override
    public Eff<?, ?> apply(Object x) {
      return new Resume<>(x, k, prompt, ret);
    }
  }

  /** the rest of a delimiter's body once the capture that crossed it is answered outside: under the delimiter
   * again AT ONCE — applied by a loop already, one call deep per delimiter crossed */
  private record Redelimit(Function k, Prompt<?, ?, ?> prompt, Function<Object, Object> ret)
      implements Function<Object, Eff<?, ?>> {
    @Override
    public Eff<?, ?> apply(Object x) {
      return resume(x, k, prompt, ret, 0);
    }
  }

  /** the rest of a delimiter's body once a reset or a resumption of its prompt, run out and back, is answered: under
   * the delimiter again, as deep as it was — so the nesting it was taken out of does not start over */
  private record Rejoin(Function k, Prompt<?, ?, ?> prompt, Function<Object, Object> ret, int depth)
      implements Function<Object, Eff<?, ?>> {
    @Override
    public Eff<?, ?> apply(Object x) {
      return resume(x, k, prompt, ret, depth);
    }
  }

  static <A> A run(Eff<?, A> p) {
    return ((Return<?, A>) delimited(p, Frames.END, TOP, Return::new, 0)).value();
  }
}
