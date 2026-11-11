package okay.rows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** okay-effects' {@code Properties} and {@code Tck}, copied with only their package and imports changed, run as they
 * are run there: their {@code main}, in a JVM of its own (it ends with {@code System.exit}), at the stack the
 * library's tests have — each property a line, {@code + } passed, {@code ==> X } failed */
class PropertiesTest {

  @Test
  void theReferencesPropertiesHold() throws Exception {
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    Process p = new ProcessBuilder(java, "-Xss512k", "-cp", System.getProperty("java.class.path"),
        "okay.rows.Properties").redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    int exit = p.waitFor();
    System.out.print(out);
    List<String> failed = out.lines().filter(l -> l.startsWith("==> X")).toList();
    assertEquals(List.of(), failed);
    assertTrue(out.contains("java: all passed"), out);
    assertEquals(0, exit);
  }
}
