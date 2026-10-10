package okay.java.kernel;

import okay.kernel.Direct;
import okay.kernel.Effects;
import okay.kernel.Handler;
import okay.kernel.Op;
import okay.kernel.Prog;
import okay.kernel.Program;
import okay.kernel.Thunk;

/**
 * THE FOUR METHODS {@link Effects} AND {@link Direct} SHARE: {@code pure}, {@code delay}, {@code perform},
 * {@code handle}. Their parameters erase to the same types, so a class implementing both interfaces has one method
 * for the two, and it can only be raw: it returns a {@code Prog} (Effects' answer, an {@code Object} to Direct). Its
 * unchecked conversion to Effects' {@code Prog<A>} is reported where the method is declared — on a class, the whole
 * class; here, on an interface of these four alone. Each passes on to {@link OnJava}'s typed method. And the API's
 * own overloads, {@code flatMap} and {@code map} of each, reported here too
 */
@SuppressWarnings({"rawtypes", "unchecked", "overloads"})
sealed interface Shared extends Effects, Direct permits OnJava {
  /** the one implementation: sealed so */
  private OnJava self() {
    return (OnJava) this;
  }

  @Override
  default Prog pure(Object a) {
    return OnJava.pureOf(a);
  }

  @Override
  default Prog delay(Thunk p) {
    return self().delayOf(p);
  }

  @Override
  default Prog perform(Op op) {
    return self().performOf(op);
  }

  @Override
  default Prog handle(Handler h, Program body) {
    return self().handleOf(h, body);
  }
}
