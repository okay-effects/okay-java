package okay.std;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import okay.core.Eff;
import okay.core.Prompt;

/** a choice among values, MULTI-SHOT: the rest run for each. Two handlers of it: every value the body comes to,
 * {@link #all}, or the first, {@link #first} — the same program a search either way */
public interface Choose {
  <X> Eff<X> among(List<X> xs);

  default <X> Eff<X> fail() {
    return among(List.of());
  }

  static <A> Eff<List<A>> all(Function<Choose, Eff<A>> body) {
    return Eff.handle((Prompt<List<A>> p) -> body.apply(new Choose() {
      public <X> Eff<X> among(List<X> xs) {
        return p.shift0(k -> {
          Eff<List<A>> acc = Eff.pure(List.of());
          for (X x : xs) acc = acc.flatMap(l -> k.apply(x).map(r -> concat(l, r)));
          return acc;
        });
      }
    }), a -> Eff.pure(List.of(a)));
  }

  /** the first value the body comes to, its choices tried in order, the rest not tried */
  static <A> Eff<Optional<A>> first(Function<Choose, Eff<A>> body) {
    return Eff.handle((Prompt<Optional<A>> p) -> body.apply(new Choose() {
      public <X> Eff<X> among(List<X> xs) {
        return p.shift0(k -> tryEach(k, xs, 0));
      }
    }), a -> Eff.pure(Optional.of(a)));
  }

  private static <X, A> Eff<Optional<A>> tryEach(Function<X, Eff<Optional<A>>> k, List<X> xs, int i) {
    return i == xs.size() ? Eff.pure(Optional.empty())
        : k.apply(xs.get(i)).flatMap(r -> r.isPresent() ? Eff.pure(r) : tryEach(k, xs, i + 1));
  }

  private static <A> List<A> concat(List<A> a, List<A> b) {
    if (a.isEmpty()) return b;
    List<A> r = new ArrayList<>(a.size() + b.size());
    r.addAll(a);
    r.addAll(b);
    return r;
  }
}
