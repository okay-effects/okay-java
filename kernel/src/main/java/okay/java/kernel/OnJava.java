package okay.java.kernel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import okay.core.Eff;
import okay.core.Eff.Top;
import okay.core.Prompt;
import okay.kernel.Aborting;
import okay.kernel.Answering;
import okay.kernel.Answers;
import okay.kernel.Computation;
import okay.kernel.Direct;
import okay.kernel.Effects;
import okay.kernel.Fn;
import okay.kernel.General;
import okay.kernel.Handler;
import okay.kernel.Op;
import okay.kernel.Prog;
import okay.kernel.Program;
import okay.kernel.Resume;
import okay.kernel.Returning;
import okay.kernel.Step;
import okay.kernel.Thunk;
import okay.kernel.Type;

/**
 * OKAY-JAVA AS AN IMPLEMENTATION OF OKAY-KERNEL: {@link Effects} (and {@link Direct}) on okay-java's {@link Eff}, run
 * by its machine — the semantics of okay-kernel's reference machine, {@code Steps}, checked by its TCK.
 *
 * <p>HOW. A kernel program ({@link Prog}) is a small tree of the bridge's nodes, context-free as the kernel's programs
 * are, built by the one {@link #EFFECTS}. It is translated into okay-java's program WHERE IT RUNS: at a context
 * ({@code Ctx}) — the bridge's delimiters in force there, innermost first, each with its okay-java prompt, down to the
 * run's root (the host's {@link Answers}, the prompt a step is handed out to). A bind's function is translated when
 * the bind runs, a handler's body when the handler is entered, a clause in its handler's place: the translation is
 * lazy, and a run is okay-java's own loop, in constant stack. So whatever looks something up — an operation its
 * handler, {@code shift0} its delimiter, a failure the frame that takes it — looks where the program runs.
 *
 * <p>A RESUMED {@code k} RUNS WHERE IT IS RESUMED. okay-java's continuation keeps its delimiters by prompt and puts
 * them back wherever it is applied. Applied, a kernel {@code k} is a node too, placed like any other: okay-java's
 * {@code k} given the context it is placed at. The operation it was captured at rebuilds its own context there (the
 * frames from it to the captured delimiter, the same frames and prompts, on the new outside) and goes on in it; the
 * code after it, already translated, learns the context from the values it is given: a value moved to another
 * context than the code's own carries it ({@code Moved}), and each bind, each delimiter's return, each clause in a
 * delimiter's place goes on in the context its value says. So an operation in a {@code k} resumed under other
 * handlers goes to the handler in force where it runs; a {@code shift0}'s body runs, and is translated, outside its
 * delimiter.
 *
 * <p>okay-java's own:
 * <ul>
 *   <li>{@code pure}, {@code flatMap}, {@code delay}: its nodes ({@code map} a bind: its function may fail).
 *   <li>Every delimiter of the bridge is an {@link Eff#handle}, a fresh prompt each time it is entered. A handler's
 *       operation by shape, in {@code Steps}' order: {@link Answering} answered in place, no capture; {@link Aborting}
 *       an abort (below); {@link General} a {@link Prompt#shift0} to that handler's prompt — {@code k} the rest with
 *       the handler (deep), multi-shot, the clause in its place. {@link Returning}: the return a value.
 *   <li>{@code shift0}: {@code Prompt.shift0} to the nearest delimiter in force, a handler's or a {@code dollar}'s
 *       (the bridge's other frames it crosses, and {@code k} keeps them); {@code dollar(body, k)}:
 *       {@code Eff.handle(body, k)}, which is {@code k $ body}.
 *   <li>EXPORT ({@code computation}): each start a run under a root delimiter. An operation with no handler in force,
 *       not answered by the host in place, is a {@code shift0} to the root: the step {@code Yield(op, k)}, {@code k}
 *       okay-java's continuation — multi-shot, from any thread, each call a new {@code Eff.run} of it.
 * </ul>
 *
 * <p>The bridge's own, okay-java having no primitive for them:
 * <ul>
 *   <li>FAILURES. Every function of the program's the bridge calls (a bind's, a map's, a delay's, a handler's return —
 *       {@code Returning.answer} too — a clause, an answering handler's {@code value}, a {@code Program.in}, the
 *       host's) runs in a {@code try}; its exception is thrown where it was called: to the nearest frame it concerns,
 *       by {@code shift0} to that frame's prompt, the rest dropped. {@code recover} answers a failure by its function;
 *       {@code ensuring} runs its finalizer, then throws on outward; an imported program's operation gives a failure
 *       to it ({@code k.fail}); the root makes it {@code Failed}. {@code failed(e)} is the same throw. Errors of the
 *       JVM ({@link VirtualMachineError}, {@link LinkageError}) are not caught.
 *   <li>ABORTS. An {@link Aborting} handler's operation is thrown as an abort to that handler: finalizers on the way
 *       run, no {@code recover} catches it, the handler's answer the abort's.
 *   <li>CANCELLATION. A handed-out {@code k} is resumed with a signal: a value, a failure ({@code k.fail}: thrown
 *       where the operation was, catchable), a cancellation ({@code k.cancel}: thrown, finalizers run, no
 *       {@code recover} catches it, the root makes it {@code Cancelled}). Inside {@code uncancelable}, {@code cancel}
 *       answers the same {@code Yield} again, its {@code k} pending: resumed, the rest of the region is captured
 *       ({@code shift0} to the region's prompt) and run under a frame that cancels when the region ends — with a
 *       value or failed.
 *   <li>IMPORT ({@code foreign}): another's program started with the host answering in place what the nearest
 *       handler here answers in place (none: this run's host); each operation it hands out performed here under a
 *       frame of its own, its value to {@code k.resume}, a failure to {@code k.fail}. An abort or a cancellation
 *       crossing that frame cancels it ({@code k.cancel}), its finalizers' operations performed here, then goes on.
 * </ul>
 *
 * <p>An exception out of okay-java's machine itself — none of the bridge's making — ends the run as {@code Failed}.
 * The cost of being exact: a node a kernel program's node, translated where it runs; a frame of the bridge's for a
 * value, when a resumed {@code k} moved its context; a lookup walks the context, one frame a step.
 *
 * <p>TRUSTED STEPS, the only unchecked casts: {@link #level} (okay-java's levels are phantom types), {@link #erase}
 * (a program is covariant in its value at run time), {@link #value} (a value given where the program, built typed,
 * expects it: {@code Steps}' {@code trust} — the handler found by its effect's exact identity).
 */
