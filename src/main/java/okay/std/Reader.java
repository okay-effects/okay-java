package okay.std;

import java.util.function.Function;
import okay.core.Eff;

/** an environment to ask. Its one operation is answered in place, {@code shift0 k. k(env)} is {@code env}: the
 * handler is the capability itself, no prompt, no capture — okay-core's {@code Answering}, here for nothing */
@FunctionalInterface
public interface Reader<E> {
  Eff<E> ask();

  static <E, A> Eff<A> reader(E env, Function<Reader<E>, Eff<A>> body) {
    Eff<E> value = Eff.pure(env);
    return Eff.delay(() -> body.apply(() -> value));
  }
}
