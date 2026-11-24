package okay.rows;

import okay.effect.Pure;
import okay.effect.Op;
import okay.effect.Eff;
import static okay.rows.Comp.DOOR;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;
import okay.core.Rows;

/** The words of the model: {@code import static okay.rows.Effects.*}. */
public final class Effects {
  private Effects() {}

  /** a value: it asks nothing, and fits any row */
  public static <A, R> Comp<A, R> pure(A a) { return new Comp<>(okay.core.Comp.pure(a)); }

  /** an operation asked: its effect is the first of the row, whatever the rest. It goes to the handler of the
   * row's first effect: the nearest one put by {@code handle}. The operation's class {@code O} must be of the
   * effect it names: it names as itself a class above {@code O}, and that class is an {@code E} ({@link Op}).
   * Written {@code ? super O}, for javac refuses {@code Op<E, X, O>} here: it cannot see that {@code O} is an
   * {@code E}, and a type variable's bound cannot say both. */
  public static <E, X, O extends Op<E, X, ? super O>> Comp<X, Eff<E, ?>> perform(O op) {
    return new Comp<>(DOOR.ask(op, 0));
  }

  /** the computation, under one more handler: its effects are one step further — and so its operations go, each
   * one handler further out than it would */
  public static <A, R> Comp<A, Eff<?, ? extends R>> up(Comp<A, R> c) { return retyped(lifted(c, 0)); }

  /** the computation, with one more handler under its first effect: the rest of its effects are one step further —
   * the operations of the first effect go where they would, the others one handler further out */
  public static <A, E, R> Comp<A, Eff<E, ? extends Eff<?, ? extends R>>> under(Comp<A, ? super Eff<E, R>> c) {
    return retyped(lifted(c, 1));
  }

  /** the same computation, where exactly the row {@code Q} is wanted — a declared type; {@code Q} has its effects,
   * each at the same place: nothing changes at run time */
  public static <A, Q> Comp<A, Q> fit(Comp<A, ? super Q> c) { return retyped(c); }

  /** the one cast of `up`, `under` and `fit`, whose signatures say which rows are allowed */
  @SuppressWarnings("unchecked") // a row is only a type: no value of a computation depends on it
  private static <A, R> Comp<A, R> retyped(Comp<A, ?> c) { return (Comp<A, R>) c; }

  /** `c`, its operations that skip `keep` handlers or more skipping one more: an operation alone by its count,
   * anything else under a marker that the search for a handler counts */
  private static <A> Comp<A, ?> lifted(Comp<A, ?> c, int keep) {
    return new Comp<A, Object>(DOOR.lift(c.eff, keep));
  }

  /** a computation built when it is reached: a call in tail position takes no stack */
  public static <A, R> Comp<A, R> defer(Supplier<? extends Comp<A, ? super R>> p) {
    return new Comp<>(okay.core.Comp.delay(() -> p.get().eff));
  }

  // -- loops: an Iterable is the source, the machine the loop

  /** {@code for (x : xs) body(x)}: the computation {@code body} makes of each element, one after another, in
   * constant stack. The iterator is asked for an element when the loop gets to it, so the source may have no end
   * (a loop left by a handler pulls no further). Each run takes an iterator of its own; and a continuation taken
   * inside the loop and resumed twice goes through the same elements twice — the place in the source is a value,
   * a {@link Cursor}, not the iterator's own. */
  public static <X, R> Comp<Void, R> forEach(
      Iterable<? extends X> xs, Function<? super X, ? extends Comp<?, ? super R>> body) {
    return defer(() -> each(Cursor.<X>of(xs.iterator()), body));
  }

  private static <X, R> Comp<Void, R> each(Cursor<X> at, Function<? super X, ? extends Comp<?, ? super R>> body) {
    if (at == null) return pure(null);
    return new Comp<Object, R>(body.apply(at.value).eff).flatMap(done -> each(at.next(), body));
  }

  /** the loop with a value carried through it: {@code acc = step(acc, x)} for each element, from {@code zero} */
  public static <X, S, R> Comp<S, R> fold(
      Iterable<? extends X> xs, S zero, BiFunction<? super S, ? super X, ? extends Comp<S, ? super R>> step) {
    return defer(() -> folded(Cursor.<X>of(xs.iterator()), zero, step));
  }

  private static <X, S, R> Comp<S, R> folded(
      Cursor<X> at, S acc, BiFunction<? super S, ? super X, ? extends Comp<S, ? super R>> step) {
    if (at == null) return pure(acc);
    return Effects.<S, R>fit(step.apply(acc, at.value)).flatMap(next -> folded(at.next(), next, step));
  }

  /** the values {@code f} makes of the elements, in their order: a list never changed, so each path of a
   * continuation resumed twice has its own */
  public static <X, Y, R> Comp<List<Y>, R> collect(
      Iterable<? extends X> xs, Function<? super X, ? extends Comp<Y, ? super R>> f) {
    return Effects.<X, Chain<Y>, R>fold(xs, null, (made, x) ->
        Effects.<Y, R>fit(f.apply(x)).map(y -> new Chain<>(y, made))).map(Chain::list);
  }

