package okay.rows;

import java.util.ArrayList;
import okay.effect.Eff;
import okay.effect.Op;

/**
 * A PLAIN LOOP OF QUESTIONS IN A BLOCK: the questions asked through it, all answered by one type, are questions of
 * its block ({@link Together}), and their answers go under one name, a list in their order.
 *
 * <pre>{@code
 * together(t -> {
 *   Var<List<Long>> prices = t.gather(g -> {
 *     for (String item : cart) g.perform(new Prices.Price(item));
 *   });
 *   ..
 * })
 * }</pre>
 *
 * As questions of the block they are asked in its rounds, with the others: operations of one handler at once, as
 * a set. {@code Effects.gather(g -> ..)} is the block that is nothing but one such loop: the list is its value.
 */
public final class Gather<X, E, R> {
  private final Block<R> block;
  final ArrayList<Var<X>> asked = new ArrayList<>();

  Gather(Block<R> block) {
    this.block = block;
  }

  /** an operation of the block's first effect, asked; an operation of another effect or answered by another type
   * is refused by the compiler */
  public <O extends Op<E, X, ? super O>> Var<X> perform(O op) {
    Var<X> v = block.asked(op);
    asked.add(v);
    return v;
  }

  /** any computation of the block's row, asked: one made of operations, another effect's with {@code up} */
  public Var<X> ask(Comp<X, ? super R> question) {
    Var<X> v = block.ask(question);
    asked.add(v);
    return v;
  }
}
