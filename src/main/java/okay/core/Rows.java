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
 * THE MACHINE'S SIDE OF THE ROW NOTATION ({@code okay.rows}): its operations, handlers and finalizers as {@link Comp}'s
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
 *       a delimiter that ends it ({@link End}); the finalizers in the rest ({@code ensuring}, {@link Comp#ensuring}'s
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

    /** {@code op} asked as a program, its handler's clause called already from a probing stack: {@code made}, what
     * {@link Probe#ask} gave for it */
    public Comp<?, ?> askMade(Object op, int skip, Object made) {
      return new Ask<>(op, skip, (Made) made);
    }

    /** the operation {@code c} is, and nothing else; else {@code null}: a computation made of more */
    public Object operation(Comp<?, ?> c) {
      return c instanceof Ask<?, ?> a && !(a.op instanceof Many) ? a.op : null;
    }

    /** the row places an operation alone ({@link #operation}) has to skip */
    public int skip(Comp<?, ?> c) {
      return ((Ask<?, ?>) c).skip;
    }

    /** the same set, to the handler {@code skip} row places past the nearest */
    public Comp<?, ?> askAll(Object[] ops, int skip) {
      return new Ask<>(new Many(ops), skip);
    }

    /** the operations {@code ops} asked at once, as a set: to the nearest handler, which takes them together if it
     * takes sets ({@link Spec#takesMany}); else, or with an interceptor on the way, each is asked as one */
    public Comp<?, ?> askAll(Object[] ops) {
      return new Ask<>(new Many(ops), 0);
    }

    /** the operation {@code op}, to the handler {@code skip} row places past the nearest */
    public Comp<?, ?> ask(Object op, int skip) {
      return new Ask<>(op, skip);
    }

    /** {@code body} under the handler {@code spec}, its state in place made from {@code init} when entered */
    public Comp<?, ?> handle(Spec spec, Object init, Comp<?, ?> body) {
      return new Enter<>(spec, init, body);
    }

    /** {@code c}, its operations that skip {@code keep} places or more skipping one more: an operation alone by its
     * count, anything else under a marker; two markers of one kind one */
    public Comp<?, ?> lift(Comp<?, ?> c, int keep) {
      if (c instanceof Nodes.Return) return c;
      if (c instanceof Ask<?, ?> a) return a.skip < keep ? a : new Ask<>(a.op, a.skip + 1);
      if (c instanceof Nodes.Handle<?, ?, ?> h && h.frame() instanceof Marker mk && mk.keep == keep)
        return new Nodes.Handle<>(h.body(), Machine.SAME, new Marker(keep, mk.by + 1));
      return new Nodes.Handle<>(Nodes.plain(c), Machine.SAME, new Marker(keep, 1));
    }

    /** {@code body}, and the finalizer made by {@code fin} when it is left: {@link Comp#ensuring}'s guard */
    public Comp<?, ?> ensuring(Comp<?, ?> body, Supplier<? extends Comp<?, ?>> fin) {
      Function<Object, Comp<?, ?>> ret = a -> fin.get().then(Comp.pure(a));
      return new Nodes.Handle<>(Nodes.plain(body), ret, new Kernel.KEnsuring(fin));
    }

    /** the rest resumed with {@code x}: the states it was taken with put back first */
    public Comp<?, ?> resume(Rest rest, Object x) {
      Kont k = rest.kont;
      if (rest.root) {
        // a stepped program's rest: its root is the next step's, a delimiter of nothing stands in its place
        k = new Kont(k.k(), Machine.take(k.ms(), k.n(), new Seg(new Prompt<>(), Machine.SAME, null, null,
            Machine.TOP)), k.n());
      }
      Comp<?, ?> r = k.apply(x);
      if (rest.saved == null && rest.own == null) return r;
      return Comp.delay(() -> {
        restore(rest);
        return r;
      });
    }

    /** the rest UNWOUND: its finalizers run, nothing else of it; then {@code null}. {@code mode}: what stands where
     * its handler was — {@link #GONE}, {@link #KEPT}, {@link #NONE}; or the handler itself, {@link #STAYS} */
    public Comp<?, ?> unwind(Rest rest, int mode) {
      Unwinding u = new Unwinding();
      Kont k = rest.kont;
      Function<Object, Comp<?, ?>> on = _ -> new Kernel.Fail<>(u);
      Frames onFrames = new Frames.Then(on, Frames.END);
      Seg[] crossed = first(k.ms(), k.n());
      // its delimiters again, each with nothing outside it but the unwinding's going on, and its handler gone
      Prompt<?, ?, ?> at = mode == STAYS ? k.up().prompt() : new Prompt<>(new Gone(mode, u));
      Meta levels = new Seg(at, on, null, Frames.END, Machine.TOP);
      for (int i = crossed.length - 1; i >= 0; i--)
        levels = new Seg(crossed[i].prompt(), crossed[i].ret(), null, onFrames, levels);
      Comp<?, ?> r = new Kont(on, levels, k.n()).apply(null);
      Comp<?, ?> run = rest.saved == null && rest.own == null ? r : Comp.delay(() -> {
        restore(rest);
        return r;
      });
      // a rest taken from a probing stack whose frames wait elsewhere (Probe.resumeHere): theirs first, innermost
      if (k.k() instanceof Frames.Then t && t.f() instanceof Probe.Mark mk && mk.unwound != null) {
        Comp<?, ?> then = run;
        run = Comp.delay(() -> (Comp) mk.unwound.get()).then(then);
      }
      return new Nodes.Handle<>(Nodes.plain(run), Machine.SAME, new End(u));
    }

    /** {@code c}, then the rest resumed with its value; if {@code c} is left by an unwinding, the rest unwound there
     * too, its handler staying, and the unwinding goes on: the rest a finalizer of {@code c}'s */
    public Comp<?, ?> after(Rest rest, Comp<?, ?> c) {
      Function<Object, Comp<?, ?>> ret = x -> resume(rest, x);
      return new Nodes.Handle<>(Nodes.plain(c), ret, new Kernel.KEnsuring(() -> unwind(rest, STAYS)));
    }

    /** {@code body} run to its value, as {@code done} makes it, or to an operation no handler in it answers, as
     * {@code asked} makes it of the operation and the rest. A failure no guard took is thrown */
    public Object step(Comp<?, ?> body, Function<Object, Object> done, BiFunction<Object, Rest, Object> asked) {
      Function<Object, Comp<?, ?>> ret = a -> Comp.pure(done.apply(a));
      return Machine.run(new Nodes.Handle<>(Nodes.plain(body), ret, new Root(asked)));
    }

    /** the same, inside a running program too, on purpose: a run of its own ({@link Comp#runIsolated}) */
    public Object stepIsolated(Comp<?, ?> body, Function<Object, Object> done, BiFunction<Object, Rest, Object> asked) {
      Function<Object, Comp<?, ?>> ret = a -> Comp.pure(done.apply(a));
      return Machine.runIsolated(new Nodes.Handle<>(Nodes.plain(body), ret, new Root(asked)));
    }

    /** the same rest, its own handler's state {@code state} for the path that resumes it: a clause with a state
     * going on with another */
    public Rest with(Rest rest, Object state) {
      return new Rest(rest.kont, rest.saved, rest.root, rest.own, state);
    }

    /** {@code body}, given when it is run a {@link Probe}: what it may ask, while it runs on this Java stack, of the
     * handlers in force there. For direct-style code (okay-java-resumable): an operation a handler in place answers is
     * answered at once, with nothing captured */
    public Comp<?, ?> here(Function<Probe, ? extends Comp<?, ?>> body) {
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
    /** the handler it was taken by, if it keeps a state and is not in place (a clause with a state), and that
     * state for this path: put back as {@code saved} is, when the rest is resumed */
    final Place own;
    final Object ownState;

    Rest(Kont kont, Object[] saved, boolean root) {
      this(kont, saved, root, null, null);
    }

    Rest(Kont kont, Object[] saved, boolean root, Place own, Object ownState) {
      this.own = own;
      this.ownState = ownState;
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
    protected abstract Comp<?, ?> ret(Object entry, Object value);

    /** in place: the answer to {@code op}, the state in {@code entry} changed as the handler says */
    protected Object answer(Object entry, Object op) {
      throw new IllegalStateException("not a handler in place");
    }

    /** in place: the state of an entry, to be put back for a path */
    protected Object state(Object entry) {
      return null;
    }

    protected void state(Object entry, Object state) {}

    /** whether the handler's clause is called before the rest is taken ({@link #early}): then a probing stack
     * calls it too */
    protected boolean asksEarly() {
      return false;
    }

    /** whether the handler answers a set of operations at once: it is given a {@link Many} as an operation */
    protected boolean takesMany() {
      return false;
    }

    /** whether an entry has a state a path keeps: saved when a rest is taken across it, put back when resumed */
    protected boolean keepsState() {
      return inPlace;
    }

    /** general: whether it declares that it resumes the rest at most once (okay.rows.Once) */
    protected boolean once() {
      return false;
    }

    /** general: whether its clause is a program made where it runs ({@link Door#here}), and may be asked from a probing
     * stack though it may resume more than once: a direct-style handler's (okay-java-resumable) */
    protected boolean eager() {
      return false;
    }

    /** general: the clause for {@code op}, outside the handler; {@code skip} the places the operation had still to
     * pass when it came here — an observer's, for the operation passed on */
    protected Comp<?, ?> on(Object op, Rest rest, int skip) {
      throw new IllegalStateException("not a general handler");
    }

    /** general: the clause for {@code op} called BEFORE the rest is taken — a clause that only answers
     * ({@link Early#answered}) is answered at once, nothing captured; any other is given the rest then
     * ({@link Early#taken}). {@code null}: not such a handler, {@link #on} is called with the rest */
    protected Early early(Object entry, Object op) {
      return null;
    }
  }

  /** a clause called before its rest was taken ({@link Spec#early}) */
  public abstract static class Early {
    /** whether the clause's program is the rest resumed, and nothing else */
    protected abstract boolean answered();

    /** the answer it resumes with */
    protected abstract Object answer();

    /** the clause's program, the rest now taken: run outside the handler */
    protected abstract Comp<?, ?> taken(Rest rest);
  }

  // -- frames: what a delimiter of the row notation is for

  /** a row handler entered: its prompt's frame and its return */
  static final class Place implements Function<Object, Comp<?, ?>> {
    final Spec spec;
    final Object entry;

    Place(Spec spec, Object entry) {
      this.spec = spec;
      this.entry = entry;
    }

    @Override
    public Comp<?, ?> apply(Object v) {
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

  /** an unwinding: a failure no guard of {@link Comp#recover}'s takes, ended by its {@link End} */
  static final class Unwinding extends Kernel.Control {
    Unwinding() {
      super("an unwinding");
    }
  }

  // -- the nodes

  static final class Ask<L, X> extends Nodes.Node<L, X> {
    final Object op;
    final int skip;
    /** its handler's clause, called already from a probing stack ({@link Probe#ask}); or {@code null} */
    final Made made;

    Ask(Object op, int skip) {
      this(op, skip, null);
    }

    Ask(Object op, int skip, Made made) {
      this.op = op;
      this.skip = skip;
      this.made = made;
    }

    Comp<?, ?> step(Run m) {
      return asked(m, op, skip, made);
    }
  }

  /** A CLAUSE CALLED FROM A PROBING STACK that did more than answer ({@link Probe#ask}): its program, made, and the
   * handler it is of — for the operation asked as a program then ({@link Door#askMade}), so that the clause is not
   * called again */
  public static final class Made {
    final Place place;
    final Early early;

    Made(Place place, Early early) {
      this.place = place;
      this.early = early;
    }
  }

  /** a clause that failed when it was called: a failure outside its handler, the rest taken and dropped */
  private static final class Failed extends Early {
    private final Throwable why;

    Failed(Throwable why) {
      this.why = why;
    }

    @Override
    protected boolean answered() {
      return false;
    }

    @Override
    protected Object answer() {
      throw new IllegalStateException();
    }

    @Override
    protected Comp<?, ?> taken(Rest rest) {
      return new Kernel.Fail<>(why);
    }
  }

  static final class Enter<L, A> extends Nodes.Node<L, A> {
    private final Spec spec;
    private final Object init;
    private final Comp<?, ?> body;

    Enter(Spec spec, Object init, Comp<?, ?> body) {
      this.spec = spec;
      this.init = init;
      this.body = body;
    }

    Comp<?, ?> step(Run m) {
      Place p = new Place(spec, spec.enter(init));
      return m.enter(new Prompt<>(p), p, body);
    }
  }

  /** a program given a probe of the handlers in force where it runs; the probe dies when the program is made */
  static final class Here<L, A> extends Nodes.Node<L, A> {
    private final Function<Probe, ? extends Comp<?, ?>> body;

    Here(Function<Probe, ? extends Comp<?, ?>> body) {
      this.body = body;
    }

    Comp<?, ?> step(Run m) {
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
    /** not answered here: the operation's handler is general, may resume more than once, and its clause is made where
     * it runs — {@link #resumeHere} answers it at once if the clause resumes in tail position */
    public static final Object EAGER = new Object();

    private Run m;
    /** where the operations of the first two places go, found once while the probe lives — the stack does not change
     * under it: a handler in place's {@link Place}, {@link #ONCE} or {@link #ELSEWHERE}. Not kept where an
     * interceptor stands on the way: whether it takes an operation is asked of each. Fields, not an array: a probe
     * often lives for one operation */
    private Object route0;
    private Object route1;

    Probe(Run m) {
      this.m = m;
    }

    /** the answer of the handler in place that {@code op} goes to, {@code skip} row places past the nearest; or
     * {@link #ONCE} or {@link #ELSEWHERE} */
    public Object ask(Object op, int skip) {
      Object r = route(op, skip);
      if (!(r instanceof Place p)) return r;
      if (p.spec.inPlace) return p.spec.answer(p.entry, op);
      // a handler whose clause is called before the rest is taken: called here, on the probing stack. One that
      // only answers is answered at once, nothing captured; any other is a {@link Made}, to ask as a program
      Early early;
      try {
        early = p.spec.early(p.entry, op);
      } catch (Throwable t) {
        early = new Failed(Kernel.nonFatal(t));
      }
      return early.answered() ? early.answer() : new Made(p, early);
    }

    /** where {@code op} goes, never answered here: {@link #ONCE} or {@link #ELSEWHERE} — for an operation that must
     * be asked as a program even where its handler is in place (a sensitive one: its audit must see it) */
    public Object kind(Object op, int skip) {
      Object r = route(op, skip);
      return r instanceof Place ? ELSEWHERE : r;
    }

    /** {@code op} asked from here, while the program that probes runs — for a general handler that declares it
     * resumes once: the handler takes the rest at once, the rest topped by {@code later}, and its clause runs. If the
     * clause resumes the rest at once ({@code k.resume(op, x)}, bound or mapped or not), the rest is put back as it
     * was and {@code x} is returned: the operation was answered here, nothing to suspend. Otherwise a
     * {@link Pending}: the stack is now the clause's, and its program must be what the probing program goes on
     * with — {@code later} is given the answer when the rest is resumed */
    public Object resumeHere(Object op, int skip, Function<Object, ? extends Comp<?, ?>> later) {
      return resumeHere(op, skip, later, null, true);
    }

    /** the same, for a handler {@link #EAGER} or {@link #ONCE}: a clause that is a program made where it runs
     * ({@link Door#here}) is made on this stack too. Answered at once if it resumes the rest at once — for a handler
     * that may resume more than once ({@code once} false), only in tail position, the clause's own program then
     * the resumption itself: nothing of the clause is left to resume the rest again. {@code unwound}, if not null,
     * the finalizer of what {@code later} would resume: run first when the rest is unwound, as the innermost of the
     * rest's finalizers */
    public Object resumeHere(Object op, int skip, Function<Object, ? extends Comp<?, ?>> later,
        Supplier<? extends Comp<?, ?>> unwound, boolean once) {
      return resumeHere(op, skip, later, unwound, once, null);
    }

    /** the same, the handler's clause called already by {@link #ask}: {@code made}, what it gave */
    public Object resumeHere(Object op, int skip, Function<Object, ? extends Comp<?, ?>> later,
        Supplier<? extends Comp<?, ?>> unwound, boolean once, Object made) {
      Mark mk = new Mark(later, unwound);
      Frames k0 = m.k;
      m.k = new Frames.Then(mk, k0);
      Comp<?, ?> next;
      try {
        next = asked(m, op, skip, (Made) made);
        // a clause made where it runs: made here, and resumed here as the machine's capture would
        if (next instanceof Here<?, ?> h) next = m.atOnce(h.step(m), once);
      } catch (Throwable t) {
        // the clause failed: a failure outside the handler, as the machine's loop would make it
        next = new Kernel.Fail<>(Kernel.nonFatal(t));
      } finally {
        mk.here = false;
      }
      return next == HIT ? mk.x : new Pending(next);
    }

    /** a finalizer made by {@code fin}, from here on: the program that probes and all it goes on with are under it,
     * as under {@link Door#ensuring} — entered now, while it runs, so that a rest taken after this has it */
    public void ensure(Supplier<? extends Comp<?, ?>> fin) {
      Function<Object, Comp<?, ?>> ret = a -> fin.get().then(Comp.pure(a));
      m.ms = new Seg(new Prompt<>(new Kernel.KEnsuring(fin)), ret, null, m.k, m.ms);
      m.k = Frames.END;
    }

    /** the operation asked by {@link #resumeHere} and not answered at once: the program to go on with */
    public record Pending(Comp<?, ?> next) {}

    /** the top of the rest {@link #resumeHere} gives the handler: resumed while the clause is called, it notes the
     * answer and leaves the rest as it was put back; resumed later, {@code later} goes on */
    static final class Mark implements Function<Object, Comp<?, ?>> {
      final Function<Object, ? extends Comp<?, ?>> later;
      final Supplier<? extends Comp<?, ?>> unwound;
      boolean here = true;
      Object x;

      Mark(Function<Object, ? extends Comp<?, ?>> later, Supplier<? extends Comp<?, ?>> unwound) {
        this.later = later;
        this.unwound = unwound;
      }

      @Override
      public Comp<?, ?> apply(Object x) {
        if (!here) return later.apply(x);
        here = false;
        this.x = x;
        return HIT;
      }
    }

    /** answered at once: never stepped, only compared */
    private static final Comp<?, ?> HIT = new Nodes.Return<>(null);

    private Object route(Object op, int skip) {
      if (m == null) return ELSEWHERE;
      Object known = skip == 0 ? route0 : skip == 1 ? route1 : null;
      if (known != null) return known;
      crossed = false;
      Object r = walk(op, skip);
      if (!crossed) {
        if (skip == 0) route0 = r;
        else if (skip == 1) route1 = r;
      }
      return r;
    }

    /** whether the last walk met an interceptor: its route is not kept */
    private boolean crossed;

    /** the walk, as {@code asked} finds a handler: a handler in place's {@link Place}, ONCE or ELSEWHERE */
    private Object walk(Object op, int skip) {
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
          if (!p.spec.row) crossed = true;
          boolean found = p.spec.row ? skip == 0 : p.spec.accepts(op);
          if (p.spec.row && !found) skip--;
          if (found) {
            if (p.spec.inPlace || p.spec.asksEarly()) return p;
            return p.spec.once() ? ONCE : p.spec.eager() ? EAGER : ELSEWHERE;
          }
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
  private static Comp<?, ?> asked(Run m, Object op, int skip) {
    return asked(m, op, skip, null);
  }

  private static Comp<?, ?> asked(Run m, Object op, int skip, Made made) {
    // a set of operations goes to a handler that takes sets; any other on its way, and it is asked one by one from here
    Many many = op instanceof Many set ? set : null;
    int from = skip;
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
        if (many != null && !p.spec.row) return one(many.ops, 0, null, from);
        boolean found = p.spec.row ? skip == 0 : p.spec.accepts(op);
        if (p.spec.row && !found) skip--;
        if (found) {
          if (many != null && !p.spec.takesMany()) return one(many.ops, 0, null, from);
          if (p.spec.inPlace) return m.value(p.spec.answer(p.entry, op));
          Early early;
          try {
            if (made == null) early = p.spec.early(p.entry, op);
            else if (made.place == p) early = made.early;
            else throw new IllegalStateException("a clause made for another handler: " + op);
          } catch (Throwable t) {
            // the clause failed: a failure outside the handler, the rest taken and dropped, as a clause's of `on`
            Comp<?, ?> failed = new Kernel.Fail<>(Kernel.nonFatal(t));
            return m.capture(s.prompt(), (Function<Kont, Comp<?, ?>>) k -> failed, m.k);
          }
          if (early != null && early.answered()) return m.value(early.answer());
          Object[] saved = saved(m.ms, depth);
          Place own = early != null && p.spec.keepsState() ? p : null;
          Object ownState = own == null ? null : p.spec.state(p.entry);
          if (early != null)
            return m.capture(s.prompt(),
                (Function<Kont, Comp<?, ?>>) k -> early.taken(new Rest(k, saved, false, own, ownState)), m.k);
          int left = skip;
          return m.capture(s.prompt(), (Function<Kont, Comp<?, ?>>) k ->
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
        if (many != null) return one(many.ops, 0, null, from);
        Object[] saved = saved(m.ms, depth);
        return m.capture(s.prompt(), (Function<Kont, Comp<?, ?>>) k ->
            Comp.pure(r.asked.apply(op, new Rest(k, saved, true))), m.k);
      }
      depth++;
    }
  }

  /** A SET OF OPERATIONS asked at once ({@link Door#askAll}): independent of each other, known before any is
   * answered. Its answer is the list of theirs, in their order */
  public static final class Many {
    final Object[] ops;

    Many(Object[] ops) {
      this.ops = ops;
    }

    /** the operations, in their order */
    public java.util.List<Object> ops() {
      return java.util.Collections.unmodifiableList(java.util.Arrays.asList(ops));
    }

    @Override
    public String toString() {
      return "all of " + java.util.Arrays.toString(ops);
    }
  }

  /** the answers so far of a set asked one by one, the last first: a value, so a rest taken on the way and resumed
   * twice has its own */
  private record Answers(Object last, Answers before) {}

  /** a set asked one by one, from the {@code i}th: each as an operation of its own, the same places to skip */
  private static Comp<?, ?> one(Object[] ops, int i, Answers made, int skip) {
    if (i == ops.length) {
      Object[] out = new Object[ops.length];
      Answers a = made;
      for (int j = ops.length - 1; j >= 0; j--, a = a.before) out[j] = a.last;
      return Comp.pure(java.util.Collections.unmodifiableList(java.util.Arrays.asList(out)));
    }
    return ((Comp<Object, Object>) (Comp) new Ask<>(ops[i], skip))
        .flatMap(x -> (Comp<Object, Object>) (Comp) one(ops, i + 1, new Answers(x, made), skip));
  }

  /** the states of the handlers in place among the first {@code n} delimiters: theirs and them, in pairs; none,
   * {@code null} */
  private static Object[] saved(Meta ms, int n) {
    // the nearest handler, the usual: nothing crossed, nothing to walk or make
    if (n == 0) return null;
    Object[] saved = null;
    int i = 0;
    Walk w = new Walk(ms);
    for (int j = 0; j < n; j++) {
      Seg s = w.next();
      if (s.prompt().frame instanceof Place p && p.spec.keepsState()) {
        if (saved == null) saved = new Object[2 * n];
        saved[i++] = p;
        saved[i++] = p.spec.state(p.entry);
      }
    }
    return saved;
  }

  /** the states a rest was taken with, put back: those of the handlers it crossed, and its own handler's */
  private static void restore(Rest rest) {
    if (rest.saved != null) restore(rest.saved);
    if (rest.own != null) rest.own.spec.state(rest.own.entry, rest.ownState);
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
