package okay.std;

import java.util.List;
import okay.core.Eff;
import okay.core.Prompt;

/** a log to tell: each {@code tell} the rest's log with {@code w} in front */
@FunctionalInterface
public interface Writer<L, W> {
  Eff<L, Void> tell(W w);

  /** this capability at a level further in */
  @SuppressWarnings("unchecked")
  default <N extends L> Writer<N, W> at() {
    return (Writer<N, W>) (Writer<?, W>) this;
  }

  record Log<W, A>(List<W> log, A value) {}

  /** the body of a writer: at a fresh level, given the capability there */
  interface Body<L, W, A> {
    <M extends L> Eff<M, A> in(Writer<M, W> w);
  }

  static <L, W, A> Eff<L, Log<W, A>> writer(Body<L, W, A> body) {
    return Eff.handle(new Eff.Body<L, A, Built<W, A>>() {
      public <M extends L> Eff<M, A> in(Prompt<L, M, Built<W, A>> p) {
        return body.in(w -> p.shift0(k -> k.apply(null).map(r -> new Built<>(new Rope.Cat<>(new Rope.Leaf<>(w), r.log()), r.value()))));
      }
    }, a -> Eff.pure(new Built<>(Rope.<W>empty(), a))).map(b -> new Log<>(b.log().toList(), b.value()));
  }

  /** the log while it is built: each tell in front, in constant time */
  record Built<W, A>(Rope<W> log, A value) {}
}
