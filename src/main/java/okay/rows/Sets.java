package okay.rows;

import static okay.rows.Comp.DOOR;

import java.util.function.BiFunction;
import java.util.function.Function;
import okay.effect.Answer;
import okay.effect.Op;
import okay.effect.Pure;

/**
 * ROWS AS SETS. A program's row is a type bounded by the effects it asks, in any order:
 * {@code static <R extends Ids & Clock> Comp<String, R> stamp()}. An operation is asked by {@link #perform}, whose
 * one bound — {@code R extends F} — is the whole check that the row has the effect; a program of fewer effects is
 * a program of more as it is, with nothing written.
 *
 * <p>An effect, for that, is a sealed interface WITHOUT type arguments of its own supertype — each operation names
 * its answer and its effect: {@code sealed interface Ids { record Ref(String p) implements Ids, Operation<String,
 * Ids> {} }}. (Java refuses a type that inherits one generic interface with two arguments, so effects that each
 * extend {@code Operation<T, Self>} could never be a row together.)
 *
 * <p>A handler is a function over a program, given the continuation, as in {@link Effects} — but of a row that
 * is a LIST, for a function can say "this effect and the rest" only of a list:
 * {@code static <A, R> Comp<A, R> stored(Comp<A, ? super And<Store, R>> c) { return handle((op, k) -> .., c); }}.
 * Between the two, once, where the handlers are put: the set written as a list —
 * {@code var app = row(of(Store.class, Ids.class)); run(identified(stored(app.of(program))))}. javac refuses a
 * program that asks more than the list has, a handler missing, handlers out of the list's order.
 *
 * <p>An operation finds its handler by the CLASS of its effect, not by a place: the nearest handler of that
 * effect answers. So the handlers here are not {@link Effects}': the two kinds of row are different types, and a
 * program of one is not given to the handlers of the other.
 */
public final class Sets {
  private Sets() {}

  // ---- a program

  /** the operation {@code op}, in a row that has its effect */
  public static <T, F, R extends F> Comp<T, R> perform(Op<F, T, ?> op) {
    return new Comp<>(DOOR.ask(op, 0));
  }

  /** a value, in any row */
  public static <A, R> Comp<A, R> pure(A a) {
    return Effects.pure(a);
  }

  /** A BLOCK ({@link Together}) of a row that is a set: questions, each answer a name — {@code together(t -> {
   * Var<String> ref = t.perform(new Ids.Ref("B")); Var<Long> now = t.perform(new Clock.Now());
   * return t.yield(ref.with(now, (r, n) -> r + "@" + n)); })}. {@code t.perform} takes an operation of any effect
   * the row has; every question whose answers are known is asked before the next */
  public static <A, R> Comp<A, R> together(Function<? super Together<R, R>, ? extends Comp<A, R>> block) {
    return new Comp<>(okay.core.Comp.delay(() -> {
      Together<R, R> t = new Together<>();
      return Handlers.trusted(t.program(block.apply(t)));
    }));
  }

  // ---- the row as a list, for the handlers

  /** a list of effects: {@code F}, then {@code R} */
  public interface And<F, R> {}

  /** the end of a list: nothing left to ask */
  public interface None {}

  /** effects named in the order of their handlers ({@link #of}); {@code L} is their list */
  public static final class Of1<L> { Of1() {} }
  public static final class Of2<L> { Of2() {} }
  public static final class Of3<L> { Of3() {} }
  public static final class Of4<L> { Of4() {} }
  public static final class Of5<L> { Of5() {} }
  public static final class Of6<L> { Of6() {} }
  public static final class Of7<L> { Of7() {} }
  public static final class Of8<L> { Of8() {} }

