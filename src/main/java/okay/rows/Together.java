package okay.rows;

import static okay.rows.Comp.DOOR;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import okay.effect.Eff;
import okay.effect.Op;

/**
 * A BLOCK OF QUESTIONS AND WHAT FOLLOWS FROM THEIR ANSWERS — a comprehension, as Scala's {@code for}, in plain Java:
 *
 * <pre>{@code
 * Comp<Long, Eff<Prices<?>, ?>> total = together(t -> {
 *   Var<Long> a = t.perform(new Prices.Price(first));
 *   Var<Long> b = t.perform(new Prices.Price(second));
 *   Var<Long> off = t.ask(a, b, (x, y) -> perform(new Prices.Discount(x + y)));   // asked when a and b are known
 *   return t.yield(a, b, off, (x, y, o) -> x + y - o);
 * });
 * }</pre>
 *
 * The questions are written one after another, each answer a name ({@link Var}) of its own type. A question may be
 * made of the answers of others: it says which, and is asked when they are known. So the block is flat, and it knows
 * what depends on what.
 *
 * <p>It asks in ROUNDS: first every question that depends on nothing; then those that needed only these; and so
 * on. The questions of one round are independent of each other, so those that are operations of one handler are
 * asked AT ONCE, as a set ({@code Effects.performAll}), whatever their types: a handler with a clause for sets
 * answers a round by one query. Any other computation asked is run in its turn within its round.
 *
 * <p>The block goes on once, with {@code yield} (a value) or {@code then} (a computation), when every question is
 * answered.
 */
public final class Together<E, R extends Eff<E, ?>> {
  /** a function of three */
  @FunctionalInterface
  public interface F3<A, B, C, T> {
    T apply(A a, B b, C c);
  }

  /** a question: a program known where it is written, or made of the answers of others when they are known —
   * {@code needs} their places, {@code null} for every question written before it */
  private record Member(okay.core.Comp<?, ?> eff, Function<Object[], ? extends Comp<?, ?>> made, int[] needs) {}

  private final ArrayList<Member> members = new ArrayList<>();
  private Function<Object[], ? extends Comp<?, ?>> next;
  private final Comp<?, ?> going = new Comp<>(null);
  /** the answers, while a function of the block reads them: of the path that goes on */
  private Object[] answers;

  Together() {}

  // -- questions

  /** an operation of the block's first effect, asked: its answer's name. The same as
   * {@code t.ask(Effects.perform(op))}; an operation of another effect is refused by the compiler */
  public <T, O extends Op<E, T, ? super O>> Var<T> perform(O op) {
    return member(new Member(DOOR.ask(op, 0), null, null));
  }

  /** a question of the block: any computation of its row; its answer's name */
  public <T> Var<T> ask(Comp<T, ? super R> question) {
    return member(new Member(question.eff, null, null));
  }

  /** a question made of one answer: asked when it is known */
  public <A, T> Var<T> ask(Var<A> a, Function<? super A, ? extends Comp<T, ? super R>> question) {
    int ia = place(a);
    return member(new Member(null, got -> question.apply(Handlers.trusted(got[ia])), new int[] {ia}));
  }

  /** a question made of two answers */
  public <A, B, T> Var<T> ask(
      Var<A> a, Var<B> b, BiFunction<? super A, ? super B, ? extends Comp<T, ? super R>> question) {
    int ia = place(a), ib = place(b);
    return member(new Member(null,
        got -> question.apply(Handlers.trusted(got[ia]), Handlers.trusted(got[ib])), new int[] {ia, ib}));
  }

  /** a question made of three answers */
  public <A, B, C, T> Var<T> ask(Var<A> a, Var<B> b, Var<C> c,
      F3<? super A, ? super B, ? super C, ? extends Comp<T, ? super R>> question) {
    int ia = place(a), ib = place(b), ic = place(c);
    return member(new Member(null, got -> question.apply(
        Handlers.trusted(got[ia]), Handlers.trusted(got[ib]), Handlers.trusted(got[ic])), new int[] {ia, ib, ic}));
  }

  /** a question made of any of the answers above it, read by {@link Var#get}: asked after every question written
   * before it. One that says its answers ({@code ask(a, b, ..)}) is asked as soon as those are known */
  public <T> Var<T> ask(Supplier<? extends Comp<T, ? super R>> question) {
    return member(new Member(null, got -> question.get(), null));
  }

