package okay.std;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import okay.core.Comp;
import okay.core.Prompt;

/** a choice among values, MULTI-SHOT: the rest run for each. Two handlers of it: every value the body comes to,
 * {@link #all}, or the first, {@link #first} — the same program a search either way */
public interface Choose<L> {
  <X> Comp<X, L> among(List<X> xs);

  default <X> Comp<X, L> fail() {
    return among(List.of());
  }

  /** this capability at a level further in */
  @SuppressWarnings("unchecked")
  default <N extends L> Choose<N> at() {
    return (Choose<N>) (Choose<?>) this;
  }

  /** the body of a handler of choices: at a fresh level, given the capability there */
  interface Body<L, A> {
    <M extends L> Comp<A, M> in(Choose<M> ch);
  }

  static <L, A> Comp<List<A>, L> all(Body<L, A> body) {
    return Comp.handle(new Comp.Body<L, A, Rope<A>>() {
      public <M extends L> Comp<A, M> in(Prompt<L, M, Rope<A>> p) {
        return body.in(new Choose<M>() {
          public <X> Comp<X, M> among(List<X> xs) {
            return p.shift0(k -> {
              Comp<Rope<A>, L> acc = Comp.pure(Rope.empty());
              for (X x : xs) acc = acc.flatMap(l -> k.apply(x).map(r -> Rope.cat(l, r)));
              return acc;
            });
          }
        });
      }
    }, a -> Comp.pure(new Rope.Leaf<>(a))).map(Rope::toList);
  }

  /** the first value the body comes to, its choices tried in order, the rest not tried */
  static <L, A> Comp<Optional<A>, L> first(Body<L, A> body) {
    return Comp.handle(new Comp.Body<L, A, Optional<A>>() {
      public <M extends L> Comp<A, M> in(Prompt<L, M, Optional<A>> p) {
        return body.in(new Choose<M>() {
          public <X> Comp<X, M> among(List<X> xs) {
            return p.shift0(k -> tryEach(k, xs, 0));
          }
        });
      }
    }, a -> Comp.pure(Optional.of(a)));
  }

  private static <L, X, A> Comp<Optional<A>, L> tryEach(Function<X, Comp<Optional<A>, L>> k, List<X> xs, int i) {
    return i == xs.size() ? Comp.pure(Optional.empty())
        : k.apply(xs.get(i)).flatMap(r -> r.isPresent() ? Comp.pure(r) : tryEach(k, xs, i + 1));
  }
}