  // -- a source of computations: each element is a program, run when the loop gets to it

  /** the computations of {@code xs}, one after another, their values in their order. The elements may ask
   * effects; the source itself is plain — built by a {@code for}, a stream, a list:
   * {@code sequence(cart.stream().map(item -> perform(new Price(item))).toList())} */
  public static <X, R> Comp<List<X>, R> sequence(Iterable<? extends Comp<X, ? super R>> xs) {
    return Effects.<Comp<X, ? super R>, X, R>collect(xs, c -> c);
  }

  /** the same, their values combined as they come: {@code acc = combine(acc, x)} from {@code zero} */
  public static <X, S, R> Comp<S, R> reduce(
      Iterable<? extends Comp<X, ? super R>> xs, S zero, BiFunction<? super S, ? super X, ? extends S> combine) {
    return Effects.<Comp<X, ? super R>, S, R>fold(xs, zero, (acc, c) ->
        Effects.<X, R>fit(c).map(x -> combine.apply(acc, x)));
  }

  /** a stream of computations collected into the computation of their values:
   * {@code cart.stream().map(item -> perform(new Price(item))).collect(sequencing())} */
  public static <X, R> Collector<Comp<X, R>, ?, Comp<List<X>, R>> sequencing() {
    return Collector.<Comp<X, R>, ArrayList<Comp<X, R>>, Comp<List<X>, R>>of(ArrayList::new, ArrayList::add,
        (a, b) -> { a.addAll(b); return a; }, Effects::<X, R>sequence);
  }

  /** a place in a source: an element, and the next place, asked of the iterator once and kept */
  private static final class Cursor<X> {
    final X value;
    private Iterator<? extends X> source;
    private Cursor<X> next;

    private Cursor(X value, Iterator<? extends X> source) {
      this.value = value;
      this.source = source;
    }

    static <X> Cursor<X> of(Iterator<? extends X> source) {
      return source.hasNext() ? new Cursor<>(source.next(), source) : null;
    }

    Cursor<X> next() {
      if (source != null) {
        next = of(source);
        source = null;
      }
      return next;
    }
  }

  /** the values collected so far, the last first */
  private record Chain<Y>(Y last, Chain<Y> before) {
    static <Y> List<Y> list(Chain<Y> made) {
      ArrayList<Y> out = new ArrayList<>();
      for (Chain<Y> c = made; c != null; c = c.before) out.add(c.last);
      Collections.reverse(out);
      return Collections.unmodifiableList(out);
    }
  }

  /** {@code acquire}d, used, {@code release}d when the use is left: {@code ensuring} of the use */
  public static <S, A, R> Comp<A, R> bracket(Comp<S, ? super R> acquire,
      Function<S, ? extends Comp<A, ? super R>> use, Function<S, ? extends Comp<?, ? super R>> release) {
    return Effects.<S, R>fit(acquire).flatMap(s -> Effects.<A, R>fit(use.apply(s)).ensuring(() -> release.apply(s)));
  }

  /** the operations of {@code F}, the first effect of the program's row, answered by {@code h}: all those the row
   * sends here, and no other. {@code F} leaves the row: {@code R} is what is under it. An operation finds this
   * handler by its place in the row; {@code effect}, the class of the operations of {@code F}, only names the
   * effect: the machine does not test it, for the types say every operation that comes here is an {@code F}. */
  public static <F, A, B, R> Comp<B, R> handle(
      Class<? super F> effect, Handler<F, A, B, R> h, Comp<A, ? super Eff<F, R>> p) {
    return new Comp<>(DOOR.handle(new Handlers.General<>(effect, h, true), null, p.eff));
  }

  /** the same, the handler one function of the operation and the continuation ({@link Clause}), a lambda:
   * {@code handle((op, k) -> switch (op) { case Price p -> k.resume(p, 4L); }, p)}. The effect is the first of the
   * program's row: the compiler finds it there, and nothing names its class. A clause that only resumes is answered
   * in place. */
  public static <F, A, R> Comp<A, R> handle(Clause<F, A, R> clause, Comp<A, ? super Eff<F, R>> p) {
    return new Comp<>(DOOR.handle(new Handlers.Quick<>(clause), null, p.eff));
  }

  /** the same, the handler with a state: a {@link StateClause} is given the state of this path, the operation and
   * the continuation, and resumes with the answer and the next state —
   * {@code handle(0, (s, op, k) -> switch (op) { case Get g -> k.resume(g, s); case Put p -> k.resume(p, null,
   * p.value()); }, p)}. A continuation resumed twice goes on twice from the state it was asked in. */
  public static <F, S, A, R> Comp<A, R> handle(
      S s0, StateClause<F, S, A, R> clause, Comp<A, ? super Eff<F, R>> p) {
    return new Comp<>(DOOR.handle(new Handlers.Stateful<F, S, A, A, R>(clause, (state, a) -> a), s0, p.eff));
  }