  /** every question of {@code questions}: their answers, in their order, under one name */
  public <T> Var<List<T>> askAll(Iterable<? extends Comp<T, ? super R>> questions) {
    ArrayList<Integer> places = new ArrayList<>();
    for (Comp<T, ? super R> q : questions) places.add(ask(q).index);
    int[] needs = places.stream().mapToInt(Integer::intValue).toArray();
    return member(new Member(null, got -> {
      Object[] values = new Object[needs.length];
      for (int i = 0; i < needs.length; i++) values[i] = got[needs[i]];
      return Effects.pure(Collections.unmodifiableList(Arrays.asList(values)));
    }, needs));
  }

  /** a name for a value made of an answer: no question */
  public <A, T> Var<T> let(Var<A> a, Function<? super A, ? extends T> value) {
    return this.<A, T>ask(a, x -> Effects.<T, R>pure(value.apply(x)));
  }

  /** a name for a value made of two answers */
  public <A, B, T> Var<T> let(Var<A> a, Var<B> b, BiFunction<? super A, ? super B, ? extends T> value) {
    return this.<A, B, T>ask(a, b, (x, y) -> Effects.<T, R>pure(value.apply(x, y)));
  }

  // -- the last word of the block

  /** the block's value, made of one answer */
  public <A, V> Comp<V, R> yield(Var<A> a, Function<? super A, ? extends V> value) {
    int ia = place(a);
    return goOn(got -> Effects.<V, R>pure(value.apply(Handlers.trusted(got[ia]))));
  }

  /** the block's value, made of two answers */
  public <A, B, V> Comp<V, R> yield(Var<A> a, Var<B> b, BiFunction<? super A, ? super B, ? extends V> value) {
    int ia = place(a), ib = place(b);
    return goOn(got -> Effects.<V, R>pure(value.apply(Handlers.trusted(got[ia]), Handlers.trusted(got[ib]))));
  }

  /** the block's value, made of three answers */
  public <A, B, C, V> Comp<V, R> yield(
      Var<A> a, Var<B> b, Var<C> c, F3<? super A, ? super B, ? super C, ? extends V> value) {
    int ia = place(a), ib = place(b), ic = place(c);
    return goOn(got -> Effects.<V, R>pure(value.apply(
        Handlers.trusted(got[ia]), Handlers.trusted(got[ib]), Handlers.trusted(got[ic]))));
  }

  /** the block's value, made of any of its answers, read by {@link Var#get} */
  public <V> Comp<V, R> yield(Supplier<? extends V> value) {
    return goOn(got -> Effects.<V, R>pure(value.get()));
  }

  /** the block goes on with a computation made of one answer */
  public <A, V> Comp<V, R> then(Var<A> a, Function<? super A, ? extends Comp<V, ? super R>> made) {
    int ia = place(a);
    return goOn(got -> made.apply(Handlers.trusted(got[ia])));
  }

  /** the block goes on with a computation made of two answers */
  public <A, B, V> Comp<V, R> then(
      Var<A> a, Var<B> b, BiFunction<? super A, ? super B, ? extends Comp<V, ? super R>> made) {
    int ia = place(a), ib = place(b);
    return goOn(got -> made.apply(Handlers.trusted(got[ia]), Handlers.trusted(got[ib])));
  }

  /** the block goes on with a computation made of any of its answers, read by {@link Var#get} */
  public <V> Comp<V, R> then(Supplier<? extends Comp<V, ? super R>> made) {
    return goOn(got -> made.get());
  }

  // -- the block as a program

  private <T> Var<T> member(Member m) {
    if (next != null) throw new IllegalStateException("a question asked after the block went on");
    members.add(m);
    return new Var<>(this, members.size() - 1);
  }

  private int place(Var<?> v) {
    if (v.block != this) throw new IllegalArgumentException("an answer of another block: take its value there");
    return v.index;
  }

  private <V> Comp<V, R> goOn(Function<Object[], ? extends Comp<?, ?>> made) {
    if (next != null) throw new IllegalStateException("the block went on already");
    next = made;
    return Handlers.trusted(going);
  }

  Object answer(int index) {
    if (answers == null)
      throw new IllegalStateException("an answer read outside its block: take it into a local where the block reads it");
    if (answers[index] == NOT_YET)
      throw new IllegalStateException("an answer read by a question asked before it is known: say it, ask(a, x -> ..)");
    return answers[index];
  }