  // the effects of a list, by their classes: its tail T is what `row` or `Keep.row` says
  public static <A, T> Of1<And<A, T>> of(Class<A> a) {
    return new Of1<>();
  }
  public static <A, B, T> Of2<And<A, And<B, T>>> of(Class<A> a, Class<B> b) {
    return new Of2<>();
  }
  public static <A, B, C, T> Of3<And<A, And<B, And<C, T>>>> of(Class<A> a, Class<B> b, Class<C> c) {
    return new Of3<>();
  }
  public static <A, B, C, D, T> Of4<And<A, And<B, And<C, And<D, T>>>>> of(Class<A> a, Class<B> b, Class<C> c,
      Class<D> d) {
    return new Of4<>();
  }
  public static <A, B, C, D, E, T> Of5<And<A, And<B, And<C, And<D, And<E, T>>>>>> of(Class<A> a, Class<B> b,
      Class<C> c, Class<D> d, Class<E> e) {
    return new Of5<>();
  }
  public static <A, B, C, D, E, G, T> Of6<And<A, And<B, And<C, And<D, And<E, And<G, T>>>>>>> of(Class<A> a,
      Class<B> b, Class<C> c, Class<D> d, Class<E> e, Class<G> g) {
    return new Of6<>();
  }
  public static <A, B, C, D, E, G, H, T> Of7<And<A, And<B, And<C, And<D, And<E, And<G, And<H, T>>>>>>>> of(Class<A> a,
      Class<B> b, Class<C> c, Class<D> d, Class<E> e, Class<G> g, Class<H> h) {
    return new Of7<>();
  }
  public static <A, B, C, D, E, G, H, I, T> Of8<And<A, And<B, And<C, And<D, And<E, And<G, And<H, And<I,
      T>>>>>>>>> of(Class<A> a, Class<B> b, Class<C> c, Class<D> d, Class<E> e, Class<G> g, Class<H> h, Class<I> i) {
    return new Of8<>();
  }

  /** A SET AND ITS LIST. {@code V} is the set — exactly the effects named and the row left outside; {@code L} the
   * same as a list. Its type cannot be written (an intersection javac inferred): keep it in a {@code var}, or use
   * it where it is made. */
  public static final class Row<V, L> {
    Row() {}

    /** a program that asks nothing but the set, as a program of the list: for its handlers */
    public <A> Comp<A, L> of(Comp<A, ? super V> p) {
      return new Comp<>(p.eff);
    }
  }

  // the effects of a whole program: row(of(Store.class, Mail.class)). The bound of L is what makes V a subtype
  // of every effect of the list; with nothing else known of it, javac takes exactly their intersection
  public static <V, L extends And<? super V, None>> Row<V, L> row(
      Of1<L> effects) {
    return new Row<>();
  }
  public static <V, L extends And<? super V, ? extends And<? super V, None>>> Row<V, L> row(
      Of2<L> effects) {
    return new Row<>();
  }
  public static <V, L extends And<? super V, ? extends And<? super V, ? extends And<? super V, None>>>> Row<V, L> row(
      Of3<L> effects) {
    return new Row<>();
  }
  public static <V, L extends And<? super V, ? extends And<? super V, ? extends And<? super V,
      ? extends And<? super V, None>>>>> Row<V, L> row(
      Of4<L> effects) {
    return new Row<>();
  }
  public static <V, L extends And<? super V, ? extends And<? super V, ? extends And<? super V,
      ? extends And<? super V, ? extends And<? super V, None>>>>>> Row<V, L> row(
      Of5<L> effects) {
    return new Row<>();
  }
  public static <V, L extends And<? super V, ? extends And<? super V, ? extends And<? super V,
      ? extends And<? super V, ? extends And<? super V, ? extends And<? super V, None>>>>>>> Row<V, L> row(
      Of6<L> effects) {
    return new Row<>();
  }
  public static <V, L extends And<? super V, ? extends And<? super V, ? extends And<? super V,
      ? extends And<? super V, ? extends And<? super V, ? extends And<? super V, ? extends And<? super V,
          None>>>>>>>> Row<V, L> row(
      Of7<L> effects) {
    return new Row<>();
  }
  public static <V, L extends And<? super V, ? extends And<? super V, ? extends And<? super V,
      ? extends And<? super V, ? extends And<? super V, ? extends And<? super V, ? extends And<? super V,
          ? extends And<? super V, None>>>>>>>>> Row<V, L> row(
      Of8<L> effects) {
    return new Row<>();
  }

  /** inside a program of the row {@code K}: handlers put around a part of it, {@code K} left outside them —
   * {@code attempt(Sets.<R>keep().row(of(Abort.class)).of(part))} */
  public static <K> Keep<K> keep() {
    return new Keep<>();
  }

