package okay.resumable.transform;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.WeakHashMap;

/** THE TRANSFORMATION AT LOAD TIME, for a jar built without the plugin: named by the jar's manifest as its
 * {@code Launcher-Agent-Class}, it is started by {@code java -jar} before the main class, with no flag. Only then: a
 * jar on a classpath, or a test run, does not start it. Each class is rewritten as the build would have; a class
 * the build already rewrote is left as it is. A method that cannot be made resumable is said on stderr and loaded as
 * it is: it is refused at run time if it suspends. */
public final class Agent {
  private Agent() {}

  public static void agentmain(String args, Instrumentation inst) {
    install(inst);
  }

  public static void premain(String args, Instrumentation inst) {
    install(inst);
  }

  static void install(Instrumentation inst) {
    inst.addTransformer(new Rewriting());
  }

  private static final class Rewriting implements ClassFileTransformer {
    private final Map<ClassLoader, Transformer> transformers = new WeakHashMap<>();
    /** the classes the transformation itself loads are not transformed */
    private final ThreadLocal<Boolean> busy = ThreadLocal.withInitial(() -> false);

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> redefined, ProtectionDomain domain,
        byte[] bytes) {
      if (loader == null || name == null || busy.get() || name.startsWith("java/") || name.startsWith("jdk/")
          || name.startsWith("sun/") || name.startsWith("okay/resumable/transform/")) return null;
      busy.set(true);
      try {
        Transformer t;
        synchronized (transformers) {
          t = transformers.computeIfAbsent(loader, l -> new Transformer(n -> Main.read(l, n),
              Transformer.Guard.of(System.getProperty("okay.resumable.guard", "off")),
              Transformer.Forbid.of(System.getProperty("okay.resumable.forbid", ""), n -> Main.read(l, n)),
              System.err::println));
        }
        synchronized (t) {
          return t.transform(bytes);
        }
      } catch (Transformer.Refused e) {
        System.err.println("okay.resumable: " + e.getMessage());
        return null;
      } catch (RuntimeException e) {
        System.err.println("okay.resumable: " + name + " could not be transformed: " + e);
        return null;
      } finally {
        busy.set(false);
      }
    }
  }
}