public final class OnJava implements Effects, Direct {
  private OnJava() {}

  /** okay-java's {@code Effects}: context-free, as the kernel's programs are — run by {@link #computation} or
   * {@link #run} */
  public static final OnJava EFFECTS = new OnJava();

  @Override
  public String toString() {
    return "OnJava.EFFECTS";
  }

  // -- the programs: the bridge's nodes, the value's type a phantom

  private sealed interface Node<A> extends Prog<A>
      permits PureN, BindN, MapN, DelayN, PerformN, HandleN, Shift0N, DollarN, FailedN, RecoverN, EnsuringN,
      UncancelableN, ForeignN, ResumeN, RawN {}

  private record PureN<A>(A a) implements Node<A> {}

  private record BindN<A, B>(Node<A> p, Fn<A, Prog<B>> f) implements Node<B> {}

  private record MapN<A, B>(Node<A> p, Fn<A, B> f) implements Node<B> {}

  private record DelayN<A>(Thunk<? extends Prog<A>> t) implements Node<A> {}

  private record PerformN<X>(Op<X> op) implements Node<X> {}

  private record HandleN<A, R>(Handler<A, R> h, Program<A> body) implements Node<R> {}

  private record Shift0N<A, I, O>(Fn<Fn<A, Prog<I>>, Prog<O>> body) implements Node<A> {}

  private record DollarN<A, I, O>(Program<A> body, Fn<A, Prog<I>> k) implements Node<O> {}

  private record FailedN<A>(Throwable e) implements Node<A> {}

  private record RecoverN<A>(Program<A> body, Fn<Throwable, Prog<A>> h) implements Node<A> {}

  private record EnsuringN<A>(Program<A> body, Program<Void> fin) implements Node<A> {}

  private record UncancelableN<A>(Program<A> body) implements Node<A> {}

  private record ForeignN<A>(Computation<A> c) implements Node<A> {}

  /** a captured rest resumed with {@code x}: okay-java's continuation, given the context it is placed at */
  private record ResumeN<A>(Function<Object, Eff<Top, Object>> k, Object x) implements Node<A> {}

