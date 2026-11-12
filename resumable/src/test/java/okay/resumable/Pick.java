package okay.resumable;

import static okay.rows.Effects.handle;
import static okay.rows.Effects.pure;

import java.util.ArrayList;
import java.util.List;
import okay.rows.Comp;
import okay.rows.Eff;
import okay.rows.Handler;
import okay.rows.K;
import okay.rows.Op;

/** A CHOICE in direct style: the rest of the program resumed once for every value */
public abstract sealed class Pick extends Suspend {
  public static final class Among extends Pick implements Op<Pick, Integer, Among> {
    final List<Integer> values;

    public Among(List<Integer> values) { this.values = values; }

    @Override
    public String toString() { return "Among" + values; }
  }

  public static int among(Integer... values) throws Pick {
    return Direct.perform(new Among(List.of(values)));
  }

  /** every value the program comes to; none ends a path, unwound */
  public static <A, R> Comp<List<A>, R> all(Comp<A, ? super Eff<Pick, R>> p) {
    return handle(Pick.class, new Handler<Pick, A, List<A>, R>() {
      public Comp<List<A>, R> ret(A a) { return pure(List.of(a)); }
      public Comp<List<A>, R> on(Pick op, K<List<A>, R> k) {
        return switch (op) {
          case Among among when among.values.isEmpty() -> k.unwind().map(left -> List.of());
          case Among among -> every(among, 0, List.of(), k);
        };
      }
    }, p);
  }

  private static <A, R> Comp<List<A>, R> every(Among among, int i, List<A> found, K<List<A>, R> k) {
    if (i == among.values.size()) return pure(found);
    return k.resume(among, among.values.get(i)).flatMap(more -> {
      List<A> all = new ArrayList<>(found);
      all.addAll(more);
      return every(among, i + 1, all, k);
    });
  }
}
