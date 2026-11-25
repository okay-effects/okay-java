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
 *
 * <p>The block's own loops and branches go BY THE ANSWERS, and stay in the rounds: {@code each} asks, for every
 * element of an answer that is a list, the questions a function writes for it — the questions of all the elements
 * in the same rounds, so the same handler answers them at once, however deep the lists are nested; {@code when}
 * asks the questions of one branch or the other, chosen by an answer.
 */
public final class Together<E, R extends Eff<E, ?>> {
  /** a function of three */
  @FunctionalInterface
  public interface F3<A, B, C, T> {
    T apply(A a, B b, C c);
  }

  /** A NAME OF THE BLOCK, by what gives it its value, once the answers it {@code needs} are known:
   * a question known where it is written ({@code eff}); a question made of answers ({@code made}); a value made of
   * answers, no question ({@code value}); or more of the block, written when the answers are known, and what this
   * name then is ({@code grow}: an {@code each}, a {@code when}) */
  private record Member(int[] needs, okay.core.Comp<?, ?> eff, Function<Object[], ? extends Comp<?, ?>> made,
      Function<Object[], Object> value, Function<Object[], Member> grow) {
    static final int[] NOTHING = {};

    static Member asked(okay.core.Comp<?, ?> eff) {
      return new Member(NOTHING, eff, null, null, null);
    }

    static Member made(int[] needs, Function<Object[], ? extends Comp<?, ?>> made) {
      return new Member(needs, null, made, null, null);
    }

    static Member value(int[] needs, Function<Object[], Object> value) {
      return new Member(needs, null, null, value, null);
    }

    static Member grow(int[] needs, Function<Object[], Member> grow) {
      return new Member(needs, null, null, null, grow);
    }
  }

  /** what is being written: the block itself, or, while it goes, what an {@code each} or a {@code when} adds —
   * its first name's place {@code base} */
  private ArrayList<Member> writing = new ArrayList<>();
  private int base;
  private boolean growing;
  private Function<Object[], ? extends Comp<?, ?>> next;
  private final Comp<?, ?> going = new Comp<>(null);
  /** the answers, while a function of the block reads them: of the path that goes on */
  private Object[] answers;

  Together() {}

  // -- questions

  /** an operation of the block's first effect, asked: its answer's name. The same as
   * {@code t.ask(Effects.perform(op))}; an operation of another effect is refused by the compiler */
  public <T, O extends Op<E, T, ? super O>> Var<T> perform(O op) {
    return member(Member.asked(DOOR.ask(op, 0)));
  }

  /** a question of the block: any computation of its row; its answer's name */
  public <T> Var<T> ask(Comp<T, ? super R> question) {
    return member(Member.asked(question.eff));
  }

  /** a question made of one answer: asked when it is known */
  public <A, T> Var<T> ask(Var<A> a, Function<? super A, ? extends Comp<T, ? super R>> question) {
    int ia = place(a);
    return member(Member.made(new int[] {ia}, got -> question.apply(Handlers.trusted(got[ia]))));
  }

  /** a question made of two answers */
  public <A, B, T> Var<T> ask(
      Var<A> a, Var<B> b, BiFunction<? super A, ? super B, ? extends Comp<T, ? super R>> question) {
    int ia = place(a), ib = place(b);
    return member(Member.made(new int[] {ia, ib},
        got -> question.apply(Handlers.trusted(got[ia]), Handlers.trusted(got[ib]))));
  }

  /** a question made of three answers */
  public <A, B, C, T> Var<T> ask(Var<A> a, Var<B> b, Var<C> c,
      F3<? super A, ? super B, ? super C, ? extends Comp<T, ? super R>> question) {
    int ia = place(a), ib = place(b), ic = place(c);
    return member(Member.made(new int[] {ia, ib, ic}, got -> question.apply(
        Handlers.trusted(got[ia]), Handlers.trusted(got[ib]), Handlers.trusted(got[ic]))));
  }

  /** a question made of any of the answers above it, read by {@link Var#get}: asked after every question written
   * before it — in a body of {@code each} or {@code when}, before it there. One that says its answers
   * ({@code ask(a, b, ..)}) is asked as soon as those are known */
  public <T> Var<T> ask(Supplier<? extends Comp<T, ? super R>> question) {
    return member(Member.made(above(), got -> question.get()));
  }


  /** A PLAIN LOOP OF QUESTIONS: those asked through {@code g} in {@code loop}, all answered by one type, are
   * questions of this block; their answers, in their order, under one name —
   * {@code t.gather(g -> { for (String item : cart) g.perform(new Price(item)); })} */
  public <T> Var<List<T>> gather(java.util.function.Consumer<? super Gather<T, E, R>> loop) {
    Gather<T, E, R> g = new Gather<>(this);
    loop.accept(g);
    return list(g.asked);
  }