  /** a program of okay-java's own, given to {@link Direct}: placed as it is */
  private record RawN<A>(Eff<Top, Object> e) implements Node<A> {}

  private static <A> Node<A> node(Prog<A> p) {
    if (p instanceof Node<A> n) return n;
    throw new IllegalArgumentException("not a program of okay-java's bridge: " + p);
  }

  // -- the trusted steps

  /** TRUSTED: okay-java's levels are phantom types — the bridge runs every program at {@link Top}, a delimiter's
   * body at its fresh level {@code M} the same at run time */
  @SuppressWarnings("unchecked")
  private static <R> Prompt<Top, Top, R> level(Prompt<Top, ?, R> p) {
    return (Prompt<Top, Top, R>) p;
  }

  /** TRUSTED: the same, for a program of okay-java's given to {@link Direct} */
  @SuppressWarnings("unchecked")
  private static Eff<Top, ?> level(Eff<?, ?> e) {
    return (Eff<Top, ?>) e;
  }

  /** TRUSTED, SOUND: a program of {@code A} is one of {@code Object} — covariant at run time, invariant to Java only */
  @SuppressWarnings("unchecked")
  private static Eff<Top, Object> erase(Eff<Top, ?> e) {
    return (Eff<Top, Object>) e;
  }

  /** TRUSTED: a value given where the program, built typed, expects it ({@code Steps}' {@code trust}) */
  @SuppressWarnings("unchecked")
  private static <T> T value(Object v) {
    return (T) v;
  }

  // -- Effects: the nodes

  public <A> Prog<A> pure(A a) {
    return new PureN<>(a);
  }

  public <A, B> Prog<B> flatMap(Prog<A> p, Fn<A, Prog<B>> f) {
    return new BindN<>(node(p), f);
  }

  @Override
  public <A, B> Prog<B> map(Prog<A> p, Fn<A, B> f) {
    return new MapN<>(node(p), f);
  }

  public <A> Prog<A> delay(Thunk<Prog<A>> p) {
    return new DelayN<>(p);
  }

  public <X> Prog<X> perform(Op<X> op) {
    return new PerformN<>(op);
  }

  public <A, R> Prog<R> handle(Handler<A, R> h, Program<A> body) {
    return new HandleN<>(h, body);
  }

  public <A, I, O> Prog<A> shift0(Fn<Fn<A, Prog<I>>, Prog<O>> body) {
    return new Shift0N<>(body);
  }

  public <A, I, O> Prog<O> dollar(Program<A> body, Fn<A, Prog<I>> k) {
    return new DollarN<>(body, k);
  }

  public <A> Prog<A> failed(Throwable e) {
    return new FailedN<>(e);
  }

  public <A> Prog<A> recover(Program<A> body, Fn<Throwable, Prog<A>> h) {
    return new RecoverN<>(body, h);
  }

  public <A> Prog<A> ensuring(Program<A> body, Program<Void> finalizer) {
    return new EnsuringN<>(body, finalizer);
  }

  public <A> Prog<A> uncancelable(Program<A> body) {
    return new UncancelableN<>(body);
  }

  /** IMPORT: another's program, its operations answered where it runs */
  public <A> Prog<A> foreign(Computation<A> c) {
    return new ForeignN<>(c);
  }

  // -- Direct: the same nodes, untyped; a bind's function passed as it is; okay-java's own programs taken too

  public Object directPure(Object a) {
    return new PureN<>(a);
  }

  public Object directFlatMap(Object p, Fn<Object, Object> f) {
    return new BindN<Object, Object>(nodeOf(p), x -> prog(f.apply(x)));
  }

  public Object directMap(Object p, Fn<Object, Object> f) {
    return new MapN<>(nodeOf(p), f);
  }

  public Object directDelay(Thunk<Object> p) {
    return new DelayN<Object>(() -> prog(p.get()));
  }

  public Object directPerform(Op<?> op) {
    return new PerformN<>(op);
  }

  /** untyped: the body's value of the handler's type, trusted */
  public Object directHandle(Handler<?, ?> h, Program<?> body) {
    return new HandleN<Object, Object>(value(h), value(body));
  }

  /** the bridge's node; or okay-java's own program — run as it is, its operations okay-java's */
  public <A> Prog<A> prog(Object p) {
    if (p instanceof Node<?> n) return value(n);
    if (p instanceof Eff<?, ?> e) return new RawN<>(erase(level(e)));
    throw new IllegalArgumentException("not a program of okay-java or its bridge: " + p);
  }

