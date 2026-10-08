package okay.std;

import java.util.List;
import java.util.function.Function;
import okay.core.Eff;
import okay.core.Prompt;

/** a log to tell: each {@code tell} the rest's log with {@code w} in front */
@FunctionalInterface
public interface Writer<W> {
  Eff<Void> tell(W w);

  record Log<W, A>(List<W> log, A value) {}

  static <W, A> Eff<Log<W, A>> writer(Function<Writer<W>, Eff<A>> body) {
    return Eff.handle(
        (Prompt<Built<W, A>> p) -> body.apply(w -> p.shift0(k -> k.apply(null).map(r -> new Built<>(new Rope.Cat<>(new Rope.Leaf<>(w), r.log()), r.value())))),
        a -> Eff.pure(new Built<>(Rope.<W>empty(), a)))
        .map(b -> new Log<>(b.log().toList(), b.value()));
  }

  /** the log while it is built: each tell in front, in constant time */
  record Built<W, A>(Rope<W> log, A value) {}
}