  /** the answers of several questions of this block, in that order, under one name */
  public <T> Var<List<T>> list(List<Var<T>> asked) {
    int[] needs = new int[asked.size()];
    for (int i = 0; i < needs.length; i++) needs[i] = place(asked.get(i));
    return member(listOf(needs));
  }

  private static Member listOf(int[] needs) {
    return Member.value(needs, got -> {
      Object[] values = new Object[needs.length];
      for (int i = 0; i < needs.length; i++) values[i] = got[needs[i]];
      return Collections.unmodifiableList(Arrays.asList(values));
    });
  }

  /** a name for a value: no question */
  public <T> Var<T> value(T value) {
    return member(Member.value(Member.NOTHING, got -> value));
  }

  /** a name for a value made of an answer, no question: {@link Var#map} */
  <A, T> Var<T> let(Var<A> a, Function<? super A, ? extends T> value) {
    int ia = place(a);
    return member(Member.value(new int[] {ia}, got -> value.apply(Handlers.trusted(got[ia]))));
  }

  /** a name for a value made of two answers: {@link Var#with} */
  <A, B, T> Var<T> let(Var<A> a, Var<B> b, BiFunction<? super A, ? super B, ? extends T> value) {
    int ia = place(a), ib = place(b);
    return member(Member.value(new int[] {ia, ib},
        got -> value.apply(Handlers.trusted(got[ia]), Handlers.trusted(got[ib]))));
  }

  /** a name for a value made of three answers: {@link Var#with} */
  <A, B, C, T> Var<T> let(Var<A> a, Var<B> b, Var<C> c,
      F3<? super A, ? super B, ? super C, ? extends T> value) {
    int ia = place(a), ib = place(b), ic = place(c);
    return member(Member.value(new int[] {ia, ib, ic}, got -> value.apply(
        Handlers.trusted(got[ia]), Handlers.trusted(got[ib]), Handlers.trusted(got[ic]))));
  }

  /** the questions that need an answer, written through this block when it is known, and the name they give:
   * {@link Var#then} */
  <A, T> Var<T> follow(Var<A> a, Function<? super A, Var<T>> next) {
    int ia = place(a);
    return member(Member.grow(new int[] {ia}, got -> {
      int given = place(next.apply(Handlers.trusted(got[ia])));
      return Member.value(new int[] {given}, answers -> answers[given]);
    }));
  }

  // -- the block's loops and branches, by the answers

  /** FOR EVERY ELEMENT of an answer that is a list, the questions {@code body} writes for it — through this same
   * block — and the name it returns; their values, in the elements' order, under one name. Written when the list is
   * known. The questions of all the elements are questions of the block, asked in its rounds: those of one handler
   * at once, for all the elements together — and so again for an {@code each} inside the body. */
  public <X, Y> Var<List<Y>> each(Var<? extends Iterable<? extends X>> xs, Function<? super X, Var<Y>> body) {
    int ix = place(xs);
    return member(Member.grow(new int[] {ix}, got -> {
      ArrayList<Integer> made = new ArrayList<>();
      Iterable<? extends X> elements = Handlers.trusted(got[ix]);
      for (X x : elements) made.add(place(body.apply(x)));
      return listOf(made.stream().mapToInt(Integer::intValue).toArray());
    }));
  }

  /** THE QUESTIONS OF ONE BRANCH OR THE OTHER, chosen by an answer: written when it is known, through this same
   * block; the name the branch returns. The branch not taken asks nothing */
  public <T> Var<T> when(Var<Boolean> condition, Supplier<Var<T>> then, Supplier<Var<T>> otherwise) {
    int ic = place(condition);
    return member(Member.grow(new int[] {ic}, got -> {
      int chosen = place(Boolean.TRUE.equals(got[ic]) ? then.get() : otherwise.get());
      return Member.value(new int[] {chosen}, answers -> answers[chosen]);
    }));
  }

  /** A LOOP BY THE ANSWERS: while {@code more} says so of the state, {@code next} writes — through this same block
   * — the questions that give the next state, and names it; the name of the last state. Each turn is written when
   * the state before it is known: a read page after page, a walk up a chain. The turns of one loop are asked one
   * after another, as each needs the one before; with the block's other questions, in its rounds. The block keeps
   * every turn's names: for tens or hundreds of turns, not for a loop without end ({@code Effects.fold}). */
  public <S> Var<S> loop(Var<S> start, java.util.function.Predicate<? super S> more, Function<? super S, Var<S>> next) {
    return member(turn(place(start), more, next));
  }