  public Object unprog(Prog<?> p) {
    return node(p);
  }

  private Node<Object> nodeOf(Object p) {
    return node(this.<Object>prog(p));
  }

  // -- the context: the frames, innermost first

  /** a frame: a delimiter of the bridge's, what it is for */
  private sealed interface Frame
      permits Root, InHandler, InDollar, InRecover, InEnsuring, InUncancelable, InPending, InForeign, InAfter {}

  /** a run's root: its host, and the prompt its steps are handed out to */
  private record Root(Answers host, Prompt<Top, Top, Step<Object>> prompt) implements Frame {}

  private record InHandler(Handler<?, ?> h) implements Frame {}

  private record InDollar() implements Frame {}

  private record InRecover(Fn<Throwable, ? extends Prog<?>> h) implements Frame {}

  private record InEnsuring(Program<Void> fin) implements Frame {}

  private record InUncancelable() implements Frame {}

  /** around an uncancelable region a cancellation waits for: its end — a value or a failure — cancels */
  private record InPending() implements Frame {}

  /** an operation of an imported program, performed here: its {@code k} */
  private record InForeign(Resume<?, ?> k) implements Frame {}

  /** an imported program being cancelled, its finalizers' operations performed here: then {@code then} goes on */
  private record InAfter(Throwable then) implements Frame {}

  /** a value whose context moved: the code it is given to goes on at {@code at}, not its own */
  private record Moved(Ctx at, Object v) {}

  /** what okay-java's {@code k} is resumed with: the value, and where it is placed */
  private record Re(Object v, Ctx at) {}

  /** what a handed-out {@code k} is resumed with */
  private sealed interface Signal permits Value, Fail, Cancelled, Pending {}

  private record Value(Object x) implements Signal {}

  private record Fail(Throwable e) implements Signal {}

  private record Cancelled() implements Signal {}

  /** a resumption while a cancellation waits for the region's end */
  private record Pending(Signal s) implements Signal {}

  /** not a failure: an abort or a cancellation — no {@code recover} catches it */
  private abstract static class Control extends RuntimeException {
    Control(String m) {
      super(m, null, false, false);
    }
  }

  /** an {@link Aborting} handler's operation: to that handler's frame, its answer */
  private static final class Abort extends Control {
    final Frame to;
    final Object answer;

    Abort(Frame to, Object answer) {
      super("an abort");
      this.to = to;
      this.answer = answer;
    }
  }

  private static final class Cancel extends Control {
    Cancel() {
      super("cancelled");
    }
  }

  private static final Cancel CANCEL = new Cancel();

  private static Throwable nonFatal(Throwable t) {
    if (t instanceof VirtualMachineError || t instanceof LinkageError) throw (Error) t;
    return t;
  }

  private static Object unmoved(Object r) {
    return r instanceof Moved m ? m.v() : r;
  }

  /** a program's value, said to be at {@code at}: for code of another context */
  private static Eff<Top, Object> tag(Eff<Top, Object> e, Ctx at) {
    return e.flatMap(x -> Eff.pure(x instanceof Moved ? x : new Moved(at, x)));
  }

  /**
   * WHERE A PROGRAM RUNS: a frame, its delimiter's prompt (none at the root), the context outside, and {@code home}
   * — the context the delimiter was entered at, whose code follows it unless a resumed {@code k} placed it elsewhere
   */
  private static final class Ctx {
    final Frame frame;
    final Prompt<Top, Top, Object> prompt;
    final Ctx outer;
    final Ctx home;
    final Root root;
    /** the nearest delimiter of {@code shift0}, a handler's or a dollar's: its context, or null */
    final Ctx delimiter;

    Ctx(Frame frame, Prompt<Top, Top, Object> prompt, Ctx outer, Ctx home) {
      this.frame = frame;
      this.prompt = prompt;
      this.outer = outer;
      this.home = home;
      this.root = outer == null ? (Root) frame : outer.root;
      this.delimiter = frame instanceof InHandler || frame instanceof InDollar ? this : outer == null ? null : outer.delimiter;
    }

    // -- the translation: a node, placed here

