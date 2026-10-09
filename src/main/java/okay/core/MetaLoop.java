package okay.core;

import java.util.function.Function;
import okay.core.EffLoop.Bind;
import okay.core.EffLoop.Delay;
import okay.core.EffLoop.Fed;
import okay.core.EffLoop.Frames;
import okay.core.EffLoop.Handle;
import okay.core.EffLoop.Map;
import okay.core.EffLoop.Push;
import okay.core.EffLoop.Reset;
import okay.core.EffLoop.Resume;
import okay.core.EffLoop.Return;
import okay.core.EffLoop.Shift0;
import okay.core.EffLoop.Suspend;
import okay.core.EffLoop.Sync;

/**
 * {@link Eff}'s nodes run by ONE LOOP over an explicit stack of delimiters, where {@link EffLoop} runs a delimiter's
 * body in a Java frame of its own. The state: the program {@code c}, the frames of the innermost segment {@code k},
 * and the delimiters outside it, {@code ms}, innermost first — each with its prompt, its return and the frames
 * outside it. A capture walks {@code ms} to its prompt: no allocation per delimiter crossed. A resumption puts the
 * captured delimiters back in front of the clause's own by one {@link Take}: no copy.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
final class MetaLoop {
  private MetaLoop() {}

  /** the delimiters outside the innermost segment, as a sequence */
  sealed interface Meta {}

  /** no delimiter: the top */
  record Top() implements Meta {}

  static final Meta TOP = new Top();

  /** a delimiter, its prompt and return, the frames outside it, and the delimiters further out */
  record Seg(Prompt<?, ?, ?> prompt, Function ret, Frames outer, Meta next) implements Meta {}

  /** the first {@code n} delimiters of {@code m}, then {@code tail}: a resumed continuation's, shared, not copied */
  record Take(Meta m, int n, Meta tail) implements Meta {}

  static Meta take(Meta m, int n, Meta tail) {
    if (n == 0) return tail;
    // the first n of the first n' of m', n <= n': the first n of m' — so takes do not nest as captures repeat
    while (m instanceof Take t && n <= t.n()) m = t.m();
    return new Take(m, n, tail);
  }

  /** the first delimiter of {@code m}; {@code m} not empty */
  static Seg head(Meta m) {
    while (true) {
      switch (m) {
        case Seg s -> { return s; }
        case Take t -> m = t.n() > 0 ? t.m() : t.tail();
        case Top _ -> throw new IllegalStateException("no delimiter");
      }
    }
  }

  /** {@code m} without its first {@code d} delimiters */
  static Meta drop(Meta m, int d) {
    while (d > 0) {
      switch (m) {
        case Seg s -> { m = s.next(); d--; }
        case Take t -> {
          if (d < t.n()) return take(drop(t.m(), d), t.n() - d, t.tail());
          d -= t.n();
          m = t.tail();
        }
        case Top _ -> throw new IllegalStateException("no delimiter");
      }
    }
    return m;
  }

  /** the index of the first delimiter of {@code prompt} among the first {@code limit} of {@code m}: {@code -1 - n}
   * if none, {@code n} the delimiters looked at */
  static int indexOf(Meta m, int limit, Prompt<?, ?, ?> prompt) {
    int seen = 0;
    while (seen < limit) {
      switch (m) {
        case Seg s -> {
          if (s.prompt() == prompt) return seen;
          seen++;
          m = s.next();
        }
        case Take t -> {
          int n = Math.min(t.n(), limit - seen);
          int i = indexOf(t.m(), n, prompt);
          if (i >= 0) return seen + i;
          seen += n;
          m = t.tail();
        }
        case Top _ -> { return -1 - seen; }
      }
    }
    return -1 - seen;
  }

  /** a captured continuation: the frames of the innermost segment, the delimiters crossed — the first {@code n} of
   * {@code ms} — and the delimiter captured up to, its prompt and return. Applied, a resumption node */
  record Kont(Frames k, Meta ms, int n, Prompt<?, ?, ?> prompt, Function ret) implements Function<Object, Eff<?, ?>> {
    @Override
    public Eff<?, ?> apply(Object x) {
      return new Resume<>(x, this, null, null);
    }
  }

  static <A> A run(Eff<?, A> program) {
    Eff c = program;
    Frames k = Frames.END;
    Meta ms = TOP;
    while (true) {
      switch (c) {
        case Return r -> {
          Object v = r.value();
          if (!(k instanceof Frames.End)) {
            Fed fed = EffLoop.feed(v, k);
            if (fed.c() != null) {
              c = fed.c();
              k = fed.k();
              continue;
            }
            v = fed.v();
          }
          // the segment done: its delimiter's return, outside it
          if (ms instanceof Top) return (A) v;
          Seg s = head(ms);
          c = (Eff) s.ret().apply(v);
          k = s.outer();
          ms = drop(ms, 1);
        }
        case Sync s -> c = new Return<>(s.a().get());
        case Bind b -> {
          if (b.m() instanceof Return r) {
            c = (Eff) b.f().apply(r.value());
          } else if (b.m() instanceof Sync s) {
            c = (Eff) b.f().apply(s.a().get());
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
        case Push p -> {
          k = k instanceof Frames.End ? p.k() : new Frames.Then(p.k(), k);
          c = p.m();
        }
        case Handle h -> {
          Prompt<?, ?, ?> p = new Prompt<>();
          ms = new Seg(p, h.ret(), k, ms);
          k = Frames.END;
          c = h.body().in(p);
        }
        case Reset r -> {
          ms = new Seg(r.prompt(), Return::new, k, ms);
          k = Frames.END;
          c = r.body();
        }
        case Shift0 s -> {
          Object[] next = capture(s.prompt(), s.f(), k, ms);
          c = (Eff) next[0];
          k = (Frames) next[1];
          ms = (Meta) next[2];
        }
        case Suspend s -> {
          Object[] next = capture(s.prompt(), s.f(), k instanceof Frames.End ? wrap(s.k()) : new Frames.Then(s.k(), k), ms);
          c = (Eff) next[0];
          k = (Frames) next[1];
          ms = (Meta) next[2];
        }
        case Resume r -> {
          // a captured continuation resumed here: its frames, its delimiters crossed, its delimiter again with the
          // frames here outside it — in front of the delimiters here, by a take
          Kont kont = (Kont) r.k();
          ms = take(kont.ms(), kont.n(), new Seg(kont.prompt(), kont.ret(), k, ms));
          k = kont.k();
          c = new Return<>(r.x());
        }
      }
    }
  }

  /** a function as the frames of a segment: one {@code Then} */
  private static Frames wrap(Function f) {
    return f instanceof Frames fr ? fr : new Frames.Then(f, Frames.END);
  }

  /** a capture to {@code prompt}: the clause's program, run outside the delimiter, with the frames and delimiters
   * there — the continuation the frames here and the delimiters crossed, the delimiter included */
  private static Object[] capture(Prompt<?, ?, ?> prompt, Function f, Frames k, Meta ms) {
    int i = indexOf(ms, Integer.MAX_VALUE, prompt);
    if (i < 0) throw new IllegalStateException(
        "an operation with no handler in force: its capability was used outside the handler that made it");
    Seg s = head(drop(ms, i));
    Kont kont = new Kont(k, ms, i, s.prompt(), s.ret());
    return new Object[] {f.apply(kont), s.outer(), drop(ms, i + 1)};
  }
}