  private <S> Member turn(int state, java.util.function.Predicate<? super S> more, Function<? super S, Var<S>> next) {
    return Member.grow(new int[] {state}, got -> {
      S s = Handlers.trusted(got[state]);
      return more.test(s) ? turn(place(next.apply(s)), more, next) : Member.value(new int[] {state}, a -> a[state]);
    });
  }

  // -- the last word of the block

  /** the block has no value: its questions are asked for what they do */
  public Comp<Void, R> done() {
    return goOn(got -> Effects.<Void, R>pure(null));
  }

  /** the block's value: an answer as it is */
  public <V> Comp<V, R> yield(Var<V> a) {
    int ia = place(a);
    return goOn(got -> Effects.<V, R>pure(Handlers.trusted(got[ia])));
  }

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
    if (next != null && !growing) throw new IllegalStateException("a question asked after the block went on");
    writing.add(m);
    return new Var<>(this, base + writing.size() - 1);
  }

  /** the names written so far, here: of the block, or of the body of an each or a when that is being written */
  private int[] above() {
    int[] needs = new int[writing.size()];
    for (int i = 0; i < needs.length; i++) needs[i] = base + i;
    return needs;
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
      throw new IllegalStateException(
          "an answer read outside its block: take it into a local where the block reads it");
    if (index >= answers.length || answers[index] == NOT_YET)
      throw new IllegalStateException("an answer read by a question asked before it is known: say it, ask(a, x -> ..)");
    return answers[index];
  }

  private static final Object NOT_YET = new Object();

  /** a function of the block given the answers of a path: they are read while it runs, and not after */
  private <T> T reading(Object[] got, Function<Object[], T> f) {
    Object[] before = answers;
    answers = got;
    try {
      return f.apply(got);
    } finally {
      answers = before;
    }
  }

  /** THE BLOCK ON ONE PATH, while it goes: its names — more of them as an each or a when is written —, the
   * questions made, the answers known. A name WAITS (no question made, no answer), is READY (its question made) or
   * KNOWN (its answer there). A value: a step makes another, so a path resumed twice has its own. The names are
   * shared by the copies, and replaced, never changed, when the block grows */
  private static final class Going {
    Member[] names;
    okay.core.Comp<?, ?>[] effs;
    Object[] got;

    Going(List<Member> written) {
      int n = written.size();
      names = written.toArray(new Member[n]);
      effs = new okay.core.Comp<?, ?>[n];
      got = new Object[n];
      Arrays.fill(got, NOT_YET);
    }

    private Going(Going of) {
      names = of.names;
      effs = of.effs.clone();
      got = of.got.clone();
    }

    Going copy() {
      return new Going(this);
    }

    /** more names, and the one at {@code at} become {@code become}: in arrays of this copy's own */
    void grow(List<Member> more, int at, Member become) {
      int n = names.length;
      names = Arrays.copyOf(names, n + more.size());
      effs = Arrays.copyOf(effs, names.length);
      got = Arrays.copyOf(got, names.length);
      for (int i = 0; i < more.size(); i++) {
        names[n + i] = more.get(i);
        got[n + i] = NOT_YET;
      }
      names[at] = become;
    }

    boolean known(int[] needs) {
      for (int i : needs) if (got[i] == NOT_YET) return false;
      return true;
    }
  }

  /** the block's program: its questions asked in rounds — each round every question whose answers are known */
  okay.core.Comp<?, ?> program(Comp<?, ?> returned) {
    Function<Object[], ? extends Comp<?, ?>> then = returned == going ? next : got -> returned;
    if (then == null) throw new IllegalStateException("the block did not go on: return t.then(..) or t.yield(..)");
    return round(new Going(writing), then);
  }

  /** what can be known without asking is made known — the values, the questions made of answers, the bodies of
   * each and when, which add names */
  private void settle(Going g) {
    boolean more = true;
    while (more) {
      more = false;
      for (int i = 0; i < g.names.length; i++) {
        if (g.got[i] != NOT_YET || g.effs[i] != null) continue;
        Member m = g.names[i];
        if (m.eff != null) {
          g.effs[i] = m.eff;
        } else if (!g.known(m.needs)) {
          continue;
        } else if (m.made != null) {
          g.effs[i] = reading(g.got, m.made).eff;
        } else if (m.value != null) {
          g.got[i] = reading(g.got, m.value);
          more = true;
        } else {
          // more of the block, written now through the block's own words: its names after the ones there are
          ArrayList<Member> outer = writing;
          int outerBase = base;
          boolean outerGrowing = growing;
          writing = new ArrayList<>();
          base = g.names.length;
          growing = true;
          try {
            Member become = reading(g.got, m.grow);
            g.grow(writing, i, become);
          } finally {
            writing = outer;
            base = outerBase;
            growing = outerGrowing;
          }
          more = true;
        }
      }
    }
  }

  /** a round: every question that can be asked now — those that are operations of one handler put in one set, each
   * set where the first of them stands — then the next round; no question left, the block goes on. {@code g} is
   * this path's own, not yet shared: it is settled in place */
  @SuppressWarnings({"unchecked", "rawtypes"}) // the machine's programs, untyped: the block was typed where written
  private okay.core.Comp<?, ?> round(Going g, Function<Object[], ? extends Comp<?, ?>> then) {
    settle(g);
    // the questions ready; the usual round is of operations of one handler, or of one question: found in one pass
    int count = 0, first = -1, skip = -1;
    boolean oneSet = true;
    for (int i = 0; i < g.got.length; i++) {
      if (g.got[i] != NOT_YET || g.effs[i] == null) continue;
      if (count++ == 0) first = i;
      if (!oneSet) continue;
      if (DOOR.operation(g.effs[i]) == null) oneSet = false;
      else if (skip < 0) skip = DOOR.skip(g.effs[i]);
      else if (skip != DOOR.skip(g.effs[i])) oneSet = false;
    }
    if (count == 0) {
      for (int i = 0; i < g.got.length; i++)
        if (g.got[i] == NOT_YET) throw new IllegalStateException("a name of the block that nothing can give");
      return reading(g.got, got -> then.apply(got).eff);
    }
    if (count == 1) {
      int at = first;
      okay.core.Comp one = g.effs[at];
      return one.flatMap(x -> {
        Going more = g.copy();
        more.got[at] = x;
        return (okay.core.Comp) round(more, then);
      });
    }
    int[] in = new int[count];
    for (int i = first, j = 0; j < count; i++) if (g.got[i] == NOT_YET && g.effs[i] != null) in[j++] = i;
    if (oneSet) {
      Object[] set = new Object[count];
      for (int j = 0; j < count; j++) set[j] = DOOR.operation(g.effs[in[j]]);
      okay.core.Comp all = DOOR.askAll(set, skip);
      return all.flatMap(xs -> {
        Going more = g.copy();
        List<Object> list = (List<Object>) xs;
        for (int j = 0; j < in.length; j++) more.got[in[j]] = list.get(j);
        return (okay.core.Comp) round(more, then);
      });
    }
    Object[] ops = new Object[count];
    for (int i = 0; i < count; i++) ops[i] = DOOR.operation(g.effs[in[i]]);
    ArrayList<int[]> steps = new ArrayList<>();
    boolean[] placed = new boolean[count];
    for (int i = 0; i < count; i++) {
      if (placed[i]) continue;
      int same = 0;
      int[] which = new int[count - i];
      for (int j = i; j < count; j++) {
        if (j == i || (!placed[j] && ops[i] != null && ops[j] != null
            && DOOR.skip(g.effs[in[j]]) == DOOR.skip(g.effs[in[i]]))) {
          which[same++] = j;
          placed[j] = true;
        }
      }
      steps.add(Arrays.copyOf(which, same));
    }
    return step(g, in, ops, steps, 0, then);
  }

  @SuppressWarnings({"unchecked", "rawtypes"}) // the machine's programs, untyped: the block was typed where written
  private okay.core.Comp<?, ?> step(Going g, int[] in, Object[] ops, ArrayList<int[]> steps, int s,
      Function<Object[], ? extends Comp<?, ?>> then) {
    // the last step's copy is the next round's own
    if (s == steps.size()) return round(g, then);
    int[] which = steps.get(s);
    if (which.length == 1) {
      int at = in[which[0]];
      okay.core.Comp one = g.effs[at];
      return one.flatMap(x -> {
        Going more = g.copy();
        more.got[at] = x;
        return (okay.core.Comp) step(more, in, ops, steps, s + 1, then);
      });
    }
    Object[] set = new Object[which.length];
    for (int j = 0; j < set.length; j++) set[j] = ops[which[j]];
    okay.core.Comp all = DOOR.askAll(set, DOOR.skip(g.effs[in[which[0]]]));
    return all.flatMap(xs -> {
      Going more = g.copy();
      List<Object> list = (List<Object>) xs;
      for (int j = 0; j < which.length; j++) more.got[in[which[j]]] = list.get(j);
      return (okay.core.Comp) step(more, in, ops, steps, s + 1, then);
    });
  }
}
