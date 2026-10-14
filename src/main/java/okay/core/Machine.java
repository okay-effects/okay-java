package okay.core;

import java.util.function.Function;
import okay.core.Nodes.Bind;
import okay.core.Nodes.Delay;
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

  /** a delimiter, its prompt and return, the function its answer is bound to if any, the frames outside it, and the
   * delimiters further out. {@code then}: a handler or a resumption bound — {@code handle(..).flatMap(f)}, a clause's
   * {@code k.apply(x).flatMap(f)}, the usual clause — its bind in the delimiter, no frame made: a field a Seg has room
   * for free */
  record Seg(Prompt<?, ?, ?> prompt, Function ret, Function then, Frames outer, Meta next) implements Meta {}

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

  /** the index of the first delimiter among the first {@code limit} of {@code m} that {@code p} holds of: {@code -1}
   * if none — {@link #indexOf} by a predicate, for {@link Kernel}'s frames */
  static int indexWhere(Meta m, int limit, java.util.function.Predicate<Seg> p) {
    int seen = 0;
    while (seen < limit) {
      switch (m) {
        case Seg s -> {
          if (p.test(s)) return seen;
          seen++;
          m = s.next();
        }
        case Take t -> {
          int n = Math.min(t.n(), limit - seen);
          int i = indexWhere(t.m(), n, p);
          if (i >= 0) return seen + i;
          seen += n;
          m = t.tail();
        }
        case Top _ -> { return -1; }
      }
    }
    return -1;
  }

  /** a captured continuation: the rest of the innermost segment ({@code k}, frames or a function), and the delimiters
   * crossed and the one captured up to — the first {@code n + 1} of {@code ms}. Applied, a resumption node */
  record Kont(Function k, Meta ms, int n) implements Function<Object, Eff<?, ?>>, okay.kernel.Fn<Object, Eff<?, ?>> {
    @Override
    public Eff<?, ?> apply(Object x) {
      // not itself kept: its delimiter, when none crossed — the usual — so a clause that only applies k once, most,
      // leaves the Kont to the JIT's escape analysis, never made
      return new Resumption<>(x, k, n == 0 && ms instanceof Seg up ? up : this);
    }

    /** the delimiter captured up to */
    Seg up() {
      return n == 0 && ms instanceof Seg s ? s : head(drop(ms, n));
    }
  }

  /** a captured continuation — its rest {@code k}, and {@code to}: its delimiter if it crossed none, else itself —
   * applied to {@code x} */
  static final class Resumption<L, B> extends Nodes.Node<L, B> {
    private final Object x;
    private final Function k;
    private final Object to;

    Resumption(Object x, Function k, Object to) {
      this.x = x;
      this.k = k;
      this.to = to;
    }

    Object x() {
      return x;
    }

    Function k() {
      return k;
    }

    Object to() {
      return to;
    }

    Eff<?, ?> step(Run m) {
      return m.resume(this, null);
    }
  }

  /** the end of a run: its value in {@link Run#result} */
  static final Eff<?, ?> DONE = new Return<>(null);

  /** ONE STEP A NODE: the loop calls the node's own step, a method of its own — so C2 compiles each apart, small, its
   * calls to the program's functions profiled by that kind of node alone; the machine's state in the fields here */
  static <A> A run(Eff<?, A> program) {
    Run m = new Run();
    Eff<?, ?> c = program;
    while (true) {
      try {
        while (c != DONE) c = ((Nodes.Node<?, ?>) c).step(m);
        break;
      } catch (Throwable t) {
        // a function of the program's threw, in a step: a FAILURE, unwound from the stack as it is there — to the
        // nearest guard it concerns (Eff.recover, Eff.ensuring, the kernel's), or out of the run. HotSpot compiles
        // the handler as a table entry: the loop is the same. Each step leaves the stack as the function it calls
        // sees it: a clause outside its delimiter, a resumed rest under its delimiters again
        c = new Kernel.Fail<>(Kernel.nonFatal(t));
      }
    }
    if (m.failure != null) throw Kernel.unchecked(m.failure);
    return (A) m.result;
  }

  /** a run's state: the frames of the innermost segment {@code k}, the delimiters outside it {@code ms}; each step
   * moves it on and gives the program next */
  static final class Run {
    Frames k = Frames.END;
    Meta ms = TOP;
    Object result;
    /** a failure no delimiter took: the run's, thrown out of it */
    Throwable failure;

    Eff<?, ?> bind(Bind b) {
      Eff m = b.m();
      if (m instanceof Return r) return (Eff) b.f().apply(r.value());
      if (m instanceof Sync s) return (Eff) b.f().apply(s.a().get());
      // a handler or a resumption bound: the function to its delimiter, no frame made
      if (m instanceof Resumption r) return resume(r, b.f());
      if (m instanceof Handle h) return handle(h, b.f());
      k = new Frames.Then(b.f(), k);
      return m instanceof Delay d ? (Eff) d.c().get() : m;
    }

    Eff<?, ?> map(Map m) {
      k = new Frames.Mapped(m.f(), k);
      // a delay under it, a recursion's tail: built here, no step of its own
      return m.m() instanceof Delay d ? (Eff) d.c().get() : m.m();
    }

    Eff<?, ?> push(Push p) {
      k = k instanceof Frames.End ? p.k() : new Frames.Then(p.k(), k);
      return p.m();
    }

    Eff<?, ?> reset(Reset r) {
      ms = new Seg(r.prompt(), SAME, null, k, ms);
      k = Frames.END;
      return r.body();
    }

    Eff<?, ?> handle(Handle h, Function then) {
      Prompt<?, ?, ?> p = new Prompt<>(h.frame());
      ms = new Seg(p, h.ret(), then, k, ms);
      k = Frames.END;
      return h.body().in(p);
    }

    Eff<?, ?> shift0(Shift0 s) {
      return capture(s.prompt(), s.f(), k);
    }

    Eff<?, ?> suspend(Suspend s) {
      return capture(s.prompt(), s.f(), k instanceof Frames.End ? s.k() : new Frames.Then(s.k(), k));
    }

    /** a capture of {@code kk} to {@code prompt}'s delimiter: the clause in its place — the nearest the usual one, no
     * walk, no drop */
    Eff<?, ?> capture(Prompt<?, ?, ?> prompt, Function f, Function kk) {
      Eff c;
      Seg d;
      Kont kont;
      if (ms instanceof Seg near && near.prompt() == prompt) {
        d = near;
        kont = new Kont(kk, ms, 0);
        ms = near.next();
      } else {
        kont = far(kk, ms, prompt);
        d = kont.up();
        ms = drop(ms, kont.n() + 1);
      }
      // the stack outside the delimiter first, then the clause: a failure of it is outside, as the clause is
      k = d.outer();
      c = (Eff) f.apply(kont);
      if (d.then() != null) {
        // the clause's answer the delimiter's: to the function it is bound to — at once if a value
        if (c instanceof Return a) c = (Eff) d.then().apply(a.value());
        else k = new Frames.Then(d.then(), k);
      }
      // the usual clauses, k(x) and k(x) bound: resumed right here, no step of the loop between
      if (c instanceof Resumption r) return resume(r, null);
      if (c instanceof Bind b && b.m() instanceof Resumption r) return resume(r, b.f());
      if (c instanceof Map mp && mp.m() instanceof Resumption r) {
        k = new Frames.Mapped(mp.f(), k);
        return resume(r, null);
      }
      return c;
    }

    /** a captured continuation resumed here: x through its rest, then its delimiters crossed and its delimiter again,
     * the frames here outside it — no delimiter made till a bind needs it. Its first frame (the operation's own) at a
     * call site of its own, the deeper at feed's */
    Eff<?, ?> resume(Resumption r, Function then) {
      Object x = r.x();
      if (!(r.k() instanceof Frames fr)) {
        ms = again(r, then, k, ms);
        k = Frames.END;
        return (Eff) r.k().apply(x);
      }
      Eff next = null;
      try {
        if (fr instanceof Frames.Mapped m) {
          x = m.f().apply(x);
          fr = m.next();
          if (!(fr instanceof Frames.End)) {
            Fed fed = feed(x, fr);
            next = fed.c();
            x = fed.v();
            fr = fed.k();
          }
        } else if (fr instanceof Frames.Then t) {
          next = (Eff) t.f().apply(x);
          fr = t.next();
        }
      } catch (Throwable t) {
        // the rest's frames run under its delimiters, not yet put back: put back for the failure to unwind through
        ms = again(r, then, k, ms);
        k = Frames.END;
        throw t;
      }
      if (next != null) {
        // a bind in its rest: its delimiter again, under it the rest's program
        ms = again(r, then, k, ms);
        k = fr;
        return next;
      }
      if (r.to() instanceof Seg up) {
        // its rest done, the delimiter next: the frames and delimiters here as they are, its return applied
        Function ret = up.ret();
        if (ret != SAME) {
          Eff c = (Eff) ret.apply(x);
          if (then == null) return c;
          if (c instanceof Return a) return (Eff) then.apply(a.value());
          k = new Frames.Then(then, k);
          return c;
        }
        if (then != null) return (Eff) then.apply(x);
        return value(x);
      }
      ms = again(r, then, k, ms);
      k = Frames.END;
      return value(x);
    }

    /** a value: through the maps to the first bind, or out of the segment — its delimiter's return, outside it; a
     * reset's the value itself, on at once. No Return made for it */
    Eff<?, ?> value(Object v) {
      while (true) {
        while (k instanceof Frames.Mapped m) {
          v = m.f().apply(v);
          k = m.next();
        }
        Eff c;
        if (k instanceof Frames.Then t) {
          k = t.next();
          c = (Eff) t.f().apply(v);
        } else {
          Seg seg;
          if (ms instanceof Seg plain) {
            seg = plain;
            ms = plain.next();
          } else if (ms instanceof Top) {
            result = v;
            return DONE;
          } else {
            seg = head(ms);
            ms = drop(ms, 1);
          }
          k = seg.outer();
          if (seg.then() != null) {
            // a bound delimiter's answer: its return, then the function bound — at once if the return a value
            if (seg.ret() == SAME) {
              c = (Eff) seg.then().apply(v);
            } else {
              c = (Eff) seg.ret().apply(v);
              if (c instanceof Return a) c = (Eff) seg.then().apply(a.value());
              else k = new Frames.Then(seg.then(), k);
            }
          } else if (seg.ret() != SAME) {
            c = (Eff) seg.ret().apply(v);
          } else {
            continue;
          }
        }
        // a value again — a handler's return, a bind's pure — on here, no step of the loop for it
        if (!(c instanceof Return r)) return c;
        v = r.value();
      }
    }
  }

  /** a value through the maps on top of the frames, to the first bind — its program and the frames under it — or to
   * the end, {@code c} null */
  record Fed(Eff<?, ?> c, Object v, Frames k) {}

  /** a resumed continuation's frames below its first: a method of its own, so the profile of its call to a frame's
   * function is the deeper frames' only — a capture's rest, maps over maps, one function mostly: inlined, as the old
   * loop's {@code feed} had it (okay-core's {@code feed}) */
  private static Fed feed(Object x, Frames fr) {
    while (fr instanceof Frames.Mapped m) {
      x = m.f().apply(x);
      fr = m.next();
    }
    return fr instanceof Frames.Then t ? new Fed((Eff) t.f().apply(x), null, t.next()) : new Fed(null, x, fr);
  }

  /** a capture to a delimiter not the nearest: its index walked to */
  private static Kont far(Function k, Meta ms, Prompt<?, ?, ?> prompt) {
    return new Kont(k, ms, index(ms, prompt));
  }

  /** a resumption's delimiters: those it crossed, then its delimiter again with the frames {@code k} here outside
   * it, in front of the delimiters here {@code ms}. Resumed where it was captured up to — a clause's {@code k(v)} as
   * it is, the usual — the delimiter the same: kept, not made again. Straight code for none crossed, the usual: the
   * loop the JIT compiles the machine's into has no loop of a take's in it */
  private static Meta again(Resumption r, Function then, Frames k, Meta ms) {
    if (r.to() instanceof Seg up)
      return then == null && up.then() == null && k == up.outer() && ms == up.next() ? up
          : new Seg(up.prompt(), up.ret(), then, k, ms);
    return againCrossed((Kont) r.to(), then, k, ms);
  }

  private static Meta againCrossed(Kont kont, Function then, Frames k, Meta ms) {
    Meta at = drop(kont.ms(), kont.n());
    Seg up = head(at);
    // resumed where it was captured: the delimiters crossed are in front of their delimiter still — the stack as it
    // was, no take made, so the next capture walks plain delimiters again
    if (then == null && up.then() == null && k == up.outer() && ms == up.next())
      return at == up ? kont.ms() : take(kont.ms(), kont.n(), up);
    return take(kont.ms(), kont.n(), new Seg(up.prompt(), up.ret(), then, k, ms));
  }

  /** the index of {@code prompt}'s delimiter in {@code ms}: none, a capability out of its handler */
  private static int index(Meta ms, Prompt<?, ?, ?> prompt) {
    int i = indexOf(ms, Integer.MAX_VALUE, prompt);
    if (i < 0) throw new IllegalStateException(
        "an operation with no handler in force: its capability was used outside the handler that made it");
    return i;
  }
}
