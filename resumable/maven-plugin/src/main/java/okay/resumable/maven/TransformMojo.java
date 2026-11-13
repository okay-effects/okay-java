package okay.resumable.maven;

import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

/** {@code target/classes} made resumable, in place, after compilation */
@Mojo(name = "transform", defaultPhase = LifecyclePhase.PROCESS_CLASSES,
    requiresDependencyResolution = ResolutionScope.COMPILE, threadSafe = true)
public class TransformMojo extends AbstractMojo {
  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject project;

  /** effectful code that reaches an effect's implementation other than to install it: off, warn or error */
  @Parameter(property = "okay.resumable.guard", defaultValue = "off")
  private String guard;

  /** the APIs effectful code may not use, comma separated; {@code strict} the preset */
  @Parameter(property = "okay.resumable.forbid", defaultValue = "")
  private String forbid;

  @Override
  public void execute() throws MojoExecutionException {
    try {
      Transform.run(project, project.getBuild().getOutputDirectory(), project.getCompileClasspathElements(),
          guard, forbid, getLog());
    } catch (DependencyResolutionRequiredException e) {
      throw new MojoExecutionException(e.getMessage(), e);
    }
  }
}
