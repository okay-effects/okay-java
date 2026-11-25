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
import java.util.function.Consumer;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
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

  /** A BLOCK OF QUESTIONS AND WHAT FOLLOWS FROM THEIR ANSWERS ({@link Together}) — a comprehension:
   * {@code together(t -> { Var<Long> price = t.ask(perform(new Price(item))); Var<Boolean> stock =
   * t.ask(perform(new InStock(item))); return t.yield(() -> stock.get() ? price.get() : 0L); })}. The questions
   * of a block are independent: operations of one handler are asked at once, as a set. The block is written again
   * each time the program gets to it. */
  public static <A, E, R extends Eff<E, ?>> Comp<A, R> together(
      Function<? super Together<E, R>, ? extends Comp<A, R>> block) {
    return new Comp<>(okay.core.Comp.delay(() -> {
      Together<E, R> t = new Together<>();
      return Handlers.trusted(t.program(block.apply(t)));
    }));
  }

  /** PERFORM WRITTEN IN A PLAIN LOOP: the block ({@link Together}) that is nothing but one loop of questions, the
   * list of their answers its value —
   * {@code gather(g -> { for (String item : cart) g.perform(new Prices.Price(item)); })}. The questions are
   * independent: operations of one handler are asked at once, as a set. In a block with other questions the same
   * loop is {@code t.gather(g -> ..)}, its list one of the block's names. */
  public static <X, E, R extends Eff<E, ?>> Comp<List<X>, R> gather(Consumer<? super Gather<X, E, R>> loop) {
    return Effects.<List<X>, E, R>together(t -> t.yield(t.<X>gather(loop), answers -> answers));
  }

  // -- a source of computations asked one after another, as far as the answers say

  /** the computations of {@code xs} run in their order UNTIL a value is {@code wanted}: that value, and the ones
   * after it are never asked; none wanted, all were asked and the value is empty. Not a set: each is asked when
   * the answer before it did not decide */
  public static <X, R> Comp<Optional<X>, R> first(
      Iterable<? extends Comp<X, ? super R>> xs, Predicate<? super X> wanted) {
    return defer(() -> firstFrom(Cursor.<Comp<X, ? super R>>of(xs.iterator()), wanted));
  }

  private static <X, R> Comp<Optional<X>, R> firstFrom(Cursor<Comp<X, ? super R>> at, Predicate<? super X> wanted) {
    if (at == null) return pure(Optional.empty());
    return Effects.<X, R>fit(at.value).flatMap(x -> wanted.test(x)
        ? Effects.<Optional<X>, R>pure(Optional.ofNullable(x)) : firstFrom(at.next(), wanted));
  }

  /** whether a value is {@code wanted}: asked no further than the first that is */
  public static <X, R> Comp<Boolean, R> any(Iterable<? extends Comp<X, ? super R>> xs, Predicate<? super X> wanted) {
    return Effects.<X, R>first(xs, wanted).map(Optional::isPresent);
  }

  /** whether every value is {@code wanted}: asked no further than the first that is not */
  public static <X, R> Comp<Boolean, R> all(Iterable<? extends Comp<X, ? super R>> xs, Predicate<? super X> wanted) {
    return Effects.<X, R>first(xs, wanted.negate()).map(Optional::isEmpty);
  }

  /** A SET OF OPERATIONS ASKED AT ONCE: independent of each other, known before any is answered. Their answers,
   * in their order. A handler with a clause for sets ({@link Batch}) is given them together; any other handler —
   * and any handler with an interceptor or an observer on the way — is asked them one by one, so the program is
   * the same under either. */
  public static <E, X, O extends Op<E, X, ? super O>> Comp<List<X>, Eff<E, ?>> performAll(List<? extends O> ops) {
    return new Comp<>(DOOR.askAll(ops.toArray()));
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
    return new Comp<>(DOOR.handle(new Handlers.Quick<>(clause, null), null, p.eff));
  }

  /** the same, with a clause for a SET of operations asked at once ({@link Batch}; {@link #performAll}, a
   * {@link Gather}): it is given them all before any is answered — one query, a cache, each repeated one once. An
   * operation asked alone still goes to {@code clause}. */
  public static <F, A, R> Comp<A, R> handle(
      Clause<F, A, R> clause, Batch<F, A, R> batch, Comp<A, ? super Eff<F, R>> p) {
    return new Comp<>(DOOR.handle(new Handlers.Quick<>(clause, batch), null, p.eff));
  }

  /** the same, the handler with a state: a {@link StateClause} is given the state of this path, the operation and
   * the continuation, and resumes with the answer and the next state —
   * {@code handle(0, (s, op, k) -> switch (op) { case Get g -> k.resume(g, s); case Put p -> k.resume(p, null,
   * p.value()); }, p)}. A continuation resumed twice goes on twice from the state it was asked in. */
  public static <F, S, A, R> Comp<A, R> handle(
      S s0, StateClause<F, S, A, R> clause, Comp<A, ? super Eff<F, R>> p) {
    return new Comp<>(DOOR.handle(new Handlers.Stateful<F, S, A, A, R>(clause, null, (state, a) -> a), s0, p.eff));
  }

  /** the same, its value made of the last state and the program's value: {@code ret} */
  public static <F, S, A, B, R> Comp<B, R> handle(S s0, StateClause<F, S, B, R> clause,
      BiFunction<? super S, ? super A, ? extends B> ret, Comp<A, ? super Eff<F, R>> p) {
    return new Comp<>(DOOR.handle(new Handlers.Stateful<F, S, A, B, R>(clause, null, ret), s0, p.eff));
  }

  /** a handler with a state and a clause for a SET of operations ({@link StateBatch}): the state of this path,
   * all the operations of the set, and the continuation — {@code k.answer(op, x)} for each, then
   * {@code k.resume(next)} */
  public static <F, S, A, R> Comp<A, R> handle(S s0, StateClause<F, S, A, R> clause,
      StateBatch<F, S, A, R> batch, Comp<A, ? super Eff<F, R>> p) {
    return new Comp<>(DOOR.handle(new Handlers.Stateful<F, S, A, A, R>(clause, batch, (state, a) -> a), s0, p.eff));
  }

  /** the same, its value made of the last state and the program's value: {@code ret} */
  public static <F, S, A, B, R> Comp<B, R> handle(S s0, StateClause<F, S, B, R> clause,
      StateBatch<F, S, B, R> batch, BiFunction<? super S, ? super A, ? extends B> ret,
      Comp<A, ? super Eff<F, R>> p) {
    return new Comp<>(DOOR.handle(new Handlers.Stateful<F, S, A, B, R>(clause, batch, ret), s0, p.eff));
  }

  /** WHAT A PROGRAM REALLY ASKS OF ITS FIRST EFFECT, AS IT ASKS IT: {@code see} is given every operation asked
   * alone, as a list of one, and every set, whole — and each goes on as it came, a set still a set. Unlike
   * {@code observe}, which is shown operations one by one and so has a set asked one by one: this shows the rounds.
   * The effect stays in the row. For a trace, a count of the queries, a test of how a program asks. */
  public static <F, A, T> Comp<A, Eff<F, ? extends T>> rounds(
      Consumer<? super List<F>> see, Comp<A, ? super Eff<F, T>> p) {
    Clause<F, A, Eff<F, ? extends T>> one = (op, k) -> {
      see.accept(List.of(op));
      return k.afterAny(DOOR.ask(op, 0));
    };
    Batch<F, A, Eff<F, ? extends T>> all = (ops, k) -> {
      see.accept(ops);
      okay.core.Comp<Object, Object> outside = Handlers.trusted(DOOR.askAll(ops.toArray(), 0));
      return k.resumeAfter(new Comp<Object, Eff<F, ? extends T>>(outside.map(xs -> {
        List<?> answers = (List<?>) xs;
        for (int i = 0; i < answers.size(); i++) k.answerAny(ops.get(i), answers.get(i));
        return xs;
      })));
    };
    return Effects.<F, A, Eff<F, ? extends T>>handle(one, all, under(p));
  }

  /** EACH OPERATION OF THE FIRST EFFECT ASKED ONCE: an operation equal to one asked before — records are equal by
   * their fields — is answered by what that one was, and never goes further; a set goes on as the set of those not
   * known, each once. The effect stays in the row: this is no handler of it, the handler outside answers what is
   * asked. What is remembered is the run's: each run starts with nothing, and every path of a continuation resumed
   * twice shares it — an answer does not depend on the path. For an effect whose answers do not change within a
   * run: a lookup, a read; not a counter, a clock, a write. */
  public static <F, A, T> Comp<A, Eff<F, ? extends T>> cached(Comp<A, ? super Eff<F, T>> p) {
    return defer(() -> {
      java.util.HashMap<Object, Object> known = new java.util.HashMap<>();
      Clause<F, A, Eff<F, ? extends T>> one = (op, k) -> {
        if (known.containsKey(op)) return new Comp<>(k.resumed(known.get(op), false, null));
        return k.afterAny(DOOR.ask(op, 0).map(x -> {
          known.put(op, x);
          return x;
        }));
      };
      Batch<F, A, Eff<F, ? extends T>> all = (ops, k) -> {
        java.util.LinkedHashSet<Object> missing = new java.util.LinkedHashSet<>();
        for (F op : ops) if (!known.containsKey(op)) missing.add(op);
        if (missing.isEmpty()) {
          for (F op : ops) k.answerAny(op, known.get(op));
          return k.resume();
        }
        Object[] asked = missing.toArray();
        okay.core.Comp<Object, Object> outside = Handlers.trusted(
            asked.length == 1 ? DOOR.ask(asked[0], 0).map(x -> List.of(x)) : DOOR.askAll(asked, 0));
        return k.resumeAfter(new Comp<Object, Eff<F, ? extends T>>(outside.map(xs -> {
          List<?> answers = (List<?>) xs;
          for (int i = 0; i < asked.length; i++) known.put(asked[i], answers.get(i));
          for (F op : ops) k.answerAny(op, known.get(op));
          return xs;
        })));
      };
      return Effects.<F, A, Eff<F, ? extends T>>handle(one, all, under(p));
    });
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
