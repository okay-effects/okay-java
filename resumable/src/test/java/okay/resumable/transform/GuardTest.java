package okay.resumable.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** THE GUARD over the module's own test classes as javac wrote them: effectful code that reaches an effect's
 * implementation other than to install it — {@code MisuseTest}'s programs, and nothing else */
class GuardTest {
  static final Path CLASSES = Path.of("build/classes/java/test");

  static byte[] bytes(String name) {
    try {
      Path f = CLASSES.resolve(name + ".class");
      if (Files.exists(f)) return Files.readAllBytes(f);
      try (InputStream s = ClassLoader.getSystemResourceAsStream(name + ".class")) {
        return s == null ? null : s.readAllBytes();
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static List<Path> classes() throws IOException {
    try (Stream<Path> s = Files.walk(CLASSES)) {
      return s.filter(f -> f.toString().endsWith(".class")).sorted().toList();
    }
  }

  @Test
  void warnedOfMisuseTestsProgramsOnly() throws IOException {
    List<String> warned = new ArrayList<>();
    Transformer t = new Transformer(GuardTest::bytes, Transformer.Guard.WARN, warned::add);
    for (Path f : classes()) t.transform(Files.readAllBytes(f));
    warned.forEach(System.out::println);
    // the body that calls the handler's clause, and the test's lambda that does; the one that sets its state (read,
    // written, read again); the one that calls the bank: each said for what it does — constructing a handler, to
    // install it, is not, anywhere in the module's tests
    assertEquals(6, warned.size(), String.join("\n", warned));
    assertTrue(warned.stream().allMatch(w -> w.startsWith("okay.resumable: warning: okay.resumable.MisuseTest.")));
    assertTrue(warned.stream().anyMatch(w -> w.contains("callsTheClause") && w.contains("calls on of "
        + "okay.resumable.MisuseTest$Keeping")));
    assertTrue(warned.stream().anyMatch(w -> w.contains("setsTheState") && w.contains("writes s of")));
    assertTrue(warned.stream().anyMatch(w -> w.contains("paysAroundTheAudit") && w.contains("calls transfer of "
        + "okay.resumable.MisuseTest$Bank")));
  }

  @Test
  void refusedAsErrors() throws IOException {
    Transformer t = new Transformer(GuardTest::bytes, Transformer.Guard.ERROR, w -> {});
    var e = assertThrows(Transformer.Refused.class,
        () -> t.transform(Files.readAllBytes(CLASSES.resolve("okay/resumable/MisuseTest.class"))));
    assertEquals(6, e.getMessage().lines().count(), e.getMessage());
    assertTrue(e.getMessage().contains("perform the effect instead"), e.getMessage());
  }

  @Test
  void offByDefault() throws IOException {
    assertNotNull(new Transformer(GuardTest::bytes).transform(
        Files.readAllBytes(CLASSES.resolve("okay/resumable/MisuseTest.class"))));
    assertEquals(Transformer.Guard.OFF, Transformer.Guard.of("off"));
    assertEquals(Transformer.Guard.WARN, Transformer.Guard.of("warn"));
    assertEquals(Transformer.Guard.ERROR, Transformer.Guard.of("error"));
    assertThrows(IllegalArgumentException.class, () -> Transformer.Guard.of("maybe"));
  }

  @Test
  void aClauseCalledThroughItsInterfaceIsAClauseCall() throws IOException {
    // a program calling Direct.Handler0.on on a reference typed by the interface: the clause called directly
    Path dir = Files.createTempDirectory("guard");
    Path src = dir.resolve("Through.java");
    Files.writeString(src, """
        class Through {
          static okay.resumable.Direct.Handler0<okay.resumable.Counter, Integer, Integer> h;
          static int calls(okay.resumable.Counter op) throws okay.resumable.Counter, okay.resumable.Direct.Resumes {
            return okay.resumable.Counter.get() + h.on(op, null);
          }
        }
        """);
    assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-cp",
        System.getProperty("java.class.path"), "-d", dir.toString(), src.toString()));
    List<String> warned = new ArrayList<>();
    new Transformer(GuardTest::bytes, Transformer.Guard.WARN, warned::add)
        .transform(Files.readAllBytes(dir.resolve("Through.class")));
    assertEquals(1, warned.size(), warned.toString());
    assertTrue(warned.getFirst().contains("calls Direct.Handler0.on, a handler's clause, directly"), warned.toString());
  }

  static final String WORLD = """
      class World {
        static final class Client { static void send(String s) {} }

        static int net() throws Exception, okay.resumable.Counter {
          new java.net.URL("http://example.com").openStream();
          return okay.resumable.Counter.get();
        }
        static int process() throws Exception, okay.resumable.Counter {
          new ProcessBuilder("ls").start();
          Runtime.getRuntime().exec(new String[] {"ls"});
          return okay.resumable.Counter.get();
        }
        static int exit() throws okay.resumable.Counter {
          if (okay.resumable.Counter.get() < 0) System.exit(1);
          return 0;
        }
        static int reflect() throws Exception, okay.resumable.Counter {
          World.class.getDeclaredMethod("exit").setAccessible(true);
          return okay.resumable.Counter.get();
        }
        static int client() throws okay.resumable.Counter {
          Client.send("x");
          return okay.resumable.Counter.get();
        }
        static int clean() throws okay.resumable.Counter {
          java.util.List<Integer> l = new java.util.ArrayList<>();
          l.add(okay.resumable.Counter.get());
          return l.size() + Math.max(1, 2);
        }
        static void notEffectful() throws Exception {
          new ProcessBuilder("ls").start();
          System.exit(0);
        }
      }
      """;

  static byte[] world() throws IOException {
    Path dir = Files.createTempDirectory("world");
    Path src = dir.resolve("World.java");
    Files.writeString(src, WORLD);
    assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-cp",
        System.getProperty("java.class.path"), "-d", dir.toString(), src.toString()));
    return Files.readAllBytes(dir.resolve("World.class"));
  }

  @Test
  void forbiddenApisRefusedInEffectfulCodeOnly() throws IOException {
    byte[] world = world();
    Transformer t = new Transformer(GuardTest::bytes, Transformer.Guard.OFF,
        Transformer.forbidden("strict, World$Client"), w -> {});
    var e = assertThrows(Transformer.Refused.class, () -> t.transform(world));
    List<String> lines = e.getMessage().lines().toList();
    lines.forEach(System.out::println);
    // each named by method, line and the entry it matched
    assertTrue(lines.stream().anyMatch(l -> l.startsWith("World.net (line 5) constructs java.net.URL, forbidden "
        + "here by java.net.*")), e.getMessage());
    assertTrue(lines.stream().anyMatch(l -> l.startsWith("World.process (line 9) constructs java.lang.ProcessBuilder")
        && l.contains("by java.lang.ProcessBuilder")), e.getMessage());
    assertTrue(lines.stream().anyMatch(l -> l.startsWith("World.process (line 10) calls exec of java.lang.Runtime")
        && l.contains("by java.lang.Runtime.exec")), e.getMessage());
    assertTrue(lines.stream().anyMatch(l -> l.startsWith("World.exit (line 14) calls exit of java.lang.System")),
        e.getMessage());
    assertTrue(lines.stream().anyMatch(l -> l.startsWith("World.reflect (line 18) calls setAccessible of "
        + "java.lang.reflect.Method") && l.contains("by java.lang.reflect.*")), e.getMessage());
    assertTrue(lines.stream().anyMatch(l -> l.startsWith("World.client (line 22) calls send of World$Client")
        && l.contains("by World$Client")), e.getMessage());
    // a method that only performs, and one that is not effectful: nothing of either
    assertTrue(lines.stream().noneMatch(l -> l.startsWith("World.clean") || l.startsWith("World.notEffectful")),
        e.getMessage());
    // said, not refused, when the guard warns; nothing at all with no list
    List<String> warned = new ArrayList<>();
    assertNotNull(new Transformer(GuardTest::bytes, Transformer.Guard.WARN, Transformer.forbidden("java.lang.System"),
        warned::add).transform(world));
    assertEquals(1, warned.size(), warned.toString());
    assertTrue(warned.getFirst().contains("World.exit (line 14) calls exit of java.lang.System"), warned.toString());
    assertNotNull(new Transformer(GuardTest::bytes).transform(world));
  }

  @Test
  void theStrictPresetAndTheList() {
    List<String> f = Transformer.forbidden("strict , com.acme.Bank");
    assertTrue(f.containsAll(Transformer.STRICT));
    assertEquals("com.acme.Bank", f.getLast());
    assertTrue(Transformer.forbidden("  ").isEmpty());
  }
}
