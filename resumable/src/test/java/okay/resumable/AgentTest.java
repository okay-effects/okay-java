package okay.resumable;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code java -jar} of a jar built without the transformation, with no flag: its manifest's Launcher-Agent-Class
 * transforms the classes as they load */
class AgentTest {
  @Test
  void aJarBuiltWithoutThePluginRunsWithJavaJarItsContinuationsExportedByItsManifest() throws IOException, InterruptedException {
    String java = ProcessHandle.current().info().command().orElseThrow();
    Process p = new ProcessBuilder(java, "-jar", System.getProperty("okay.resumable.jarSample"))
        .redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
    assertEquals(0, p.waitFor(), out);
    System.out.println(out);
    // every size, and the order taken once: its frames were resumed twice, not replayed
    assertEquals(List.of("the JVM's continuations", "[coffee small, coffee large] [order]"), out.lines().toList());
  }
}
