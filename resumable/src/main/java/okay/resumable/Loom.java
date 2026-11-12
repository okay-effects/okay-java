package okay.resumable;

import java.lang.System.Logger.Level;

/** WHETHER THE JVM'S ONE-SHOT CONTINUATIONS can be used: {@code jdk.internal.vm} and {@code jdk.internal.access}
 * exported to this module, {@code java.lang} open to it. Given by the build's plugins to tests and runs, by a jar's
 * manifest ({@code Add-Exports}, {@code Add-Opens}) to {@code java -jar}. Where they are not, an operation whose
 * handler is general and declared {@code Once} takes the saved frames, as one that may resume many times does; said
 * once, on the logger {@code okay.resumable}. {@code -Dokay.resumable.loom=false} turns them off. */
final class Loom {
  private Loom() {}

  static final String WHY_NOT = whyNot();
  static final boolean AVAILABLE = WHY_NOT == null;
  private static volatile boolean told;

  private static String whyNot() {
    if ("false".equals(System.getProperty("okay.resumable.loom"))) return "-Dokay.resumable.loom=false";
    Module base = Object.class.getModule();
    Module me = Loom.class.getModule();
    for (String p : new String[] {"jdk.internal.vm", "jdk.internal.access"})
      if (!base.isExported(p, me)) return "java.base does not export " + p + " to " + me;
    if (!base.isOpen("java.lang", me)) return "java.base does not open java.lang to " + me;
    return null;
  }

  /** said once: why a one-shot handler takes saved frames here */
  static void tell() {
    if (told) return;
    told = true;
    System.getLogger("okay.resumable").log(Level.INFO, "okay.resumable: the JVM's continuations are not used ("
        + WHY_NOT + "): a handler that resumes once takes saved frames. To use them: --add-exports "
        + "java.base/jdk.internal.vm=ALL-UNNAMED --add-exports java.base/jdk.internal.access=ALL-UNNAMED "
        + "--add-opens java.base/java.lang=ALL-UNNAMED, as the okay-java-resumable plugins add, or java -jar of a "
        + "jar whose manifest has Add-Exports and Add-Opens");
  }
}
