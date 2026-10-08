package okay.std;

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
    return Eff.handle((Prompt<Rope<A>> p) -> body.apply(new Choose() {
      public <X> Eff<X> among(List<X> xs) {
        return p.shift0(k -> {
          Eff<Rope<A>> acc = Eff.pure(Rope.empty());
          for (X x : xs) acc = acc.flatMap(l -> k.apply(x).map(r -> Rope.cat(l, r)));
          return acc;
        });
      }
    }), a -> Eff.pure(new Rope.Leaf<>(a))).map(Rope::toList);
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
}
