package okay.std;

import java.util.function.Function;
import okay.core.Eff;
import okay.core.Prompt;

/** a failure of type {@code E}: {@code raise} drops k, the handler's recovery in its place — run outside the
 * handler, so a recovery that raises again raises to the handler further out */
@FunctionalInterface
public interface Throws<E> {
  <X> Eff<X> raise(E e);

  static <E, A> Eff<A> catching(Function<Throws<E>, Eff<A>> body,
                                Function<? super E, ? extends Eff<A>> recover) {
    return Eff.handle((Prompt<A> p) -> body.apply(new Throws<E>() {
      public <X> Eff<X> raise(E e) {
        return p.shift0(_ -> recover.apply(e));
      }
    }));
  }
}