    Eff<Top, Object> tr(Node<?> n) {
      return switch (n) {
        case PureN<?> p -> Eff.<Top, Object>pure(p.a());
        case BindN<?, ?> _, MapN<?, ?> _ -> spine(n);
        case DelayN<?> d -> Eff.<Top, Object>delay(() -> guarded(() -> tr(node(d.t().get()))));
        case PerformN<?> p -> perform(p.op());
        case HandleN<?, ?> h -> handle(h);
        case Shift0N<?, ?, ?> s -> shift0(s);
        case DollarN<?, ?, ?> d -> dollar(d);
        case FailedN<?> f -> throwIn(f.e());
        case RecoverN<?> r -> delimited(new InRecover(r.h()), r.body(), (out, v) -> Eff.pure(v));
        case EnsuringN<?> e -> delimited(new InEnsuring(e.fin()), e.body(), (out, v) -> out.finalized(e.fin(), d -> Eff.pure(v)));
        case UncancelableN<?> u -> delimited(new InUncancelable(), u.body(), (out, v) -> Eff.pure(v));
        case ForeignN<?> f -> Eff.<Top, Object>delay(() -> guarded(() -> stepped(f.c().start(answersHere()))));
        case ResumeN<?> r -> r.k().apply(new Re(r.x(), this));
        case RawN<?> r -> r.e();
      };
    }

    /** a Program, built and placed here */
    Eff<Top, Object> in(Program<?> p) {
      return guarded(() -> tr(node(p.in(EFFECTS))));
    }

    /** binds and maps nested to the left, a loop: the innermost first, then each function around it */
    private Eff<Top, Object> spine(Node<?> n0) {
      ArrayDeque<Node<?>> fs = new ArrayDeque<>();
      Node<?> n = n0;
      while (true) {
        if (n instanceof BindN<?, ?> b) {
          fs.push(n);
          n = b.p();
        } else if (n instanceof MapN<?, ?> m) {
          fs.push(n);
          n = m.p();
        } else break;
      }
      Eff<Top, Object> e = tr(n);
      while (!fs.isEmpty()) {
        Node<?> f = fs.pop();
        e = f instanceof BindN<?, ?> b ? bound(e, b) : mapped(e, (MapN<?, ?>) f);
      }
      return e;
    }

    private <A, B> Eff<Top, Object> bound(Eff<Top, Object> e, BindN<A, B> b) {
      return e.flatMap(r -> cont(r, (d, v) -> d.tr(node(b.f().apply(value(v))))));
    }

    private <A, B> Eff<Top, Object> mapped(Eff<Top, Object> e, MapN<A, B> m) {
      return e.flatMap(r -> cont(r, (d, v) -> Eff.<Top, Object>pure(m.f().apply(value(v)))));
    }

    /** code of this context given {@code r}: it goes on where {@code r} says, its value said to be there for the
     * code after it, of this context */
    Eff<Top, Object> cont(Object r, BiFunction<Ctx, Object, Eff<Top, Object>> f) {
      if (r instanceof Moved m && m.at() != this) {
        Ctx d = m.at();
        return tag(d.guarded(() -> f.apply(d, m.v())), d);
      }
      Object v = unmoved(r);
      return guarded(() -> f.apply(this, v));
    }

    /** a function of the program's, run: its exception thrown here */
    Eff<Top, Object> guarded(Supplier<Eff<Top, Object>> s) {
      try {
        return s.get();
      } catch (Throwable t) {
        return throwIn(nonFatal(t));
      }
    }

    // -- delimiters

    private Eff<Top, Object> delimited(Frame f, Program<?> body, BiFunction<Ctx, Object, Eff<Top, Object>> ret) {
      return delimitedBy(f, inside -> inside.in(body), ret);
    }

    /** {@code body} under a delimiter of the frame {@code f}, a fresh prompt; its value to {@code ret} outside it */
    Eff<Top, Object> delimitedBy(Frame f, Function<Ctx, Eff<Top, Object>> body, BiFunction<Ctx, Object, Eff<Top, Object>> ret) {
      Ctx here = this;
      return Eff.handle(new Eff.Body<Top, Object, Object>() {
        public <M extends Top> Eff<M, Object> in(Prompt<Top, M, Object> p) {
          Ctx inside = new Ctx(f, level(p), here, here);
          return inside.guarded(() -> body.apply(inside)).<M>at();
        }
      }, r -> here.exit(f, r, ret));
    }

