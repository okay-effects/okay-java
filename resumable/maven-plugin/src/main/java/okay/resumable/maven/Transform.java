package okay.resumable.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;

/** What both goals do: a directory of classes rewritten in place, and surefire given the JVM's continuations */
final class Transform {
  private Transform() {}

  static final String JVM_ARGS = "--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED "
      + "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED";

  static void run(MavenProject project, String classes, List<String> classpath, Log log) throws MojoExecutionException {
    if (Runtime.version().feature() < 24)
      throw new MojoExecutionException("okay-java-resumable needs Maven to run on JDK 24 or later "
          + "(java.lang.classfile); this is " + Runtime.version());
    Path dir = Path.of(classes);
    if (Files.isDirectory(dir)) {
      try {
        int n = okay.resumable.transform.Main.transform(dir, dir, String.join(File.pathSeparator, classpath));
        log.info("okay.resumable: " + n + " class(es) made resumable in " + dir);
      } catch (IOException | IllegalStateException e) {
        throw new MojoExecutionException(e.getMessage(), e);
      }
    }
    // as JaCoCo does: surefire reads the property argLine when it runs
    String argLine = project.getProperties().getProperty("argLine", "");
    if (!argLine.contains("jdk.internal.vm"))
      project.getProperties().setProperty("argLine", (argLine + " " + JVM_ARGS).trim());
  }
}
