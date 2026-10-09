package okay.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** docs/guide.md's examples, as code: its imports, its declarations and its runs made one class, compiled and run —
 * each run's checks holding; each block that does not compile compiled beside the declarations, and failing */
class GuideTest {

  record Blocks(List<String> imports, List<String> declarations, List<String> runs, List<String> failing) {}

  static Blocks blocks() throws IOException {
    String guide = Files.readString(Path.of("docs/guide.md"));
    Matcher m = Pattern.compile("```java\\n(.*?)```", Pattern.DOTALL).matcher(guide);
    Blocks b = new Blocks(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    while (m.find()) {
      String code = m.group(1);
      if (code.startsWith("// run")) b.runs().add(code);
      else if (code.startsWith("// does not compile")) b.failing().add(code);
      else if (code.startsWith("import")) b.imports().add(code);
      else b.declarations().add(code);
    }
    return b;
  }

  /** the class: the declarations its members, each run a block of {@code run()}, {@code check} the assertion */
  static String source(Blocks b, List<String> runs) {
    StringBuilder s = new StringBuilder(String.join("\n", b.imports()));
    s.append("\npublic class Guide {\n");
    s.append("  static void check(Object actual, Object expected) {\n");
    s.append("    if (!java.util.Objects.equals(actual, expected)) throw new AssertionError(actual + \" != \" + expected);\n");
    s.append("  }\n");
    for (String d : b.declarations()) s.append(d).append('\n');
    s.append("  public static void run() {\n");
    for (String r : runs) s.append("{\n").append(r).append("}\n");
    s.append("  }\n}\n");
    return s.toString();
  }

  @Test
  void theGuidesExamplesCompileAndHold() throws Exception {
    Blocks b = blocks();
    assertTrue(b.runs().size() >= 8 && b.declarations().size() >= 5, b.runs().size() + " runs");
    Path dir = Files.createTempDirectory("guide");
    List<String> errors = Javac.compile(dir, "Guide", source(b, b.runs()));
    assertEquals(List.of(), errors);
    try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] {dir.toUri().toURL()}, getClass().getClassLoader())) {
      loader.loadClass("Guide").getMethod("run").invoke(null);
    }
  }

  @Test
  void whatTheGuideSaysDoesNotCompileDoesNot() throws Exception {
    Blocks b = blocks();
    assertFalse(b.failing().isEmpty());
    for (String f : b.failing()) {
      List<String> errors = Javac.compile(Files.createTempDirectory("guide"), "Guide", source(b, List.of(f)));
      assertFalse(errors.isEmpty(), f);
    }
  }
}