  public static final class Keep<K> {
    Keep() {}

    public <V extends K, L extends And<? super V, K>> Row<V, L> row(
        Of1<L> effects) {
      return new Row<>();
    }
    public <V extends K, L extends And<? super V, ? extends And<? super V, K>>> Row<V, L> row(
        Of2<L> effects) {
      return new Row<>();
    }
    public <V extends K, L extends And<? super V, ? extends And<? super V, ? extends And<? super V, K>>>> Row<V, L> row(
        Of3<L> effects) {
      return new Row<>();
    }
    public <V extends K, L extends And<? super V, ? extends And<? super V, ? extends And<? super V,
        ? extends And<? super V, K>>>>> Row<V, L> row(
        Of4<L> effects) {
      return new Row<>();
    }
    public <V extends K, L extends And<? super V, ? extends And<? super V, ? extends And<? super V,
        ? extends And<? super V, ? extends And<? super V, K>>>>>> Row<V, L> row(
        Of5<L> effects) {
      return new Row<>();
    }
    public <V extends K, L extends And<? super V, ? extends And<? super V, ? extends And<? super V,
        ? extends And<? super V, ? extends And<? super V, ? extends And<? super V, K>>>>>>> Row<V, L> row(
        Of6<L> effects) {
      return new Row<>();
    }
    public <V extends K, L extends And<? super V, ? extends And<? super V, ? extends And<? super V,
        ? extends And<? super V, ? extends And<? super V, ? extends And<? super V, ? extends And<? super V,
            K>>>>>>>> Row<V, L> row(
        Of7<L> effects) {
      return new Row<>();
    }
    public <V extends K, L extends And<? super V, ? extends And<? super V, ? extends And<? super V,
        ? extends And<? super V, ? extends And<? super V, ? extends And<? super V, ? extends And<? super V,
            ? extends And<? super V, K>>>>>>>>> Row<V, L> row(
        Of8<L> effects) {
      return new Row<>();
    }
  }

  // ---- handlers: functions over a program of a list, whose first effect they take

  private static Class<?> key(Object[] effect) {
    Class<?> c = effect.getClass().getComponentType();
    if (c == Object.class) throw new IllegalArgumentException("okay.rows.Sets: the handler's effect is not known "
        + "where it is made — its program's row must name the effect: Comp<A, ? super And<Store, R>>");
    return c;
  }

  /** A HANDLER THAT ONLY ANSWERS: {@code answering(op -> switch (op) { case Ref r -> r.answer(..); }, c)}. In
   * place: nothing of the program is taken. {@code effect} is not given: it tells the handler its effect's class */
  @SafeVarargs
  @SuppressWarnings("varargs") // the array is read for its class only
  public static <F, A, R> Comp<A, R> answering(Function<? super F, ? extends Answer<?>> answers,
      Comp<A, ? super And<F, R>> p, F... effect) {
    return new Comp<>(DOOR.handle(new Handlers.Answering<F>(answers, key(effect)), null, p.eff));
  }

  /** A HANDLER GIVEN THE CONTINUATION: {@code handle((op, k) -> switch (op) { case Put p -> k.resume(p, true); },
   * c)} — go on after the rest, leave it ({@code k.unwind()}), resume it twice. A clause that only resumes is
   * answered in place */
  @SafeVarargs
  @SuppressWarnings("varargs") // the array is read for its class only
  public static <F, A, R> Comp<A, R> handle(Clause<F, A, R> clause, Comp<A, ? super And<F, R>> p, F... effect) {
    return new Comp<>(DOOR.handle(new Handlers.Quick<>(clause, null, key(effect)), null, p.eff));
  }

  /** the same, the program's value made the handler's by {@code ret}: {@code handle(a -> pure(Optional.of(a)),
   * (op, k) -> pure(Optional.empty()), c)} */
  @SafeVarargs
  @SuppressWarnings("varargs") // the array is read for its class only
  public static <F, A, B, R> Comp<B, R> handle(Function<? super A, ? extends Comp<B, R>> ret,
      Clause<F, B, R> clause, Comp<A, ? super And<F, R>> p, F... effect) {
    Handler<F, A, B, R> h = new Handler<>() {
      public Comp<B, R> ret(A a) { return ret.apply(a); }
      public Comp<B, R> on(F op, K<B, R> k) { return clause.on(op, k); }
    };
    return new Comp<>(DOOR.handle(new Handlers.General<>(key(effect), h, false), null, p.eff));
  }