    /** a delimiter's value leaving it: outside it where it is now — here, unless a resumed {@code k} placed it */
    private Eff<Top, Object> exit(Frame f, Object r, BiFunction<Ctx, Object, Eff<Top, Object>> ret) {
      Ctx out = r instanceof Moved m ? m.at().outerOf(f) : this;
      Object v = unmoved(r);
      Eff<Top, Object> e = out.guarded(() -> ret.apply(out, v));
      return out == this ? e : tag(e, out);
    }

    private Ctx outerOf(Frame f) {
      for (Ctx c = this; c != null; c = c.outer) if (c.frame == f) return c.outer;
      throw new IllegalStateException("a delimiter's value from outside it");
    }

    /** in this delimiter's place — outside it, where it is now: {@code f} there */
    Eff<Top, Object> inPlace(Function<Ctx, Eff<Top, Object>> f) {
      Ctx o = outer;
      Eff<Top, Object> e = o.guarded(() -> f.apply(o));
      return o == home ? e : tag(e, o);
    }

    /** the rest up to this delimiter dropped, {@code f} in its place */
    private Eff<Top, Object> capture(Function<Ctx, Eff<Top, Object>> f) {
      return prompt.<Object>shift0(k -> inPlace(f));
    }

    /** a capture up to {@code d} resumed: here again if {@code d} is where it was, else this context rebuilt on
     * where it is — the value said to be there */
    private Eff<Top, Object> relocated(Ctx d, Object r) {
      if (!(r instanceof Re(Object v, Ctx at))) throw new IllegalStateException("a resumption of no kind: " + r);
      if (at == d.outer) return Eff.pure(v);
      return Eff.pure(new Moved(rebase(d, at), v));
    }

    /** this context's frames up to {@code upTo}'s, the same frames and prompts, on {@code at} */
    private Ctx rebase(Ctx upTo, Ctx at) {
      ArrayList<Ctx> path = new ArrayList<>();
      for (Ctx c = this; ; c = c.outer) {
        path.add(c);
        if (c == upTo) break;
      }
      Ctx o = at;
      for (int i = path.size() - 1; i >= 0; i--) {
        Ctx c = path.get(i);
        o = new Ctx(c.frame, c.prompt, o, c.home);
      }
      return o;
    }

    // -- handlers, control

    private Handler<?, ?> handler() {
      return frame instanceof InHandler(var h) ? h : null;
    }

    private Ctx handlerOf(Type t) {
      for (Ctx c = this; c.outer != null; c = c.outer) {
        Handler<?, ?> h = c.handler();
        if (h != null && h.effect() == t) return c;
      }
      return null;
    }

    private <A, R> Eff<Top, Object> handle(HandleN<A, R> n) {
      Handler<A, R> h = n.h();
      BiFunction<Ctx, Object, Eff<Top, Object>> ret =
          h instanceof Returning<A, R> r ? (out, v) -> Eff.<Top, Object>pure(r.answer(value(v)))
              : h instanceof General<A, R> g ? (out, v) -> out.tr(node(g.ret(value(v), EFFECTS)))
              : (out, v) -> Eff.pure(v); // no return: the body's value the answer, as Steps
      return delimited(new InHandler(h), n.body(), ret);
    }

    /** an operation: to the nearest handler of its effect in force; none, the host in place, else handed out */
    private Eff<Top, Object> perform(Op<?> op) {
      Ctx h = handlerOf(op.effect());
      if (h == null) return unhandled(op);
      Handler<?, ?> hd = h.handler();
      if (hd instanceof Answering<?, ?> a) return Eff.<Top, Object>delay(() -> guarded(() -> Eff.pure(a.value(op))));
      if (hd instanceof Aborting<?, ?> ab)
        return Eff.<Top, Object>delay(() -> guarded(() -> throwIn(new Abort(h.frame, ab.abort(op)))));
      if (hd instanceof General<?, ?> g) return general(g, h, op);
      return throwIn(new IllegalStateException("a handler of no shape: " + hd));
    }

    /** a general handler's operation: {@code shift0} to its prompt, the clause in its place */
    private <A, R, X> Eff<Top, Object> general(General<A, R> g, Ctx h, Op<X> op) {
      return h.prompt.<Object>shift0(k -> h.inPlace(o -> o.tr(node(g.apply(op, x -> new ResumeN<R>(k, x), EFFECTS)))))
          .flatMap(r -> relocated(h, r));
    }

