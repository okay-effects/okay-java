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
  static final String AMBIENT = """
      import okay.resumable.Counter;
      import okay.resumable.Direct;

      class Ambient {
        static int count;
        static final java.util.List<String> NAMES = java.util.List.of("a");
        static final Object LOCK = new Object();
        static final class Worker extends Thread {}

        static int state() throws Counter {
          count = Counter.get();
          return count + NAMES.size();
        }
        static String reader() throws Counter {
          Counter.get();
          return System.getenv("HOME") + System.getProperty("user.dir");
        }
        static void writer() throws Counter {
          System.out.println(Counter.get());
          java.util.logging.Logger.getLogger("x").info("y");
        }
        static long clock() throws Counter {
          Counter.get();
          return System.nanoTime() + java.time.Instant.now().getNano() + java.time.LocalDate.now().getDayOfMonth();
        }
        static long fresh() throws Counter {
          Counter.get();
          return java.util.UUID.randomUUID().hashCode() + new java.util.Random().nextInt()
              + java.util.concurrent.ThreadLocalRandom.current().nextInt();
        }
        static void async() throws Exception, Counter {
          Counter.get();
          Thread.sleep(1);
          new Worker().start();
          java.util.concurrent.CompletableFuture.runAsync(() -> {});
          LOCK.notify();
          String s = "s";
          s.wait(1);
        }
        static Object io() throws Exception, Counter {
          Counter.get();
          java.nio.file.Files.readString(java.nio.file.Path.of("x"));
          return new java.io.FileInputStream("x");
        }
        static int nested() throws Counter {
          return okay.rows.Effects.run(okay.rows.Effects.pure(Counter.get()));
        }
        static int installs() throws Counter {
          return Direct.row(Counter.class).embed(Counter.run(Counter.get(),
              Direct.row(Counter.class, Counter.class).of(() -> 1))).value() + NAMES.size();
        }
        static int clean() throws Counter {
          java.util.List<Integer> l = new java.util.ArrayList<>(NAMES.size());
          l.add(Counter.get());
          return Math.max(l.size(), 2);
        }
        static void notEffectful() throws Exception {
          count = 1;
          System.out.println(System.nanoTime() + " " + java.util.UUID.randomUUID());
          new java.io.FileInputStream("x").close();
          okay.rows.Effects.run(okay.rows.Effects.pure(1));
        }
      }
      """;

  static Path ambientDir;

  /** Ambient compiled, once: its classes beside the module's */
  static byte[] ambient() throws IOException {
    if (ambientDir == null) {
      Path dir = Files.createTempDirectory("ambient");
      Path src = dir.resolve("Ambient.java");
      Files.writeString(src, AMBIENT);
      assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-cp",
          System.getProperty("java.class.path"), "-d", dir.toString(), src.toString()));
      ambientDir = dir;
    }
    return Files.readAllBytes(ambientDir.resolve("Ambient.class"));
  }

  static byte[] ambientBytes(String name) {
    try {
      Path f = ambientDir.resolve(name + ".class");
      return Files.exists(f) ? Files.readAllBytes(f) : bytes(name);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** what {@code list} refuses in Ambient, the guard off: one line each */
  static List<String> refused(String list) throws IOException {
    byte[] ambient = ambient();
    var e = assertThrows(Transformer.Refused.class,
        () -> new Transformer(GuardTest::ambientBytes, Transformer.Guard.OFF, Transformer.forbidden(list), w -> {})
            .transform(ambient));
    return e.getMessage().lines().toList();
  }

  static void has(List<String> lines, String method, String what, String instead) {
    assertTrue(lines.stream().anyMatch(l -> l.startsWith("Ambient." + method + " (line ") && l.contains(what)
        && l.contains(" — use " + instead)), method + ": " + what + "\n" + String.join("\n", lines));
  }

  @Test
  void eachPresetNamedAfterAnEffectRefusesItsAmbientWayAndNamesTheEffect() throws IOException {
    List<String> state = refused("state");
    has(state, "state", "writes count of Ambient, a static field that is not final", "okay.rows.std.State");
    assertEquals(1, state.size(), String.join("\n", state));
    List<String> reader = refused("reader");
    has(reader, "reader", "calls getenv of java.lang.System, forbidden here by java.lang.System.getenv",
        "okay.rows.std.Reader");
    has(reader, "reader", "calls getProperty of java.lang.System", "okay.rows.std.Reader");
    assertEquals(2, reader.size(), String.join("\n", reader));
    for (String w : List.of("writer", "trail")) {
      List<String> writer = refused(w);
      has(writer, "writer", "reads out of java.lang.System", "okay.rows.std.Writer, or okay.rows.std.Trail");
      has(writer, "writer", "calls getLogger of java.util.logging.Logger, forbidden here by java.util.logging.*",
          "okay.rows.std.Writer");
      has(writer, "writer", "calls info of java.util.logging.Logger", "okay.rows.std.Writer");
      assertEquals(3, writer.size(), String.join("\n", writer));
    }
    List<String> clock = refused("clock");
    has(clock, "clock", "calls nanoTime of java.lang.System, forbidden here by java.lang.System.nanoTime",
        "okay.rows.std.Clock");
    has(clock, "clock", "calls now of java.time.Instant", "okay.rows.std.Clock");
    has(clock, "clock", "calls now of java.time.LocalDate", "okay.rows.std.Clock");
    assertEquals(3, clock.size(), String.join("\n", clock));
    List<String> fresh = refused("fresh");
    has(fresh, "fresh", "calls randomUUID of java.util.UUID", "okay.rows.std.Fresh");
    has(fresh, "fresh", "constructs java.util.Random", "okay.rows.std.Fresh");
    // a subclass: ThreadLocalRandom is a Random
    has(fresh, "fresh", "calls current of java.util.concurrent.ThreadLocalRandom", "okay.rows.std.Fresh");
    List<String> async = refused("async");
    has(async, "async", "calls sleep of java.lang.Thread", "okay.rows.std.Async");
    // a subclass of Thread, constructed and started
    has(async, "async", "constructs Ambient$Worker, forbidden here by java.lang.Thread", "okay.rows.std.Async");
    has(async, "async", "calls start of Ambient$Worker", "okay.rows.std.Async");
    has(async, "async", "calls runAsync of java.util.concurrent.CompletableFuture", "okay.rows.std.Async");
    has(async, "async", "calls notify of java.lang.Object", "okay.rows.std.Async");
    // Object's wait, called on a String
    has(async, "async", "calls wait of java.lang.Object, forbidden here by java.lang.Object.wait",
        "okay.rows.std.Async");
    List<String> io = refused("io");
    has(io, "io", "calls readString of java.nio.file.Files, forbidden here by java.nio.file.*",
        "an effect of your own");
    has(io, "io", "constructs java.io.FileInputStream", "an effect of your own");
    List<String> nested = refused("nested-run");
    has(nested, "nested", "calls run of okay.rows.Effects, forbidden here by okay.rows.Effects.run",
        "Direct.row(..).embed(..)");
    assertEquals(1, nested.size(), String.join("\n", nested));
    // pure: all of them; and nothing of a method that installs a handler, one that is clean, one that performs
    // nothing
    List<String> pure = refused("pure");
    for (String m : List.of("state", "reader", "writer", "clock", "fresh", "async", "io", "nested"))
      assertTrue(pure.stream().anyMatch(l -> l.startsWith("Ambient." + m + " (line ")), m + "\n" + pure);
    assertTrue(pure.stream().noneMatch(l -> l.startsWith("Ambient.installs") || l.startsWith("Ambient.clean")
        || l.startsWith("Ambient.notEffectful")), String.join("\n", pure));
  }

  @Test
  void aStaticFieldNotFinalReadIsSaidNotRefused() throws IOException {
    byte[] ambient = ambient();
    List<String> warned = new ArrayList<>();
    var e = assertThrows(Transformer.Refused.class, () -> new Transformer(GuardTest::ambientBytes,
        Transformer.Guard.OFF, Transformer.forbidden("state"), warned::add).transform(ambient));
    assertEquals(1, e.getMessage().lines().count(), e.getMessage());
    // count read, said; NAMES, final, read in state, installs and clean: nothing
    assertEquals(1, warned.size(), String.join("\n", warned));
    assertTrue(warned.getFirst().startsWith("okay.resumable: warning: Ambient.state (line 12) reads count of Ambient, "
        + "a static field that is not final"), warned.getFirst());
  }

  @Test
  void thePresetsInTheList() {
    assertEquals(List.of("java.util.UUID.randomUUID", "java.util.Random"),
        Transformer.forbidden("fresh").subList(0, 2));
    List<String> pure = Transformer.forbidden("pure");
    for (var p : Transformer.PRESETS.values()) assertTrue(pure.containsAll(p.apis()));
    assertEquals(pure.size(), new java.util.HashSet<>(pure).size());
    assertEquals(List.of("okay.rows.Effects.run"), Transformer.forbidden("okay.rows.Effects.run"));
  }
}
