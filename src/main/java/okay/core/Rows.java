package okay.core;

import java.lang.invoke.MethodHandles;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import okay.core.Machine.Kont;
import okay.core.Machine.Meta;
import okay.core.Machine.Run;
import okay.core.Machine.Seg;
import okay.core.Nodes.Frames;

/**
 * THE MACHINE'S SIDE OF THE ROW NOTATION ({@code okay.rows}): its operations, handlers and finalizers as {@link Eff}'s
 * nodes and delimiters, run by the same machine. Not for programs: the door ({@link Door}) is given once, to
 * {@code okay.rows.Comp}, so a program cannot answer an operation past the types of {@code okay.rows}.
 *
 * <ul>
 *   <li>A ROW HANDLER is a delimiter whose prompt's frame is a {@link Place}, made each time the handler is entered:
 *       its {@link Spec} and, for a handler in place, its state (an object of {@code okay.rows}, the cell).
 *   <li>AN OPERATION ({@link Ask}) is found its handler by its place in the row: the machine walks its delimiters
 *       from the innermost, counting the row handlers ({@code skip} of them passed), a marker of {@code up} or
 *       {@code under} ({@link Marker}) adding to the count, an interceptor taking the operations of its class.
 *       In place: answered there, nothing captured. General: a capture to the handler's prompt, the clause in its
 *       place. Reaching the root of {@code step}: a capture to the root, the step {@code Asked}.
 *   <li>STATE PER PATH: a capture saves the states of the handlers in place it takes into its rest; a resumption
 *       puts them back before it goes on — so each path starts from the states its rest was taken with.
 *   <li>UNWINDING a rest: resumed as a failure of its own ({@link Unwinding}) thrown where the operation was, under
 *       a delimiter that ends it ({@link End}); the finalizers in the rest ({@code ensuring}, {@link Eff#ensuring}'s
 *       guard) run as the failure passes them, where they were installed; a value coming out of a level of the rest
 *       goes on unwinding; where the handler that unwinds was stands {@link Gone}.
 * </ul>
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public final class Rows {
  private Rows() {}

  // -- the door

  private static volatile boolean given;

  /** the door, to {@code okay.rows.Comp} alone, once: its full-privilege lookup is the proof */
  public static synchronized Door door(MethodHandles.Lookup lookup) {
    if (given || !lookup.lookupClass().getName().equals("okay.rows.Comp")
        || (lookup.lookupModes() & MethodHandles.Lookup.PRIVATE) == 0)
      throw new IllegalStateException("the row notation's door is okay.rows.Comp's");
    given = true;
    return new Door();
  }

  /** what {@code okay.rows} builds and runs its programs with */
  public static final class Door {
    private Door() {}

    /** the operation {@code op}, to the handler {@code skip} row places past the nearest */
    public Eff<?, ?> ask(Object op, int skip) {
      return new Ask<>(op, skip);
    }

    /** {@code body} under the handler {@code spec}, its state in place made from {@code init} when entered */
    public Eff<?, ?> handle(Spec spec, Object init, Eff<?, ?> body) {
      return new Enter<>(spec, init, body);
    }

    /** {@code c}, its operations that skip {@code keep} places or more skipping one more: an operation alone by its
     * count, anything else under a marker; two markers of one kind one */
    public Eff<?, ?> lift(Eff<?, ?> c, int keep) {
      if (c instanceof Nodes.Return) return c;
      if (c instanceof Ask<?, ?> a) return a.skip < keep ? a : new Ask<>(a.op, a.skip + 1);
      if (c instanceof Nodes.Handle<?, ?, ?> h && h.frame() instanceof Marker mk && mk.keep == keep)
        return new Nodes.Handle<>(h.body(), Machine.SAME, new Marker(keep, mk.by + 1));
      return new Nodes.Handle<>(Nodes.plain(c), Machine.SAME, new Marker(keep, 1));
    }

    /** {@code body}, and the finalizer made by {@code fin} when it is left: {@link Eff#ensuring}'s guard */
    public Eff<?, ?> ensuring(Eff<?, ?> body, Supplier<? extends Eff<?, ?>> fin) {
      Function<Object, Eff<?, ?>> ret = a -> fin.get().then(Eff.pure(a));
      return new Nodes.Handle<>(Nodes.plain(body), ret, new Kernel.KEnsuring(fin));
    }

    /** the rest resumed with {@code x}: the states it was taken with put back first */
    public Eff<?, ?> resume(Rest rest, Object x) {
      Kont k = rest.kont;
      if (rest.root) {
        // a stepped program's rest: its root is the next step's, a delimiter of nothing stands in its place
        k = new Kont(k.k(), Machine.take(k.ms(), k.n(), new Seg(new Prompt<>(), Machine.SAME, null, null,
            Machine.TOP)), k.n());
      }
      Eff<?, ?> r = k.apply(x);
      if (rest.saved == null) return r;
      return Eff.delay(() -> {
        restore(rest.saved);
        return r;
      });
    }

    /** the rest UNWOUND: its finalizers run, nothing else of it; then {@code null}. {@code mode}: what stands where
     * its handler was — {@link #GONE}, {@link #KEPT}, {@link #NONE}; or the handler itself, {@link #STAYS} */
    public Eff<?, ?> unwind(Rest rest, int mode) {
      Unwinding u = new Unwinding();
      Kont k = rest.kont;
      Function<Object, Eff<?, ?>> on = _ -> new Kernel.Fail<>(u);
      Frames onFrames = new Frames.Then(on, Frames.END);
      Seg[] crossed = first(k.ms(), k.n());
      // its delimiters again, each with nothing outside it but the unwinding's going on, and its handler gone
      Prompt<?, ?, ?> at = mode == STAYS ? k.up().prompt() : new Prompt<>(new Gone(mode, u));
      Meta levels = new Seg(at, on, null, Frames.END, Machine.TOP);
      for (int i = crossed.length - 1; i >= 0; i--)
        levels = new Seg(crossed[i].prompt(), crossed[i].ret(), null, onFrames, levels);
      Eff<?, ?> r = new Kont(on, levels, k.n()).apply(null);
      Eff<?, ?> run = rest.saved == null ? r : Eff.delay(() -> {
        restore(rest.saved);
        return r;
      });
      return new Nodes.Handle<>(Nodes.plain(run), Machine.SAME, new End(u));
    }

    /** {@code c}, then the rest resumed with its value; if {@code c} is left by an unwinding, the rest unwound there
     * too, its handler staying, and the unwinding goes on: the rest a finalizer of {@code c}'s */
    public Eff<?, ?> after(Rest rest, Eff<?, ?> c) {
      Function<Object, Eff<?, ?>> ret = x -> resume(rest, x);
      return new Nodes.Handle<>(Nodes.plain(c), ret, new Kernel.KEnsuring(() -> unwind(rest, STAYS)));
    }

    /** {@code body} run to its value, as {@code done} makes it, or to an operation no handler in it answers, as
     * {@code asked} makes it of the operation and the rest. A failure no guard took is thrown */
    public Object step(Eff<?, ?> body, Function<Object, Object> done, BiFunction<Object, Rest, Object> asked) {
      Function<Object, Eff<?, ?>> ret = a -> Eff.pure(done.apply(a));
      return Machine.run(new Nodes.Handle<>(Nodes.plain(body), ret, new Root(asked)));
    }

    /** {@code body}, given when it is run a {@link Probe}: what it may ask, while it runs on this Java stack, of the
     * handlers in force there. For direct-style code (okay-java-resumable): an operation a handler in place answers is
     * answered at once, with nothing captured */
    public Eff<?, ?> here(Function<Probe, ? extends Eff<?, ?>> body) {
      return new Here<>(body);
    }

    /** whether {@code t} is an unwinding that went on where none is: the rest of one resumed elsewhere */
    public boolean lostUnwinding(Throwable t) {
      return t instanceof IllegalStateException && t.getCause() instanceof Unwinding;
    }
  }

  /** a rest taken by a general handler, an interceptor, an observer or the root: what {@link Door#resume} and
   * {@link Door#unwind} take. Nothing of it is public */
  public static final class Rest {
    final Kont kont;
    final Object[] saved;
    final boolean root;

    Rest(Kont kont, Object[] saved, boolean root) {
      this.kont = kont;
      this.saved = saved;
      this.root = root;
    }
  }

  /**
   * A HANDLER, as the machine sees it: of a place in the row ({@code row}), or an interceptor (not a place: it takes
   * the operations it {@code accepts}); general, or in place ({@code inPlace}: its operations answered at once from
   * a state of its own entry). Written by {@code okay.rows} only.
   */
  public abstract static class Spec {
    final boolean row;
    final boolean inPlace;

    protected Spec(boolean row, boolean inPlace) {
      this.row = row;
      this.inPlace = inPlace;
    }

    /** an interceptor's question: is the operation its own */
    protected boolean accepts(Object op) {
      return true;
    }

    /** an entry's own object, made when the handler is entered: a handler in place keeps its state in it */
    protected Object enter(Object init) {
      return null;
    }

    /** the body's value, outside the handler */
    protected abstract Eff<?, ?> ret(Object entry, Object value);

    /** in place: the answer to {@code op}, the state in {@code entry} changed as the handler says */
    protected Object answer(Object entry, Object op) {
      throw new IllegalStateException("not a handler in place");
    }

    /** in place: the state of an entry, to be put back for a path */
    protected Object state(Object entry) {
      return null;
    }

    protected void state(Object entry, Object state) {}

    /** general: whether it declares that it resumes the rest at most once (okay.rows.Once) */
    protected boolean once() {
      return false;
    }

    /** general: the clause for {@code op}, outside the handler; {@code skip} the places the operation had still to
     * pass when it came here — an observer's, for the operation passed on */
    protected Eff<?, ?> on(Object op, Rest rest, int skip) {
      throw new IllegalStateException("not a general handler");
    }
  }

  // -- frames: what a delimiter of the row notation is for

  /** a row handler entered: its prompt's frame and its return */
  static final class Place implements Function<Object, Eff<?, ?>> {
    final Spec spec;
    final Object entry;

    Place(Spec spec, Object entry) {
      this.spec = spec;
      this.entry = entry;
    }

    @Override
    public Eff<?, ?> apply(Object v) {
      return spec.ret(entry, v);
    }
  }

  /** {@code up} ({@code keep} 0) or {@code under} ({@code keep} 1): an operation passing it with {@code keep} or more
   * places to skip skips {@code by} more */
  record Marker(int keep, int by) {}

  /** where a handler was while its rest is unwound: gone, an operation of its own ending the finalizer that asks
   * (GONE); its effect kept in the row, its own going to the next place (KEPT); nothing, an
   * interceptor or the root (NONE) */
  record Gone(int mode, Unwinding u) {}

  /** what stands where a handler was while its rest is unwound: see {@link Gone} */
  public static final int GONE = 0;
  public static final int KEPT = 1;
  public static final int NONE = 2;
  /** not a mark: the handler stays, its rest unwound by {@link Door#after} */
  public static final int STAYS = 3;

  /** the end of an unwinding: its value {@code null} */
  record End(Unwinding u) {}

  /** the root of a step: an operation no handler answers is asked here */
  record Root(BiFunction<Object, Rest, Object> asked) {}

  /** an unwinding: a failure no guard of {@link Eff#recover}'s takes, ended by its {@link End} */
  static final class Unwinding extends Kernel.Control {
    Unwinding() {
      super("an unwinding");
    }
  }

  // -- the nodes

  static final class Ask<L, X> extends Nodes.Node<L, X> {
    final Object op;
    final int skip;

    Ask(Object op, int skip) {
      this.op = op;
      this.skip = skip;
    }

    Eff<?, ?> step(Run m) {
      return asked(m, op, skip);
    }
  }

  static final class Enter<L, A> extends Nodes.Node<L, A> {
    private final Spec spec;
    private final Object init;
    private final Eff<?, ?> body;

    Enter(Spec spec, Object init, Eff<?, ?> body) {
      this.spec = spec;
      this.init = init;
      this.body = body;
    }

    Eff<?, ?> step(Run m) {
      Place p = new Place(spec, spec.enter(init));
      return m.enter(new Prompt<>(p), p, body);
    }
  }

  /** a program given a probe of the handlers in force where it runs; the probe dies when the program is made */
  static final class Here<L, A> extends Nodes.Node<L, A> {
    private final Function<Probe, ? extends Eff<?, ?>> body;

    Here(Function<Probe, ? extends Eff<?, ?>> body) {
      this.body = body;
    }

    Eff<?, ?> step(Run m) {
      Probe p = new Probe(m);
      try {
        return body.apply(p);
      } finally {
        p.m = null;
      }
    }
  }

  /** THE HANDLERS IN FORCE where a program of {@link Door#here} runs, while it runs: an operation found as
   * {@link #asked} finds it, but answered only by a handler in place — nothing captured, nothing changed but that
   * handler's state, as {@code asked} would. Any other handler, or none, is left to the operation asked the usual
   * way */
  public static final class Probe {
    /** not answered here: the operation's handler is general (and may resume more than once), an unwinding's mark, the
     * root, or none; or the probe is dead */
    public static final Object ELSEWHERE = new Object();
    /** not answered here: the operation's handler is general and declared to resume at most once */
    public static final Object ONCE = new Object();

    private Run m;

    Probe(Run m) {
      this.m = m;
    }

    /** the answer of the handler in place that {@code op} goes to, {@code skip} row places past the nearest; or
     * {@link #ONCE} or {@link #ELSEWHERE} */
    public Object ask(Object op, int skip) {
      if (m == null) return ELSEWHERE;
      Meta rest = m.ms;
      Walk w = null;
      while (true) {
        Seg s;
        if (w != null) s = w.next();
        else if (rest instanceof Seg plain) {
          s = plain;
          rest = plain.next();
        } else if (rest instanceof Machine.Top) s = null;
        else {
          w = new Walk(rest);
          s = w.next();
        }
        if (s == null) return ELSEWHERE;
        Object f = s.prompt().frame;
        if (f instanceof Place p) {
          boolean found = p.spec.row ? skip == 0 : p.spec.accepts(op);
          if (p.spec.row && !found) skip--;
          if (found) return p.spec.inPlace ? p.spec.answer(p.entry, op) : p.spec.once() ? ONCE : ELSEWHERE;
        } else if (f instanceof Marker mk) {
          if (skip >= mk.keep) skip += mk.by;
        } else if (f instanceof Gone g) {
          if (g.mode == GONE && --skip < 0) return ELSEWHERE;
          if (g.mode == KEPT && skip >= 1) skip--;
        } else if (f instanceof Root) return ELSEWHERE;
      }
    }
  }

  // -- an operation, in the machine

  /** the operation's handler, by its place: walked to from the innermost delimiter */
  private static Eff<?, ?> asked(Run m, Object op, int skip) {
    Meta rest = m.ms;
    Walk w = null;
    int depth = 0;
    while (true) {
      Seg s;
      if (w != null) s = w.next();
      else if (rest instanceof Seg plain) {
        s = plain;
        rest = plain.next();
      } else if (rest instanceof Machine.Top) s = null;
      else {
        w = new Walk(rest);
        s = w.next();
      }
      if (s == null)
        return Kernel.unwind(m, new IllegalStateException("an operation with no handler in force: " + op));
      Object f = s.prompt().frame;
      if (f instanceof Place p) {
        boolean found = p.spec.row ? skip == 0 : p.spec.accepts(op);
        if (p.spec.row && !found) skip--;
        if (found) {
          if (p.spec.inPlace) return m.value(p.spec.answer(p.entry, op));
          Object[] saved = saved(m.ms, depth);
          int left = skip;
          return m.capture(s.prompt(), (Function<Kont, Eff<?, ?>>) k ->
              p.spec.on(op, new Rest(k, saved, false), left), m.k);
        }
      } else if (f instanceof Marker mk) {
        if (skip >= mk.keep) skip += mk.by;
      } else if (f instanceof Gone g) {
        if (g.mode == GONE && --skip < 0) {
          // its handler has left: the finalizer that asks ends here, and the unwinding goes on
          return Kernel.unwind(m, g.u);
        }
        if (g.mode == KEPT && skip >= 1) skip--;
      } else if (f instanceof Root r) {
        Object[] saved = saved(m.ms, depth);
        return m.capture(s.prompt(), (Function<Kont, Eff<?, ?>>) k ->
            Eff.pure(r.asked.apply(op, new Rest(k, saved, true))), m.k);
      }
      depth++;
    }
  }

  /** the states of the handlers in place among the first {@code n} delimiters: theirs and them, in pairs; none,
   * {@code null} */
  private static Object[] saved(Meta ms, int n) {
    Object[] saved = null;
    int i = 0;
    for (Seg s : first(ms, n)) {
      if (s.prompt().frame instanceof Place p && p.spec.inPlace) {
        if (saved == null) saved = new Object[2 * n];
        saved[i++] = p;
        saved[i++] = p.spec.state(p.entry);
      }
    }
    return saved;
  }

  private static void restore(Object[] saved) {
    for (int i = 0; i < saved.length && saved[i] != null; i += 2) {
      Place p = (Place) saved[i];
      p.spec.state(p.entry, saved[i + 1]);
    }
  }

  /** the first {@code n} delimiters of {@code ms} */
  private static Seg[] first(Meta ms, int n) {
    Seg[] segs = new Seg[n];
    Walk w = new Walk(ms);
    for (int i = 0; i < n; i++) segs[i] = w.next();
    return segs;
  }

  /** the delimiters of a stack, innermost first: a take unfolded where it is met, its tail after its first n */
  private static final class Walk {
    private Meta at;
    private int left = Integer.MAX_VALUE;
    private Meta[] tails = new Meta[4];
    private int[] lefts = new int[4];
    private int depth;

    Walk(Meta at) {
      this.at = at;
    }

    /** the next delimiter, or {@code null} at the bottom */
    Seg next() {
      while (true) {
        if (left == 0 || at instanceof Machine.Top) {
          if (depth == 0) return null;
          depth--;
          at = tails[depth];
          left = lefts[depth];
          continue;
        }
        switch (at) {
          case Seg s -> {
            at = s.next();
            left--;
            return s;
          }
          case Machine.Take t -> {
            int n = Math.min(t.n(), left);
            if (depth == tails.length) {
              tails = java.util.Arrays.copyOf(tails, 2 * depth);
              lefts = java.util.Arrays.copyOf(lefts, 2 * depth);
            }
            tails[depth] = t.tail();
            lefts[depth++] = left - n;
            left = n;
            at = t.m();
          }
          case Machine.Top _ -> throw new AssertionError();
        }
      }
    }
  }
}
