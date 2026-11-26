package okay.rows;

import static okay.rows.Comp.DOOR;

import java.util.function.BiFunction;
import java.util.function.Function;
import okay.effect.Answer;
import okay.effect.Eff;
import okay.effect.Op;
import okay.effect.Pure;

/**
 * ROWS AS SETS. A program's row is a type bounded by the effects it asks, in any order:
 * {@code static <R extends Ids & Clock> Comp<String, R> stamp()}. An operation is asked by {@link #perform}, whose
 * one bound — {@code R extends F} — is the whole check that the row has the effect; a program of fewer effects is
 * a program of more as it is, with nothing written.
 *
 * <p>An effect, for that, is a sealed interface with no supertype that names it — each operation says its answer
 * and its effect: {@code sealed interface Ids { record Ref(String p) implements Ids, Operation<String, Ids> {} }},
 * as the library's own are ({@code okay.rows.std}). (Java refuses a type that inherits one generic interface with
 * two arguments, so effects that each extend {@code Operation<T, Self>} could never be a row together.)
 *
 * <p>The handlers are {@link Effects}': functions over a program whose row is a list, {@code Eff<F, R>} —
 * {@code static <A, R> Comp<A, R> stored(Comp<A, ? super Eff<Store, R>> c) { return handle((op, k) -> .., c); }},
 * {@code State.run}, {@code Clock.at}, any written before. Between the two, once, where the handlers are put,
 * the set is written as a list: {@code var app = row(of(Store.class, Ids.class));
 * run(identified(stored(app.of(program))))}. javac refuses a program that asks more than the list has, a handler
 * missing, handlers out of the list's order.
 *
 * <p>An operation asked here goes to no place: the nearest handler whose effect it is of answers — a handler
 * knows its effect's class from where it is made. The words of {@link Effects} a program of a set needs are here
 * too ({@code answering}, {@code handle}, {@code run}, {@code pure}), so that one import serves.
 */
public final class Sets {
  private Sets() {}

  // ---- a program

