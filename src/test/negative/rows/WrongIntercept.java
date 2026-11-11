import static okay.rows.Effects.*;

import okay.rows.Comp;
import okay.rows.Eff;
import okay.rows.Handler;
import okay.rows.K;
import okay.rows.std.Reader;

/** An interceptor of an effect with type arguments: it is found by the class, and the class of a
 * {@code Reader<String>} is that of a {@code Reader<Integer>} too — it would be given the asks of both: in
 * {@code Reader.run(1, Reader.run("s", loud(up(Reader.<Integer>ask()))))} the integer asked would be a string. */
final class WrongIntercept {
  static <A, R extends Eff<Reader<String>, ?>> Comp<A, R> loud(Comp<A, ? super R> p) {
    return intercept(Reader.class, new Handler<Reader<String>, A, A, R>() { // refused: Reader.class is of any reader
      public Comp<A, R> ret(A a) { return pure(a); }
      public Comp<A, R> on(Reader<String> op, K<A, R> k) {
        return switch (op) {
          case Reader.Ask<String> ask -> Reader.<String>ask().flatMap(s -> k.resume(ask, s + "!"));
        };
      }
    }, p);
  }
}
