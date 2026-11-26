package okay.rows;

import static okay.rows.Comp.DOOR;

import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
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

  /** operations of ONE effect asked at once, as a set: their answers in their order. A handler with a clause for
   * sets is given them whole; any other is asked them one by one */
  public static <T, F, R extends F> Comp<List<T>, R> performAll(List<? extends Op<F, T, ?>> ops) {
    return new Comp<>(DOOR.askAll(ops.toArray(), okay.core.Rows.FAR));
  }

  /** a value, in any row */
  public static <A, R> Comp<A, R> pure(A a) {
    return Effects.pure(a);
  }

  /** A BLOCK of a row that is a set: questions, each answer a name — {@code together(t -> {
   * Var<String> ref = t.perform(new Ids.Ref("B")); Var<Long> now = t.perform(new Clock.Now());
   * return t.yield(ref.with(now, (r, n) -> r + "@" + n)); })}. {@code t.perform} takes an operation of any effect
   * the row has; every question whose answers are known is asked before the next */
  public static <A, R> Comp<A, R> together(Function<? super Together<R>, ? extends Comp<A, R>> block) {
    return new Comp<>(okay.core.Comp.delay(() -> {
      Together<R> t = new Together<>();
      return Handlers.trusted(t.program(block.apply(t)));
    }));
  }

  /** what {@link #together} gives its function: a {@link Block} whose operations are asked by their class */
  public static final class Together<R> extends Block<R> {
    Together() {
      super(okay.core.Rows.FAR);
    }

    /** an operation of an effect the row has, asked: its answer's name */
    public <T> Var<T> perform(Op<? super R, T, ?> op) {
      return asked(op);
    }

    /** A PLAIN LOOP OF QUESTIONS, all answered by one type; their answers, in their order, under one name —
     * {@code t.gather(g -> { for (String item : cart) g.perform(new Price(item)); })} */
    public <T> Var<List<T>> gather(Consumer<? super Gather<T, R>> loop) {
      Gather<T, R> g = new Gather<>(this);
      loop.accept(g);
      return list(g.asked);
    }
  }

  /** the loop of {@link Together#gather} and of {@link #gather} */
  public static final class Gather<X, R> {
    private final Block<R> block;
    final java.util.ArrayList<Var<X>> asked = new java.util.ArrayList<>();

    Gather(Block<R> block) {
      this.block = block;
    }

    /** an operation of an effect the row has, asked */
    public Var<X> perform(Op<? super R, X, ?> op) {
      Var<X> v = block.asked(op);
      asked.add(v);
      return v;
    }

    /** any computation of the row, asked */
    public Var<X> ask(Comp<X, ? super R> question) {
      Var<X> v = block.ask(question);
      asked.add(v);
      return v;
    }
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
  public static final class Of9<L> extends Named { Of9(Class<?>... effects) { super(effects); } }
  public static final class Of10<L> extends Named { Of10(Class<?>... effects) { super(effects); } }
  public static final class Of11<L> extends Named { Of11(Class<?>... effects) { super(effects); } }
  public static final class Of12<L> extends Named { Of12(Class<?>... effects) { super(effects); } }

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
  public static <A, B, C, D, E, G, H, I, J, T> Of9<Eff<A, Eff<B, Eff<C, Eff<D, Eff<E, Eff<G, Eff<H, Eff<I, Eff<J,
      T>>>>>>>>>> of(Class<A> a, Class<B> b, Class<C> c, Class<D> d, Class<E> e, Class<G> g, Class<H> h, Class<I> i,
          Class<J> j) {
    return new Of9<>(a, b, c, d, e, g, h, i, j);
  }
  public static <A, B, C, D, E, G, H, I, J, M, T> Of10<Eff<A, Eff<B, Eff<C, Eff<D, Eff<E, Eff<G, Eff<H, Eff<I, Eff<J,
      Eff<M, T>>>>>>>>>>> of(Class<A> a, Class<B> b, Class<C> c, Class<D> d, Class<E> e, Class<G> g, Class<H> h,
          Class<I> i, Class<J> j, Class<M> m) {
    return new Of10<>(a, b, c, d, e, g, h, i, j, m);
  }
  public static <A, B, C, D, E, G, H, I, J, M, N, T> Of11<Eff<A, Eff<B, Eff<C, Eff<D, Eff<E, Eff<G, Eff<H, Eff<I,
      Eff<J, Eff<M, Eff<N, T>>>>>>>>>>>> of(Class<A> a, Class<B> b, Class<C> c, Class<D> d, Class<E> e, Class<G> g,
          Class<H> h, Class<I> i, Class<J> j, Class<M> m, Class<N> n) {
    return new Of11<>(a, b, c, d, e, g, h, i, j, m, n);
  }
  public static <A, B, C, D, E, G, H, I, J, M, N, O, T> Of12<Eff<A, Eff<B, Eff<C, Eff<D, Eff<E, Eff<G, Eff<H, Eff<I,
      Eff<J, Eff<M, Eff<N, Eff<O, T>>>>>>>>>>>>> of(Class<A> a, Class<B> b, Class<C> c, Class<D> d, Class<E> e,
          Class<G> g, Class<H> h, Class<I> i, Class<J> j, Class<M> m, Class<N> n, Class<O> o) {
    return new Of12<>(a, b, c, d, e, g, h, i, j, m, n, o);
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
  public static <V, L extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
      ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
          ? extends Eff<? super V, ? extends Eff<? super V, Pure>>>>>>>>>> Row<V, L> row(
      Of9<L> effects) {
    return new Row<>();
  }
  public static <V, L extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
      ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
          ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V, Pure>>>>>>>>>>> Row<V, L> row(
      Of10<L> effects) {
    return new Row<>();
  }
  public static <V, L extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
      ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
          ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
              Pure>>>>>>>>>>>> Row<V, L> row(
      Of11<L> effects) {
    return new Row<>();
  }
  public static <V, L extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
      ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
          ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
              ? extends Eff<? super V, Pure>>>>>>>>>>>>> Row<V, L> row(
      Of12<L> effects) {
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
    public <V extends K, L extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
        ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
            ? extends Eff<? super V, ? extends Eff<? super V, K>>>>>>>>>> Row<V, L> row(
        Of9<L> effects) {
      return new Row<>();
    }
    public <V extends K, L extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
        ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
            ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V, K>>>>>>>>>>> Row<V, L> row(
        Of10<L> effects) {
      return new Row<>();
    }
    public <V extends K, L extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
        ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
            ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
                K>>>>>>>>>>>> Row<V, L> row(
        Of11<L> effects) {
      return new Row<>();
    }
    public <V extends K, L extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
        ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
            ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
                ? extends Eff<? super V, K>>>>>>>>>>>>> Row<V, L> row(
        Of12<L> effects) {
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
    public <L extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
        ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
            ? extends Eff<? super V, Pure>>>>>>>>>> Row<V, L> row(
        Of9<L> effects) {
      check(effects);
      return new Row<>();
    }
    public <L extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
        ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
            ? extends Eff<? super V, ? extends Eff<? super V, Pure>>>>>>>>>>> Row<V, L> row(
        Of10<L> effects) {
      check(effects);
      return new Row<>();
    }
    public <L extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
        ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
            ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V, Pure>>>>>>>>>>>> Row<V, L> row(
        Of11<L> effects) {
      check(effects);
      return new Row<>();
    }
    public <L extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
        ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
            ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V, ? extends Eff<? super V,
                Pure>>>>>>>>>>>>> Row<V, L> row(
        Of12<L> effects) {
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
  public interface P8 {}
  public interface P9 {}
  public interface P10 {}
  public interface P11 {}

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
  public static <T, F, R extends Eff<?, ? extends Eff<?, ? extends Eff<?, ? extends Eff<?, ? extends Eff<?,
      ? extends Eff<?, ? extends Eff<?, ? extends Eff<?, ? extends Eff<F, ?>>>>>>>>>> Comp<T, R> ask(
      K<?, R> k, Op<F, T, ?> op, P8... place) {
    return asked(op);
  }
  public static <T, F, R extends Eff<?, ? extends Eff<?, ? extends Eff<?, ? extends Eff<?, ? extends Eff<?,
      ? extends Eff<?, ? extends Eff<?, ? extends Eff<?, ? extends Eff<?, ? extends Eff<F, ?>>>>>>>>>>> Comp<T, R> ask(
      K<?, R> k, Op<F, T, ?> op, P9... place) {
    return asked(op);
  }
  public static <T, F, R extends Eff<?, ? extends Eff<?, ? extends Eff<?, ? extends Eff<?, ? extends Eff<?,
      ? extends Eff<?, ? extends Eff<?, ? extends Eff<?, ? extends Eff<?, ? extends Eff<?, ? extends Eff<F,
          ?>>>>>>>>>>>> Comp<T, R> ask(
      K<?, R> k, Op<F, T, ?> op, P10... place) {
    return asked(op);
  }
  public static <T, F, R extends Eff<?, ? extends Eff<?, ? extends Eff<?, ? extends Eff<?, ? extends Eff<?,
      ? extends Eff<?, ? extends Eff<?, ? extends Eff<?, ? extends Eff<?, ? extends Eff<?, ? extends Eff<?,
          ? extends Eff<F, ?>>>>>>>>>>>>> Comp<T, R> ask(
      K<?, R> k, Op<F, T, ?> op, P11... place) {
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

  /** {@link Effects#answeringAtOnce} */
  @SafeVarargs
  @SuppressWarnings("varargs")
  public static <F, A, R> Comp<A, R> answeringAtOnce(Function<? super F, ? extends Answer<?>> answers,
      Comp<A, ? super Eff<F, R>> p, F... effect) {
    return Effects.answeringAtOnce(answers, p, effect);
  }

  /** {@link Effects#cached} */
  @SafeVarargs
  @SuppressWarnings("varargs")
  public static <F, A, T> Comp<A, Eff<F, ? extends T>> cached(Comp<A, ? super Eff<F, T>> p, F... effect) {
    return Effects.cached(p, effect);
  }

  /** {@link Effects#rounds} */
  @SafeVarargs
  @SuppressWarnings("varargs")
  public static <F, A, T> Comp<A, Eff<F, ? extends T>> rounds(Consumer<? super List<F>> see,
      Comp<A, ? super Eff<F, T>> p, F... effect) {
    return Effects.rounds(see, p, effect);
  }

  /** {@link Effects#observe} */
  public static <F, A, T> Comp<A, Eff<F, ? extends T>> observe(Class<? super F> effect,
      Function<? super F, ? extends Comp<?, ? super Eff<F, ? extends T>>> see, Comp<A, ? super Eff<F, T>> p) {
    return Effects.observe(effect, see, p);
  }

  /** A BLOCK THAT IS ONE PLAIN LOOP of a row that is a set: the list of its answers —
   * {@code gather(g -> { for (String item : cart) g.perform(new Prices.Price(item)); })} */
  public static <X, R> Comp<List<X>, R> gather(Consumer<? super Gather<X, R>> loop) {
    return Sets.<List<X>, R>together(t -> t.yield(t.<X>gather(loop), answers -> answers));
  }

  /** {@link Effects#fold} */
  public static <X, S, R> Comp<S, R> fold(
      Iterable<? extends X> xs, S zero, BiFunction<? super S, ? super X, ? extends Comp<S, ? super R>> step) {
    return Effects.fold(xs, zero, step);
  }

  /** {@link Effects#forEach} */
  public static <X, R> Comp<Void, R> forEach(
      Iterable<? extends X> xs, Function<? super X, ? extends Comp<?, ? super R>> body) {
    return Effects.forEach(xs, body);
  }

  /** {@link Effects#collect} */
  public static <X, Y, R> Comp<List<Y>, R> collect(
      Iterable<? extends X> xs, Function<? super X, ? extends Comp<Y, ? super R>> f) {
    return Effects.collect(xs, f);
  }

  /** {@link Effects#sequence} */
  public static <X, R> Comp<List<X>, R> sequence(Iterable<? extends Comp<X, ? super R>> xs) {
    return Effects.sequence(xs);
  }

  /** {@link Effects#reduce} */
  public static <X, S, R> Comp<S, R> reduce(
      Iterable<? extends Comp<X, ? super R>> xs, S zero, BiFunction<? super S, ? super X, ? extends S> combine) {
    return Effects.reduce(xs, zero, combine);
  }

  /** {@link Effects#first} */
  public static <X, R> Comp<Optional<X>, R> first(
      Iterable<? extends Comp<X, ? super R>> xs, Predicate<? super X> wanted) {
    return Effects.first(xs, wanted);
  }

  /** {@link Effects#any} */
  public static <X, R> Comp<Boolean, R> any(Iterable<? extends Comp<X, ? super R>> xs, Predicate<? super X> wanted) {
    return Effects.any(xs, wanted);
  }

  /** {@link Effects#all} */
  public static <X, R> Comp<Boolean, R> all(Iterable<? extends Comp<X, ? super R>> xs, Predicate<? super X> wanted) {
    return Effects.all(xs, wanted);
  }

  /** {@link Effects#defer} */
  public static <A, R> Comp<A, R> defer(Supplier<? extends Comp<A, ? super R>> p) {
    return Effects.defer(p);
  }

  /** {@link Effects#run} */
  public static <A> A run(Comp<A, ? super Pure> p) {
    return Effects.run(p);
  }
}
