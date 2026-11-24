package okay.rows;

import static okay.rows.Comp.DOOR;

import java.util.ArrayList;
import java.util.function.Supplier;

/**
 * A BLOCK OF QUESTIONS AND WHAT FOLLOWS FROM THEIR ANSWERS — a comprehension, as Scala's {@code for}, in plain Java:
 *
 * <pre>{@code
 * Comp<String, Eff<Prices<?>, ?>> quote = together(t -> {
 *   Var<Long> price = t.ask(perform(new Prices.Price(item)));
 *   Var<Boolean> stock = t.ask(perform(new Prices.InStock(item)));
 *   return t.yield(() -> stock.get() ? item + " " + price.get() : item + " is out");
 * });
 * }</pre>
 *
 * The questions of a block are written one after another, each answer a name ({@link Var}), of its own type; the
 * block goes on once, where all the names can be read: {@code yield} for a value, {@code then} for a computation —
 * which may be another block. So code nests where a question DEPENDS on an answer, and nowhere else.
 *
 * <p>The questions of one block are independent of each other: nothing is answered while the block is written.
 * So those that are operations of one handler are asked AT ONCE, as a set ({@code Effects.performAll}): a handler
 * with a clause for sets answers them by one query, whatever their types. Any other computation asked in the
 * block is run in its turn.
 */
public final class Together<R> {
  private record Member(okay.core.Comp<?, ?> eff, Object op, int skip) {}

  private final ArrayList<Member> members = new ArrayList<>();
  private Supplier<? extends Comp<?, ?>> next;
  private final Comp<?, ?> going = new Comp<>(null);
  /** the answers, while the block goes on: of the path that goes on */
  private Object[] answers;

  Together() {}

  /** a question of the block: any computation of its row; its answer's name */
  public <T> Var<T> ask(Comp<T, ? super R> question) {
    if (next != null) throw new IllegalStateException("a question asked after the block went on");
    Object op = DOOR.operation(question.eff);
    members.add(new Member(question.eff, op, op == null ? 0 : DOOR.skip(question.eff)));
    return new Var<>(this, members.size() - 1);
  }

  /** the block goes on with a computation made of the answers: the last word of the block */
  public <A> Comp<A, R> then(Supplier<? extends Comp<A, ? super R>> made) {
    if (next != null) throw new IllegalStateException("the block went on already");
    next = made;
    return Handlers.trusted(going);
  }

  /** the block's value, made of the answers: the last word of the block */
  public <A> Comp<A, R> yield(Supplier<? extends A> value) {
    return then(() -> Effects.<A, R>pure(value.get()));
  }

  Object answer(int index) {
    if (answers == null)
      throw new IllegalStateException("an answer read outside its block's then or yield: take it into a local there");
    return answers[index];
  }

  /** the block as a program: its questions, grouped as they are asked, then what follows */
  okay.core.Comp<?, ?> program(Comp<?, ?> returned) {
    Supplier<? extends Comp<?, ?>> then = returned == going ? next : () -> returned;
    if (then == null) throw new IllegalStateException("the block did not go on: return t.then(..) or t.yield(..)");
    // the steps: an operation with the others to the same handler, as one set, where the first of them stands
    ArrayList<int[]> steps = new ArrayList<>();
    boolean[] placed = new boolean[members.size()];
    for (int i = 0; i < placed.length; i++) {
      if (placed[i]) continue;
      Member m = members.get(i);
      int count = 0;
      int[] same = new int[placed.length - i];
      for (int j = i; j < placed.length; j++) {
        Member other = members.get(j);
        if (j == i || (m.op != null && other.op != null && other.skip == m.skip && !placed[j])) {
          same[count++] = j;
          placed[j] = true;
        }
      }
      steps.add(java.util.Arrays.copyOf(same, count));
    }
    return step(steps, 0, new Object[members.size()], then);
  }

  @SuppressWarnings({"unchecked", "rawtypes"}) // the machine's programs, untyped: the block was typed where written
  private okay.core.Comp<?, ?> step(ArrayList<int[]> steps, int i, Object[] got, Supplier<? extends Comp<?, ?>> then) {
    if (i == steps.size()) {
      answers = got;
      try {
        return then.get().eff;
      } finally {
        answers = null;
      }
    }
    int[] which = steps.get(i);
    if (which.length == 1) {
      okay.core.Comp one = members.get(which[0]).eff;
      return one.flatMap(x -> {
        Object[] more = got.clone();
        more[which[0]] = x;
        return (okay.core.Comp) step(steps, i + 1, more, then);
      });
    }
    Object[] ops = new Object[which.length];
    for (int j = 0; j < ops.length; j++) ops[j] = members.get(which[j]).op;
    okay.core.Comp all = DOOR.askAll(ops, members.get(which[0]).skip);
    return all.flatMap(xs -> {
      Object[] more = got.clone();
      java.util.List<Object> list = (java.util.List<Object>) xs;
      for (int j = 0; j < which.length; j++) more[which[j]] = list.get(j);
      return (okay.core.Comp) step(steps, i + 1, more, then);
    });
  }
}