  /** A HANDLER WITH A STATE: {@code handle(1, (n, op, k) -> switch (op) { case Ref r -> k.resume(r, r.p() + n,
   * n + 1); }, c)}. A continuation resumed twice goes on twice from the state it was asked in */
  @SafeVarargs
  @SuppressWarnings("varargs") // the array is read for its class only
  public static <F, S, A, R> Comp<A, R> handle(S s0, StateClause<F, S, A, R> clause,
      Comp<A, ? super And<F, R>> p, F... effect) {
    return new Comp<>(DOOR.handle(
        new Handlers.Stateful<F, S, A, A, R>(clause, null, (state, a) -> a, key(effect)), s0, p.eff));
  }

  /** the same, its value made of the last state and the program's value */
  @SafeVarargs
  @SuppressWarnings("varargs") // the array is read for its class only
  public static <F, S, A, B, R> Comp<B, R> handle(S s0, StateClause<F, S, B, R> clause,
      BiFunction<? super S, ? super A, ? extends B> ret, Comp<A, ? super And<F, R>> p, F... effect) {
    return new Comp<>(DOOR.handle(new Handlers.Stateful<F, S, A, B, R>(clause, null, ret, key(effect)), s0, p.eff));
  }

  // ---- inside a clause: an effect of the list that stays, asked through the continuation, which knows that list
  // (one overload for each place it may have there; `place` is not given: it only tells the overloads apart)

  public interface P0 {}
  public interface P1 {}
  public interface P2 {}
  public interface P3 {}
  public interface P4 {}
  public interface P5 {}
  public interface P6 {}
  public interface P7 {}

  public static <T, F, R extends And<F, ?>> Comp<T, R> ask(
      K<?, R> k, Op<F, T, ?> op, P0... place) {
    return asked(op);
  }
  public static <T, F, R extends And<?, ? extends And<F, ?>>> Comp<T, R> ask(
      K<?, R> k, Op<F, T, ?> op, P1... place) {
    return asked(op);
  }
  public static <T, F, R extends And<?, ? extends And<?, ? extends And<F, ?>>>> Comp<T, R> ask(
      K<?, R> k, Op<F, T, ?> op, P2... place) {
    return asked(op);
  }
  public static <T, F, R extends And<?, ? extends And<?, ? extends And<?, ? extends And<F, ?>>>>> Comp<T, R> ask(
      K<?, R> k, Op<F, T, ?> op, P3... place) {
    return asked(op);
  }
  public static <T, F, R extends And<?, ? extends And<?, ? extends And<?, ? extends And<?, ? extends And<F,
      ?>>>>>> Comp<T, R> ask(
      K<?, R> k, Op<F, T, ?> op, P4... place) {
    return asked(op);
  }
  public static <T, F, R extends And<?, ? extends And<?, ? extends And<?, ? extends And<?, ? extends And<?,
      ? extends And<F, ?>>>>>>> Comp<T, R> ask(
      K<?, R> k, Op<F, T, ?> op, P5... place) {
    return asked(op);
  }
  public static <T, F, R extends And<?, ? extends And<?, ? extends And<?, ? extends And<?, ? extends And<?,
      ? extends And<?, ? extends And<F, ?>>>>>>>> Comp<T, R> ask(
      K<?, R> k, Op<F, T, ?> op, P6... place) {
    return asked(op);
  }
  public static <T, F, R extends And<?, ? extends And<?, ? extends And<?, ? extends And<?, ? extends And<?,
      ? extends And<?, ? extends And<?, ? extends And<F, ?>>>>>>>>> Comp<T, R> ask(
      K<?, R> k, Op<F, T, ?> op, P7... place) {
    return asked(op);
  }

  private static <T, R> Comp<T, R> asked(Object op) {
    return new Comp<>(DOOR.ask(op, 0));
  }

  /** a program with nothing left to ask, run: once, at the entry point */
  public static <A> A run(Comp<A, ? super None> p) {
    return Effects.run(new Comp<A, Pure>(p.eff));
  }
}
