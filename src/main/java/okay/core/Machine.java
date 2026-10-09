package okay.core;

import java.util.function.Function;
import okay.core.Nodes.Bind;
import okay.core.Nodes.Delay;
import okay.core.Nodes.Fed;
import okay.core.Nodes.Frames;
import okay.core.Nodes.Handle;
import okay.core.Nodes.Map;
import okay.core.Nodes.Push;
import okay.core.Nodes.Reset;
import okay.core.Nodes.Return;
import okay.core.Nodes.Shift0;
import okay.core.Nodes.Suspend;
import okay.core.Nodes.Sync;

/**
 * THE MACHINE: {@link Eff}'s nodes run by ONE LOOP over an explicit stack of delimiters — no Java frame a delimiter,
 * so handlers nest as deep as the heap allows. The state: the program {@code c}, the frames of the innermost segment {@code k},
 * and the delimiters outside it, {@code ms}, innermost first — each with its prompt, its return and the frames
 * outside it. A capture walks {@code ms} to its prompt: no allocation per delimiter crossed. A resumption puts the
 * captured delimiters back in front of the clause's own by one {@link Take}: no copy.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
final class Machine {
  private Machine() {}

  /** the delimiters outside the innermost segment, as a sequence */
  sealed interface Meta {}

  /** no delimiter: the top */
  record Top() implements Meta {}

  static final Meta TOP = new Top();

  /** a reset's return: the value as it is — told apart, so a value leaving a reset goes on with no program made */
  static final Function<Object, Object> SAME = Return::new;

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

  /** a captured continuation: the rest of the innermost segment ({@code k}, frames or a function), the delimiters
   * crossed — the first {@code n} of {@code ms} — and the delimiter captured up to. Applied, a resumption node */
  record Kont(Function k, Meta ms, int n, Seg up) implements Function<Object, Eff<?, ?>> {
    @Override
    public Eff<?, ?> apply(Object x) {
      return new Resumption<>(x, this);
    }
  }

  /** a captured continuation applied to {@code x} */
  record Resumption<L, B>(Object x, Kont kont) implements Eff<L, B> {}

  static <A> A run(Eff<?, A> program) {
    Eff c = program;
    Frames k = Frames.END;
    Meta ms = TOP;
    while (true) {
      switch (c) {
        // a value, or one computed in place: through the frames, no Return made for it
        case Return _, Sync _ -> {
          Object v = c instanceof Return r ? r.value() : ((Sync) c).a().get();
          while (true) {
            if (!(k instanceof Frames.End)) {
              Fed fed = Nodes.feed(v, k);
              if (fed.c() != null) {
                c = fed.c();
                k = fed.k();
                break;
              }
              v = fed.v();
            }
            // the segment done: its delimiter's return, outside it — a reset's the value itself, on at once
            if (ms instanceof Top) return (A) v;
            Seg seg = ms instanceof Seg plain ? plain : head(ms);
            k = seg.outer();
            ms = ms instanceof Seg plain ? plain.next() : drop(ms, 1);
            if (seg.ret() != SAME) {
              c = (Eff) seg.ret().apply(v);
              break;
            }
          }
        }
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
          ms = new Seg(r.prompt(), SAME, k, ms);
          k = Frames.END;
          c = r.body();
        }
        case Shift0 s -> {
          Prompt<?, ?, ?> to = s.prompt();
          Function f = s.f();
          // the nearest delimiter the usual one: no walk, no drop
          if (ms instanceof Seg near && near.prompt() == to) {
            c = (Eff) f.apply(new Kont(k, ms, 0, near));
            k = near.outer();
            ms = near.next();
          } else {
            int i = index(ms, to);
            Seg d = head(drop(ms, i));
            c = (Eff) f.apply(new Kont(k, ms, i, d));
            k = d.outer();
            ms = drop(ms, i + 1);
          }
        }
        case Suspend s -> {
          Prompt<?, ?, ?> to = s.prompt();
          Function f = s.f();
          Function kk = k instanceof Frames.End ? s.k() : new Frames.Then(s.k(), k);
          if (ms instanceof Seg near && near.prompt() == to) {
            c = (Eff) f.apply(new Kont(kk, ms, 0, near));
            k = near.outer();
            ms = near.next();
          } else {
            int i = index(ms, to);
            Seg d = head(drop(ms, i));
            c = (Eff) f.apply(new Kont(kk, ms, i, d));
            k = d.outer();
            ms = drop(ms, i + 1);
          }
        }
        case Resumption r -> {
          // a captured continuation resumed here: its delimiters crossed, its delimiter again with the frames here
          // outside it — in front of the delimiters here, by a take; then x through its rest, no Return made
          Kont kont = r.kont();
          Seg up = kont.up();
          // resumed where it was captured up to — a clause's k(v) as it is, the usual — the delimiter the same: kept,
          // not made again
          Seg again = k == up.outer() && ms == up.next() ? up : new Seg(up.prompt(), up.ret(), k, ms);
          ms = take(kont.ms(), kont.n(), again);
          Object x = r.x();
          if (kont.k() instanceof Frames fr) {
            if (fr instanceof Frames.End) {
              c = new Return<>(x);
              k = Frames.END;
            } else {
              Fed fed = Nodes.feed(x, fr);
              if (fed.c() != null) {
                c = fed.c();
                k = fed.k();
              } else {
                c = new Return<>(fed.v());
                k = Frames.END;
              }
            }
          } else {
            c = (Eff) kont.k().apply(x);
            k = Frames.END;
          }
        }
      }
    }
  }


  /** the index of {@code prompt}'s delimiter in {@code ms}: none, a capability out of its handler */
  private static int index(Meta ms, Prompt<?, ?, ?> prompt) {
    int i = indexOf(ms, Integer.MAX_VALUE, prompt);
    if (i < 0) throw new IllegalStateException(
        "an operation with no handler in force: its capability was used outside the handler that made it");
    return i;
  }
}