    private <A, I, O> Eff<Top, Object> shift0(Shift0N<A, I, O> s) {
      Ctx d = delimiter;
      if (d == null) return throwIn(new IllegalStateException("shift0 with no delimiter around it"));
      return d.prompt.<Object>shift0(k -> d.inPlace(o -> o.tr(node(s.body().apply(x -> new ResumeN<I>(k, x))))))
          .flatMap(r -> relocated(d, r));
    }

    private <A, I, O> Eff<Top, Object> dollar(DollarN<A, I, O> n) {
      return delimited(new InDollar(), n.body(), (out, v) -> out.tr(node(n.k().apply(value(v)))));
    }

    /** the finalizer, placed here, then {@code then} where it ends */
    Eff<Top, Object> finalized(Program<Void> fin, Function<Ctx, Eff<Top, Object>> then) {
      return in(fin).flatMap(r -> cont(r, (d, v) -> then.apply(d)));
    }

    private Ctx uncancelableHere() {
      for (Ctx c = this; c.outer != null; c = c.outer) if (c.frame instanceof InUncancelable) return c;
      return null;
    }

    // -- failures, aborts, cancellations: thrown to the nearest frame they concern

    Eff<Top, Object> throwIn(Throwable t) {
      return Eff.delay(() -> thrown(t));
    }

    private Eff<Top, Object> thrown(Throwable t) {
      for (Ctx c = this; ; c = c.outer) {
        Ctx at = c;
        switch (c.frame) {
          case Root r -> {
            Step<Object> s = t instanceof Cancel ? new Step.Cancelled<>()
                : new Step.Failed<>(t instanceof Abort a ? new IllegalStateException("an abort with its handler gone", a) : t);
            return r.prompt().<Object>shift0(k -> Eff.pure(s));
          }
          case InHandler _ -> {
            if (t instanceof Abort a && a.to == at.frame) return at.capture(o -> Eff.pure(a.answer));
          }
          case InRecover(var h) -> {
            if (!(t instanceof Control)) return at.capture(o -> o.tr(node(h.apply(t))));
          }
          case InEnsuring(var fin) -> {
            return at.capture(o -> o.finalized(fin, d -> d.throwIn(t)));
          }
          case InForeign(var fk) -> {
            // an abort or a cancellation crossing it: it is cancelled, its finalizers' operations performed here,
            // then this goes on; a failure: given to it
            if (t instanceof Control)
              return at.capture(o -> o.delimitedBy(new InAfter(t), in -> in.stepped(fk.cancel()), (out, v) -> out.throwIn(t)));
            return at.capture(o -> o.stepped(fk.fail(t)));
          }
          case InAfter(var then) -> {
            return at.capture(o -> o.throwIn(then));
          }
          case InPending() -> {
            return at.capture(o -> o.throwIn(CANCEL));
          }
          case InDollar _, InUncancelable _ -> {}
        }
      }
    }

    // -- export

    private Eff<Top, Object> unhandled(Op<?> op) {
      return Eff.delay(() -> guarded(() -> {
        Answers host = root.host();
        if (host.answers(op.effect())) return Eff.pure(host.value(op));
        return handedOut(op);
      }));
    }

    /** the operation handed out: {@code Yield(op, k)} at the root, {@code k} resumed with a signal */
    private <X> Eff<Top, Object> handedOut(Op<X> op) {
      return root.prompt().<Object>shift0(k -> Eff.pure(new Step.Yield<X, Object>(op, resumption(op, k, false))))
          .flatMap(this::waited)
          .flatMap(r -> cont(r, Ctx::signalled));
    }

    /** a pending signal: the rest of the region a cancellation waits for captured, and run under a frame that
     * cancels when the region ends */
    private Eff<Top, Object> waited(Object s) {
      if (!(s instanceof Pending(Signal on))) return Eff.pure(s);
      Ctx u = uncancelableHere();
      return u.prompt.<Object>shift0(rest -> u.inPlace(o ->
              o.delimitedBy(new InPending(), in -> rest.apply(new Re(on, in)), (out, v) -> out.throwIn(CANCEL))))
          .flatMap(r -> relocated(u, r));
    }

    private Eff<Top, Object> signalled(Object s) {
      return switch (s) {
        case Value(var x) -> Eff.pure(x);
        case Fail(var e) -> throwIn(e);
        case Cancelled() -> throwIn(CANCEL);
        case Pending(var on) -> signalled(on);
        default -> throwIn(new IllegalStateException("a signal of no kind: " + s));
      };
    }