  private static final Object NOT_YET = new Object();

  /** a function of the block given the answers of a path: they are read while it runs, and not after */
  private okay.core.Comp<?, ?> reading(Object[] got, Function<Object[], ? extends Comp<?, ?>> made) {
    Object[] before = answers;
    answers = got;
    try {
      return made.apply(got).eff;
    } finally {
      answers = before;
    }
  }

  /** the block's program: its questions in rounds — a question in the round after the last of those it needs */
  okay.core.Comp<?, ?> program(Comp<?, ?> returned) {
    Function<Object[], ? extends Comp<?, ?>> then = returned == going ? next : got -> returned;
    if (then == null) throw new IllegalStateException("the block did not go on: return t.then(..) or t.yield(..)");
    int n = members.size();
    int[] round = new int[n];
    int rounds = 0;
    for (int i = 0; i < n; i++) {
      Member m = members.get(i);
      int r = 0;
      if (m.made != null && m.needs == null) for (int j = 0; j < i; j++) r = Math.max(r, round[j] + 1);
      if (m.needs != null) for (int j : m.needs) r = Math.max(r, round[j] + 1);
      round[i] = r;
      rounds = Math.max(rounds, r + 1);
    }
    int[][] byRound = new int[rounds][];
    for (int r = 0; r < rounds; r++) {
      int at = r;
      byRound[r] = java.util.stream.IntStream.range(0, n).filter(i -> round[i] == at).toArray();
    }
    Object[] none = new Object[n];
    Arrays.fill(none, NOT_YET);
    return round(byRound, 0, none, then);
  }

  /** a round: its questions made, those that are operations of one handler put in one set, each where the first of
   * them stands */
  private okay.core.Comp<?, ?> round(
      int[][] rounds, int r, Object[] got, Function<Object[], ? extends Comp<?, ?>> then) {
    if (r == rounds.length) return reading(got, then);
    int[] in = rounds[r];
    okay.core.Comp<?, ?>[] effs = new okay.core.Comp<?, ?>[in.length];
    Object[] ops = new Object[in.length];
    for (int i = 0; i < in.length; i++) {
      Member m = members.get(in[i]);
      effs[i] = m.made == null ? m.eff : reading(got, m.made);
      ops[i] = DOOR.operation(effs[i]);
    }
    ArrayList<int[]> steps = new ArrayList<>();
    boolean[] placed = new boolean[in.length];
    for (int i = 0; i < in.length; i++) {
      if (placed[i]) continue;
      int count = 0;
      int[] same = new int[in.length - i];
      for (int j = i; j < in.length; j++) {
        if (j == i || (!placed[j] && ops[i] != null && ops[j] != null && DOOR.skip(effs[j]) == DOOR.skip(effs[i]))) {
          same[count++] = j;
          placed[j] = true;
        }
      }
      steps.add(Arrays.copyOf(same, count));
    }
    return step(rounds, r, in, effs, ops, steps, 0, got, then);
  }

  @SuppressWarnings({"unchecked", "rawtypes"}) // the machine's programs, untyped: the block was typed where written
  private okay.core.Comp<?, ?> step(int[][] rounds, int r, int[] in, okay.core.Comp<?, ?>[] effs, Object[] ops,
      ArrayList<int[]> steps, int s, Object[] got, Function<Object[], ? extends Comp<?, ?>> then) {
    if (s == steps.size()) return round(rounds, r + 1, got, then);
    int[] which = steps.get(s);
    if (which.length == 1) {
      okay.core.Comp one = effs[which[0]];
      return one.flatMap(x -> {
        Object[] more = got.clone();
        more[in[which[0]]] = x;
        return (okay.core.Comp) step(rounds, r, in, effs, ops, steps, s + 1, more, then);
      });
    }
    Object[] set = new Object[which.length];
    for (int j = 0; j < set.length; j++) set[j] = ops[which[j]];
    okay.core.Comp all = DOOR.askAll(set, DOOR.skip(effs[which[0]]));
    return all.flatMap(xs -> {
      Object[] more = got.clone();
      List<Object> list = (List<Object>) xs;
      for (int j = 0; j < which.length; j++) more[in[which[j]]] = list.get(j);
      return (okay.core.Comp) step(rounds, r, in, effs, ops, steps, s + 1, more, then);
    });
  }
}
