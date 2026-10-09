package okay.std;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import okay.core.Eff;
import okay.core.Prompt;

/** a choice among values, MULTI-SHOT: the rest run for each. Two handlers of it: every value the body comes to,
 * {@link #all}, or the first, {@link #first} — the same program a search either way */
public interface Choose<L> {
  <X> Eff<L, X> among(List<X> xs);

  default <X> Eff<L, X> fail() {
    return among(List.of());
  }

  /** this capability at a level further in */
  @SuppressWarnings("unchecked")
  default <N extends L> Choose<N> at() {
    return (Choose<N>) (Choose<?>) this;
  }

  /** the body of a handler of choices: at a fresh level, given the capability there */
  interface Body<L, A> {
    <M extends L> Eff<M, A> in(Choose<M> ch);
  }

  static <L, A> Eff<L, List<A>> all(Body<L, A> body) {
    return Eff.handle(new Eff.Body<L, A, Rope<A>>() {
      public <M extends L> Eff<M, A> in(Prompt<L, M, Rope<A>> p) {
        return body.in(new Choose<M>() {
          public <X> Eff<M, X> among(List<X> xs) {
            return p.shift0(k -> {
              Eff<L, Rope<A>> acc = Eff.pure(Rope.empty());
              for (X x : xs) acc = acc.flatMap(l -> k.apply(x).map(r -> Rope.cat(l, r)));
              return acc;
            });
          }
        });
      }
    }, a -> Eff.pure(new Rope.Leaf<>(a))).map(Rope::toList);
  }

  /** the first value the body comes to, its choices tried in order, the rest not tried */
  static <L, A> Eff<L, Optional<A>> first(Body<L, A> body) {
    return Eff.handle(new Eff.Body<L, A, Optional<A>>() {
      public <M extends L> Eff<M, A> in(Prompt<L, M, Optional<A>> p) {
        return body.in(new Choose<M>() {
          public <X> Eff<M, X> among(List<X> xs) {
            return p.shift0(k -> tryEach(k, xs, 0));
          }
        });
      }
    }, a -> Eff.pure(Optional.of(a)));
  }

  private static <L, X, A> Eff<L, Optional<A>> tryEach(Function<X, Eff<L, Optional<A>>> k, List<X> xs, int i) {
    return i == xs.size() ? Eff.pure(Optional.empty())
        : k.apply(xs.get(i)).flatMap(r -> r.isPresent() ? Eff.pure(r) : tryEach(k, xs, i + 1));
  }
}