    private <X> Resume<X, Object> resumption(Op<X> op, Function<Object, Eff<Top, Step<Object>>> k, boolean pending) {
      Ctx here = this;
      return new Resume<>() {
        private Step<Object> on(Signal s) {
          return run(k.apply(pending ? new Pending(s) : s));
        }

        public Step<Object> resume(X x) {
          return on(new Value(x));
        }

        public Step<Object> fail(Throwable e) {
          return on(new Fail(e));
        }

        public Step<Object> cancel() {
          // in a region cancellation waits for: the same wait again, the cancellation pending
          if (pending || here.uncancelableHere() != null) return new Step.Yield<>(op, here.resumption(op, k, true));
          return on(new Cancelled());
        }
      };
    }

    // -- import

    /** a foreign step, taken here */
    Eff<Top, Object> stepped(Step<?> s) {
      return switch (s) {
        case Step.Done<?> d -> Eff.pure(d.value());
        case Step.Failed<?> f -> throwIn(f.error());
        case Step.Cancelled<?> _ -> throwIn(CANCEL);
        case Step.Yield<?, ?> y -> yielded(y);
      };
    }

    /** its operation performed here under a frame of its own; the value to its {@code k} */
    private <X, A> Eff<Top, Object> yielded(Step.Yield<X, A> y) {
      Resume<X, A> k = y.k();
      return delimitedBy(new InForeign(k), in -> in.perform(y.op()), (out, v) -> out.stepped(k.resume(value(v))));
    }

    /** what an imported program is answered in place by: the nearest handler here, if it answers in place; none,
     * this run's host */
    private Answers answersHere() {
      Ctx here = this;
      return new Answers() {
        public boolean answers(Type e) {
          Ctx c = here.handlerOf(e);
          return c == null ? here.root.host().answers(e) : c.handler() instanceof Answering<?, ?>;
        }

        public <X> X value(Op<X> op) {
          Ctx c = here.handlerOf(op.effect());
          if (c == null) return here.root.host().value(op);
          if (c.handler() instanceof Answering<?, ?> a) return a.value(op);
          throw new IllegalStateException("not answered in place: " + op);
        }
      };
    }
  }

  // -- export: a run handed out

  /** a run on okay-java's machine, to a step; an exception out of it (the machine's own) a failure */
  private static Step<Object> run(Eff<Top, Step<Object>> e) {
    try {
      return Eff.run(e);
    } catch (Throwable t) {
      return new Step.Failed<>(nonFatal(t));
    }
  }

  /** EXPORT: each start a run under a root of its own — its value {@code Done}, an operation nothing here answers
   * {@code Yield}, a failure {@code Failed}, a cancellation {@code Cancelled} */
  public <A> Computation<A> computation(Program<A> program, Set<Type> effects) {
    Set<Type> row = Collections.unmodifiableSet(new HashSet<>(effects));
    return new Computation<>() {
      public Step<A> start(Answers host) {
        return OnJava.value(run(started(program, host)));
      }

      public Set<Type> effects() {
        return row;
      }
    };
  }

  private static Eff<Top, Step<Object>> started(Program<?> program, Answers host) {
    return Eff.handle(new Eff.Body<Top, Object, Step<Object>>() {
      public <M extends Top> Eff<M, Object> in(Prompt<Top, M, Step<Object>> p) {
        Ctx top = new Ctx(new Root(host, level(p)), null, null, null);
        return top.in(program).<M>at();
      }
    }, r -> Eff.pure(new Step.Done<Object>(unmoved(r))));
  }

  /** a program with nothing left to perform: its value, or its failure thrown */
  public static <A> A run(Program<A> p) {
    return switch (EFFECTS.computation(p, Set.of()).start(Answers.NONE)) {
      case Step.Done<A>(var a) -> a;
      case Step.Failed<A>(var e) -> {
        if (e instanceof RuntimeException r) throw r;
        if (e instanceof Error r) throw r;
        throw new IllegalStateException(e);
      }
      case Step.Cancelled<A>() -> throw new IllegalStateException("cancelled");
      case Step.Yield<?, A> y -> throw new IllegalStateException("an operation with no handler: " + y.op());
    };
  }
}