  /** the same, its value made of the last state and the program's value: {@code ret} */
  public static <F, S, A, B, R> Comp<B, R> handle(S s0, StateClause<F, S, B, R> clause,
      BiFunction<? super S, ? super A, ? extends B> ret, Comp<A, ? super Eff<F, R>> p) {
    return new Comp<>(DOOR.handle(new Handlers.Stateful<F, S, A, B, R>(clause, ret), s0, p.eff));
  }

  /** the same, in place, from the state {@code s0} */
  public static <F, S, A, B, R> Comp<B, R> handle(
      Class<? super F> effect, InPlace<F, S, A, B, R> h, S s0, Comp<A, ? super Eff<F, R>> p) {
    return new Comp<>(DOOR.handle(new Handlers.Local<>(effect, h, true), s0, p.eff));
  }

  /** AN INTERCEPTOR: a handler that is no place in the row, which stays as it is. It takes every operation of
   * the class {@code effect} that passes it on the way out and that is its ({@code mine}), wherever in the row
   * the operation is going; a clause may ask the operation again, and it goes on from here. Found by the class,
   * so the class must be the whole effect, {@code Class<F>}: an effect with type arguments, as {@code Reader<V>},
   * is refused — the class would not tell a {@code Reader<Integer>} from a {@code Reader<String>}. Such an effect
   * is intercepted at its place in the row: {@code handle(.., under(p))}, as {@code Reader.local}. */
  public static <F, A, B, R> Comp<B, R> intercept(Class<F> effect, Handler<F, A, B, R> h, Comp<A, ? super R> p) {
    return new Comp<>(DOOR.handle(new Handlers.General<>(effect, h, false), null, p.eff));
  }

  public static <F, S, A, B, R> Comp<B, R> intercept(
      Class<F> effect, InPlace<F, S, A, B, R> h, S s0, Comp<A, ? super R> p) {
    return new Comp<>(DOOR.handle(new Handlers.Local<>(effect, h, false), s0, p.eff));
  }

  /** each operation of {@code F}, the first effect of the program's row, shown to {@code see} — a program run
   * before the operation goes on as it is, to the handler of that place further out: a trace, a count, a trail.
   * Nothing of the program changes. At the effect's place, as {@code Reader.local}: {@code F} may have type
   * arguments, and a sensitive place is refused ({@code under} takes no {@link Sensitive} or {@link Audited}). */
  public static <F, A, T> Comp<A, Eff<F, ? extends T>> observe(Class<? super F> effect,
      Function<? super F, ? extends Comp<?, ? super Eff<F, ? extends T>>> see, Comp<A, ? super Eff<F, T>> p) {
    return handle(effect, new Handler<F, A, A, Eff<F, ? extends T>>() {
      public Comp<A, Eff<F, ? extends T>> ret(A a) { return pure(a); }
      public Comp<A, Eff<F, ? extends T>> on(F op, K<A, Eff<F, ? extends T>> k) {
        return new Comp<>(see.apply(op).eff.flatMap(seen -> k.again().eff));
      }
    }, under(p));
  }

  /** a computation run to its next question that no handler in it answers. Refused ({@link okay.core.NestedRun})
   * while a program runs on this thread — from a program's function or a handler's clause: a handler is installed
   * as a part of the program instead ({@code State.run(s0, p)} is a program), or {@link #stepIsolated} */
  public static <A, R> Step<A, R> step(Comp<A, R> p) {
    try {
      return Handlers.trusted(DOOR.step(p.eff, Step.Done::new, Effects::asked));
    } catch (RuntimeException e) {
      if (DOOR.lostUnwinding(e)) throw new NoUnwinding();
      throw e;
    }
  }

  /** the same, inside a running program too, ON PURPOSE: a closed computation run as a run of its own — a machine of
   * its own on the stack, which sees none of the program's handlers. For a conversion, a pure helper evaluated whole,
   * a run started by a callback; not at each step of a loop */
  public static <A, R> Step<A, R> stepIsolated(Comp<A, R> p) {
    try {
      return Handlers.trusted(DOOR.stepIsolated(p.eff, Step.Done::new, Effects::asked));
    } catch (RuntimeException e) {
      if (DOOR.lostUnwinding(e)) throw new NoUnwinding();
      throw e;
    }
  }

  /** an operation that came to the top: the step {@code Asked} — unless it is sensitive: it is answered under its
   * audit only, not by whoever steps the program */
  private static Object asked(Object op, Rows.Rest rest) {
    if (!(op instanceof Op<?, ?, ?> asked)) throw new NoHandler(op);
    return new Step.Asked<>(asked, new Handlers.Stepped<>(rest));
  }

  /** a program with nothing left to ask, run to its value. Refused as {@link #step} is */
  public static <A> A run(Comp<A, ? super Pure> p) {
    return done(step(p));
  }

  /** the same, inside a running program too, on purpose: a run of its own, as {@link #stepIsolated} */
  public static <A> A runIsolated(Comp<A, ? super Pure> p) {
    return done(stepIsolated(p));
  }

  private static <A, R> A done(Step<A, R> s) {
    return switch (s) {
      case Step.Done<A, ?> done -> done.value();
      case Step.Asked<?, A, ?> asked -> throw new NoHandler(asked.op());
    };
  }
}