  /** the operation {@code op}, in a row that has its effect */
  public static <T, F, R extends F> Comp<T, R> perform(Op<F, T, ?> op) {
    return new Comp<>(DOOR.ask(op, okay.core.Rows.FAR));
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
      Together<R, R> t = new Together<>(okay.core.Rows.FAR);
      return Handlers.trusted(t.program(block.apply(t)));
    }));
  }

  // ---- the set as a list, for the handlers

  /** effects named in the order of their handlers ({@link #of}); {@code L} is their list */
  public static final class Of1<L> extends Named { Of1(Class<?>... effects) { super(effects); } }
  public static final class Of2<L> extends Named { Of2(Class<?>... effects) { super(effects); } }
  public static final class Of3<L> extends Named { Of3(Class<?>... effects) { super(effects); } }
  public static final class Of4<L> extends Named { Of4(Class<?>... effects) { super(effects); } }
  public static final class Of5<L> extends Named { Of5(Class<?>... effects) { super(effects); } }
  public static final class Of6<L> extends Named { Of6(Class<?>... effects) { super(effects); } }
  public static final class Of7<L> extends Named { Of7(Class<?>... effects) { super(effects); } }
  public static final class Of8<L> extends Named { Of8(Class<?>... effects) { super(effects); } }

  // the effects of a list, by their classes: its tail T is what `row` or `Keep.row` says
  public static <A, T> Of1<Eff<A, T>> of(Class<A> a) {
    return new Of1<>(a);
  }
  public static <A, B, T> Of2<Eff<A, Eff<B, T>>> of(Class<A> a, Class<B> b) {
    return new Of2<>(a, b);
  }
  public static <A, B, C, T> Of3<Eff<A, Eff<B, Eff<C, T>>>> of(Class<A> a, Class<B> b, Class<C> c) {
    return new Of3<>(a, b, c);
  }
  public static <A, B, C, D, T> Of4<Eff<A, Eff<B, Eff<C, Eff<D, T>>>>> of(Class<A> a, Class<B> b, Class<C> c,
      Class<D> d) {
    return new Of4<>(a, b, c, d);
  }
  public static <A, B, C, D, E, T> Of5<Eff<A, Eff<B, Eff<C, Eff<D, Eff<E, T>>>>>> of(Class<A> a, Class<B> b,
      Class<C> c, Class<D> d, Class<E> e) {
    return new Of5<>(a, b, c, d, e);
  }
  public static <A, B, C, D, E, G, T> Of6<Eff<A, Eff<B, Eff<C, Eff<D, Eff<E, Eff<G, T>>>>>>> of(Class<A> a,
      Class<B> b, Class<C> c, Class<D> d, Class<E> e, Class<G> g) {
    return new Of6<>(a, b, c, d, e, g);
  }
  public static <A, B, C, D, E, G, H, T> Of7<Eff<A, Eff<B, Eff<C, Eff<D, Eff<E, Eff<G, Eff<H, T>>>>>>>> of(Class<A> a,
      Class<B> b, Class<C> c, Class<D> d, Class<E> e, Class<G> g, Class<H> h) {
    return new Of7<>(a, b, c, d, e, g, h);
  }
  public static <A, B, C, D, E, G, H, I, T> Of8<Eff<A, Eff<B, Eff<C, Eff<D, Eff<E, Eff<G, Eff<H, Eff<I,
      T>>>>>>>>> of(Class<A> a, Class<B> b, Class<C> c, Class<D> d, Class<E> e, Class<G> g, Class<H> h, Class<I> i) {
    return new Of8<>(a, b, c, d, e, g, h, i);
  }

  /** an effect with type arguments, named for a list: {@code of(Sets.<State<Integer>>the(State.class), ..)} — a
   * class says nothing of them */
  @SuppressWarnings("unchecked")
  public static <F> Class<F> the(Class<? super F> effect) {
    return (Class<F>) effect;
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
  public static <V, L extends Eff<? super V, Pure>> Row<V, L> row(
      Of1<L> effects) {
    return new Row<>();
  }
  public static <V, L extends Eff<? super V, ? extends Eff<? super V, Pure>>> Row<V, L> row(
      Of2<L> effects) {
    return new Row<>();
  }
  public static <V, L extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V, Pure>>>> Row<V, L> row(
      Of3<L> effects) {
    return new Row<>();
  }
  public static <V, L extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
      ? extends Eff<? super V, Pure>>>>> Row<V, L> row(
      Of4<L> effects) {
    return new Row<>();
  }
  public static <V, L extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
      ? extends Eff<? super V, ? extends Eff<? super V, Pure>>>>>> Row<V, L> row(
      Of5<L> effects) {
    return new Row<>();
  }
  public static <V, L extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
      ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V, Pure>>>>>>> Row<V, L> row(
      Of6<L> effects) {
    return new Row<>();
  }
  public static <V, L extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
      ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
          Pure>>>>>>>> Row<V, L> row(
      Of7<L> effects) {
    return new Row<>();
  }
  public static <V, L extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
      ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
          ? extends Eff<? super V, Pure>>>>>>>>> Row<V, L> row(
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

    public <V extends K, L extends Eff<? super V, K>> Row<V, L> row(
        Of1<L> effects) {
      return new Row<>();
    }
    public <V extends K, L extends Eff<? super V, ? extends Eff<? super V, K>>> Row<V, L> row(
        Of2<L> effects) {
      return new Row<>();
    }
    public <V extends K, L extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V, K>>>> Row<V, L> row(
        Of3<L> effects) {
      return new Row<>();
    }
    public <V extends K, L extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
        ? extends Eff<? super V, K>>>>> Row<V, L> row(
        Of4<L> effects) {
      return new Row<>();
    }
    public <V extends K, L extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
        ? extends Eff<? super V, ? extends Eff<? super V, K>>>>>> Row<V, L> row(
        Of5<L> effects) {
      return new Row<>();
    }
    public <V extends K, L extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
        ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V, K>>>>>>> Row<V, L> row(
        Of6<L> effects) {
      return new Row<>();
    }
    public <V extends K, L extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
        ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
            K>>>>>>>> Row<V, L> row(
        Of7<L> effects) {
      return new Row<>();
    }
    public <V extends K, L extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
        ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
            ? extends Eff<? super V, K>>>>>>>>> Row<V, L> row(
        Of8<L> effects) {
      return new Row<>();
    }
  }

  // ---- the set NAMED: a class of the application whose type parameter is bounded by its effects

  /** what {@link #of} gives: the classes named */
  public abstract static class Named {
    final Class<?>[] effects;

    Named(Class<?>[] effects) {
      this.effects = effects;
    }
  }

  /** THE SET NAMED BY A CLASS: {@code final class Shop<V extends Store & Mail & Ids> { <A> A run(Comp<A, ? super V>
   * p) { var row = Sets.<V>exactly(Shop.class).row(of(Store.class, Mail.class, Ids.class)); return
   * Sets.run(identified(mailed(stored(row.of(p))))); } }}. The type parameter says the set, so the class can be
   * written where a {@link Row} cannot: {@code static final Shop<?> SHOP = new Shop<>();} is a field, a bean, a
   * parameter, and {@code SHOP.run(program)} is refused by javac for a program that asks more. javac also refuses a
   * list that names an effect the parameter is not bounded by. That the parameter is bounded by no effect the
   * list has NOT — one without a handler — javac cannot see: {@code row} checks it when it is called, by the
   * bounds of {@code named}'s first type parameter, and refuses there */
  public static <V> Exactly<V> exactly(Class<?> named) {
    return new Exactly<>(named);
  }

  public static final class Exactly<V> {
    private final Class<?> named;

    Exactly(Class<?> named) {
      this.named = named;
    }

    private void check(Named listed) {
      if (named.getTypeParameters().length == 0) throw new IllegalArgumentException("okay.rows.Sets: "
          + named.getName() + " has no type parameter to name a set");
      for (java.lang.reflect.Type bound : named.getTypeParameters()[0].getBounds()) {
        Class<?> effect = bound instanceof java.lang.reflect.ParameterizedType p ? (Class<?>) p.getRawType()
            : bound instanceof Class<?> c ? c : null;
        if (effect == null || effect == Object.class) continue;
        boolean has = false;
        for (Class<?> c : listed.effects) has |= c == effect;
        if (!has) throw new IllegalStateException("okay.rows.Sets: " + named.getName() + " is bounded by "
            + effect.getName() + ", which the list of its handlers does not name: a program could ask it, and "
            + "nothing would answer");
      }
    }

    public <L extends Eff<? super V, Pure>> Row<V, L> row(
        Of1<L> effects) {
      check(effects);
      return new Row<>();
    }
    public <L extends Eff<? super V, ? extends Eff<? super V, Pure>>> Row<V, L> row(
        Of2<L> effects) {
      check(effects);
      return new Row<>();
    }
    public <L extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V, Pure>>>> Row<V, L> row(
        Of3<L> effects) {
      check(effects);
      return new Row<>();
    }
    public <L extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
        Pure>>>>> Row<V, L> row(
        Of4<L> effects) {
      check(effects);
      return new Row<>();
    }
    public <L extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
        ? extends Eff<? super V, Pure>>>>>> Row<V, L> row(
        Of5<L> effects) {
      check(effects);
      return new Row<>();
    }
    public <L extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
        ? extends Eff<? super V, ? extends Eff<? super V, Pure>>>>>>> Row<V, L> row(
        Of6<L> effects) {
      check(effects);
      return new Row<>();
    }
    public <L extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
        ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V, Pure>>>>>>>> Row<V, L> row(
        Of7<L> effects) {
      check(effects);
      return new Row<>();
    }
    public <L extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
        ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
            Pure>>>>>>>>> Row<V, L> row(
        Of8<L> effects) {
      check(effects);
      return new Row<>();
    }
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

  public static <T, F, R extends Eff<F, ?>> Comp<T, R> ask(
      K<?, R> k, Op<F, T, ?> op, P0... place) {
    return asked(op);
  }
  public static <T, F, R extends Eff<?, ? extends Eff<F, ?>>> Comp<T, R> ask(
      K<?, R> k, Op<F, T, ?> op, P1... place) {
    return asked(op);
  }
  public static <T, F, R extends Eff<?, ? extends Eff<?, ? extends Eff<F, ?>>>> Comp<T, R> ask(
      K<?, R> k, Op<F, T, ?> op, P2... place) {
    return asked(op);
  }
  public static <T, F, R extends Eff<?, ? extends Eff<?, ? extends Eff<?, ? extends Eff<F, ?>>>>> Comp<T, R> ask(
      K<?, R> k, Op<F, T, ?> op, P3... place) {
    return asked(op);
  }
  public static <T, F, R extends Eff<?, ? extends Eff<?, ? extends Eff<?, ? extends Eff<?, ? extends Eff<F,
      ?>>>>>> Comp<T, R> ask(
      K<?, R> k, Op<F, T, ?> op, P4... place) {
    return asked(op);
  }
  public static <T, F, R extends Eff<?, ? extends Eff<?, ? extends Eff<?, ? extends Eff<?, ? extends Eff<?,
      ? extends Eff<F, ?>>>>>>> Comp<T, R> ask(
      K<?, R> k, Op<F, T, ?> op, P5... place) {
    return asked(op);
  }
  public static <T, F, R extends Eff<?, ? extends Eff<?, ? extends Eff<?, ? extends Eff<?, ? extends Eff<?,
      ? extends Eff<?, ? extends Eff<F, ?>>>>>>>> Comp<T, R> ask(
      K<?, R> k, Op<F, T, ?> op, P6... place) {
    return asked(op);
  }
  public static <T, F, R extends Eff<?, ? extends Eff<?, ? extends Eff<?, ? extends Eff<?, ? extends Eff<?,
      ? extends Eff<?, ? extends Eff<?, ? extends Eff<F, ?>>>>>>>>> Comp<T, R> ask(
      K<?, R> k, Op<F, T, ?> op, P7... place) {
    return asked(op);
  }

  private static <T, R> Comp<T, R> asked(Object op) {
    return new Comp<>(DOOR.ask(op, okay.core.Rows.FAR));
  }

  // ---- Effects' words a program of a set needs, so that `import static okay.rows.Sets.*` serves alone

  /** {@link Effects#answering} */
  @SafeVarargs
  @SuppressWarnings("varargs")
  public static <F, A, R> Comp<A, R> answering(Function<? super F, ? extends Answer<?>> answers,
      Comp<A, ? super Eff<F, R>> p, F... effect) {
    return Effects.answering(answers, p, effect);
  }

  /** {@link Effects#handle(Clause, Comp, Object[])} */
  @SafeVarargs
  @SuppressWarnings("varargs")
  public static <F, A, R> Comp<A, R> handle(Clause<F, A, R> clause, Comp<A, ? super Eff<F, R>> p, F... effect) {
    return Effects.handle(clause, p, effect);
  }

  /** {@link Effects#handle(Clause, Batch, Comp, Object[])}: a clause for the operations of the effect a block asks
   * at once, as one set */
  @SafeVarargs
  @SuppressWarnings("varargs")
  public static <F, A, R> Comp<A, R> handle(Clause<F, A, R> clause, Batch<F, A, R> batch,
      Comp<A, ? super Eff<F, R>> p, F... effect) {
    return Effects.handle(clause, batch, p, effect);
  }

  /** {@link Effects#handle(Function, Clause, Comp, Object[])} */
  @SafeVarargs
  @SuppressWarnings("varargs")
  public static <F, A, B, R> Comp<B, R> handle(Function<? super A, ? extends Comp<B, R>> ret,
      Clause<F, B, R> clause, Comp<A, ? super Eff<F, R>> p, F... effect) {
    return Effects.handle(ret, clause, p, effect);
  }

  /** {@link Effects#handle(Object, StateClause, Comp, Object[])} */
  @SafeVarargs
  @SuppressWarnings("varargs")
  public static <F, S, A, R> Comp<A, R> handle(S s0, StateClause<F, S, A, R> clause,
      Comp<A, ? super Eff<F, R>> p, F... effect) {
    return Effects.handle(s0, clause, p, effect);
  }

  /** {@link Effects#handle(Object, StateClause, BiFunction, Comp, Object[])} */
  @SafeVarargs
  @SuppressWarnings("varargs")
  public static <F, S, A, B, R> Comp<B, R> handle(S s0, StateClause<F, S, B, R> clause,
      BiFunction<? super S, ? super A, ? extends B> ret, Comp<A, ? super Eff<F, R>> p, F... effect) {
    return Effects.handle(s0, clause, ret, p, effect);
  }

  /** {@link Effects#run} */
  public static <A> A run(Comp<A, ? super Pure> p) {
    return Effects.run(p);
  }
}
