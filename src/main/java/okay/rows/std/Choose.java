package okay.rows.std;

import static okay.rows.Effects.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import okay.rows.Comp;
import okay.rows.Eff;
import okay.rows.Handler;
import okay.rows.K;
import okay.rows.Op;

/** CHOOSE: the rest of the program is resumed once for every value — a continuation taken many times. With no
 * value it is left: unwound. */
public sealed interface Choose {
  record Among<X>(List<X> values) implements Choose, Op<Choose, X, Among<X>> {}

  static <X> Comp<X, Eff<Choose, ?>> among(List<X> values) { return perform(new Among<>(values)); }

  /** no value: this path ends */
  static <X> Comp<X, Eff<Choose, ?>> none() { return perform(new Among<>(List.of())); }

  /** every value the program comes to */
  static <A, R> Comp<List<A>, R> all(Comp<A, ? super Eff<Choose, R>> p) {
    return handle(Choose.class, new Handler<Choose, A, List<A>, R>() {
      public Comp<List<A>, R> ret(A a) { return pure(List.of(a)); }
      public Comp<List<A>, R> on(Choose op, K<List<A>, R> k) {
        return switch (op) {
          case Among<?> among when among.values().isEmpty() -> k.unwind().map(left -> List.of());
          case Among<?> among -> every(among, 0, List.of(), k);
        };
      }
    }, p);
  }

  private static <X, A, R> Comp<List<A>, R> every(Among<X> among, int i, List<A> found, K<List<A>, R> k) {
    if (i == among.values().size()) return pure(found);
    return k.resume(among, among.values().get(i)).flatMap(more -> {
      List<A> all = new ArrayList<>(found);
      all.addAll(more);
      return every(among, i + 1, all, k);
    });
  }

  /** the first value the program comes to, the other paths not taken: they were not begun, and hold nothing */
  static <A, R> Comp<Optional<A>, R> first(Comp<A, ? super Eff<Choose, R>> p) {
    return handle(Choose.class, new Handler<Choose, A, Optional<A>, R>() {
      public Comp<Optional<A>, R> ret(A a) { return pure(Optional.of(a)); }
      public Comp<Optional<A>, R> on(Choose op, K<Optional<A>, R> k) {
        return switch (op) {
          case Among<?> among when among.values().isEmpty() -> k.unwind().map(left -> Optional.empty());
          case Among<?> among -> from(among, 0, k);
        };
      }
    }, p);
  }

  private static <X, A, R> Comp<Optional<A>, R> from(Among<X> among, int i, K<Optional<A>, R> k) {
    if (i == among.values().size()) return pure(Optional.empty());
    return k.resume(among, among.values().get(i))
        .flatMap(found -> found.isPresent() ? pure(found) : from(among, i + 1, k));
  }
}
