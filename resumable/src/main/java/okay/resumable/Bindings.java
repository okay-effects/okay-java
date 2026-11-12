package okay.resumable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import jdk.internal.access.JavaLangAccess;
import jdk.internal.access.SharedSecrets;

/** THE THREAD'S SCOPED-VALUE BINDINGS, switched with the stack, as okay-direct's Loom implementation found it must:
 * a continuation of ours runs on the thread that runs it, so it keeps the bindings of the stack it leaves and puts
 * back those of the stack it goes on with, the thread's cache of scoped values cleared. Used only when
 * {@link Loom#AVAILABLE}. */
final class Bindings {
  private static final JavaLangAccess LANG = SharedSecrets.getJavaLangAccess();
  private static final MethodHandle SET;

  static {
    try {
      SET = MethodHandles.privateLookupIn(Thread.class, MethodHandles.lookup())
          .findStatic(Thread.class, "setScopedValueBindings", MethodType.methodType(void.class, Object.class));
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private Bindings() {}

  static Object current() {
    return LANG.scopedValueBindings();
  }

  static void restore(Object bindings) {
    try {
      SET.invokeExact(bindings);
    } catch (Throwable t) {
      throw new IllegalStateException(t);
    }
    LANG.setScopedValueCache(null);
  }
}
