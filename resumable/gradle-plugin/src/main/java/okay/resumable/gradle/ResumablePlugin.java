package okay.resumable.gradle;

import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileCollection;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.ClasspathNormalizer;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.testing.Test;
import org.gradle.jvm.tasks.Jar;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.jvm.toolchain.JavaToolchainService;
import org.gradle.process.ExecOperations;

/** {@code plugins { id("dev.okay.resumable") }}: direct-style methods made resumable, with nothing else to write.
 * <ul>
 * <li>okay-java-resumable is added to {@code implementation}, of this plugin's version;
 * <li>every source set's Java classes are transformed as {@code compileJava} (and {@code compileTestJava}, ..) ends,
 * in place: the jar, the tests and {@code run} take them as they are. The transformer is an input of the compile
 * task, so a new one recompiles; the task stays incremental and cacheable;
 * <li>tests and {@code JavaExec} tasks are given the JVM's continuations ({@code --add-exports}, {@code --add-opens});
 * <li>the jar's manifest gets {@code Add-Exports}, {@code Add-Opens} and {@code Launcher-Agent-Class}: {@code java
 * -jar} has the continuations, and transforms what was not built so (a dependency) as it loads.
 * </ul>
 * Only Java classes: Kotlin's, Scala's and Groovy's compile tasks are not followed. */
public abstract class ResumablePlugin implements Plugin<Project> {
  static final List<String> JVM_ARGS = List.of("--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED",
      "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.lang=ALL-UNNAMED");

  @Inject
  protected abstract ExecOperations getExec();

  @Inject
  protected abstract JavaToolchainService getToolchains();

  @Override
  public void apply(Project project) {
    project.getPluginManager().apply(JavaPlugin.class);
    String version = ResumablePlugin.class.getPackage().getImplementationVersion();
    String library = "dev.okay:okay-java-resumable:" + (version == null ? "0.2.0-SNAPSHOT" : version);
    project.getDependencies().add(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME, library);
    Configuration transformer = project.getConfigurations().create("okayResumableTransformer", c -> {
      c.setCanBeConsumed(false);
      c.setDescription("the okay-java-resumable transformation, run after each Java compilation");
    });
    project.getDependencies().add(transformer.getName(), library);

    JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
    var launcher = getToolchains().launcherFor(java.getToolchain());
    ExecOperations exec = getExec();
    java.getSourceSets().all(set -> project.getTasks().named(set.getCompileJavaTaskName(), JavaCompile.class, t -> {
      t.getInputs().files(transformer).withPropertyName("okayResumableTransformer")
          .withNormalizer(ClasspathNormalizer.class);
      t.doLast("okay-java-resumable: transform", new Transform(exec, transformer, set, t.getDestinationDirectory(),
          launcher));
    }));

    project.getTasks().withType(Test.class).configureEach(t -> t.jvmArgs(JVM_ARGS));
    project.getTasks().withType(JavaExec.class).configureEach(t -> t.jvmArgs(JVM_ARGS));
    project.getTasks().named(JavaPlugin.JAR_TASK_NAME, Jar.class, j -> j.getManifest().getAttributes().putAll(
        Map.of("Add-Exports", "java.base/jdk.internal.vm java.base/jdk.internal.access",
            "Add-Opens", "java.base/java.lang", "Launcher-Agent-Class", "okay.resumable.transform.Agent")));
  }

  /** the classes of a compilation rewritten where they are: a JVM of the project's toolchain runs the transformer */
  private static final class Transform implements Action<Task> {
    private final ExecOperations exec;
    private final FileCollection transformer;
    private final FileCollection classpath;
    private final DirectoryProperty classes;
    private final org.gradle.api.provider.Provider<JavaLauncher> launcher;

    Transform(ExecOperations exec, FileCollection transformer, SourceSet set, DirectoryProperty classes,
        org.gradle.api.provider.Provider<JavaLauncher> launcher) {
      this.exec = exec;
      this.transformer = transformer;
      this.classpath = set.getCompileClasspath();
      this.classes = classes;
      this.launcher = launcher;
    }

    @Override
    public void execute(Task task) {
      String dir = classes.get().getAsFile().getPath();
      if (!classes.get().getAsFile().exists()) return;
      exec.javaexec(spec -> {
        spec.setExecutable(launcher.get().getExecutablePath().getAsFile());
        spec.classpath(transformer);
        spec.getMainClass().set("okay.resumable.transform.Main");
        spec.args(dir, dir, classpath.getAsPath());
      });
    }
  }

}
