package okay.std;

import java.util.List;
import okay.core.Comp;
import okay.core.Prompt;

/** a log to tell: each {@code tell} the rest's log with {@code w} in front */
@FunctionalInterface
public interface Writer<L, W> {
  Comp<Void, L> tell(W w);

  /** this capability at a level further in */
  @SuppressWarnings("unchecked")
  default <N extends L> Writer<N, W> at() {
    return (Writer<N, W>) (Writer<?, W>) this;
  }

  record Log<W, A>(List<W> log, A value) {}

  /** the body of a writer: at a fresh level, given the capability there */
  interface Body<L, W, A> {
    <M extends L> Comp<A, M> in(Writer<M, W> w);
  }

  static <L, W, A> Comp<Log<W, A>, L> writer(Body<L, W, A> body) {
    return Comp.handle(new Comp.Body<L, A, Built<W, A>>() {
      public <M extends L> Comp<A, M> in(Prompt<L, M, Built<W, A>> p) {
        return body.in(w -> p.shift0(k -> k.apply(null).map(r -> new Built<>(new Rope.Cons<>(w, r.log()), r.value()))));
      }
    }, a -> Comp.pure(new Built<>(Rope.<W>empty(), a))).map(b -> new Log<>(b.log().toList(), b.value()));
  }

  /** the log while it is built: each tell in front, in constant time */
  record Built<W, A>(Rope<W> log, A value) {}
}
