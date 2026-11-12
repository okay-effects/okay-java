import okay.resumable.Direct;
import okay.resumable.SensitiveSuspend;
import okay.rows.Sensitive;

/** a sensitive operation performed by a method that does not declare its effect; or that declares it and calls one
 * that performs it without declaring it either. Apart from SensitiveDirect.java: javac checks what a method throws
 * only where nothing else in the file is refused */
class SensitiveUndeclared {
  abstract static sealed class Pay extends SensitiveSuspend {
    static final class Transfer extends Pay implements Sensitive.Op<Pay, String, Transfer> {}
  }

  static String undeclared() {
    return Direct.perform(new Pay.Transfer()); // refused
  }

  static String declared() throws Pay {
    return Direct.perform(new Pay.Transfer());
  }

  static String caller() {
    return declared(); // refused
  }
}
