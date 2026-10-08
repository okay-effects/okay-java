package okay.std;

import java.util.ArrayList;
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
        (Prompt<Log<W, A>> p) -> body.apply(w -> p.shift0(k -> k.apply(null).map(r -> new Log<>(prepend(w, r.log()), r.value())))),
        a -> Eff.pure(new Log<>(List.of(), a)));
  }

  private static <W> List<W> prepend(W w, List<W> l) {
    List<W> r = new ArrayList<>(l.size() + 1);
    r.add(w);
    r.addAll(l);
    return r;
  }
}
