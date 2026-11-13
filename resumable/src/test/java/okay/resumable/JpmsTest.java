package okay.resumable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/** THE STRONGEST PREVENTION, by the module system: the effect's module exports the effect and the way to install
 * its handler, not the bank nor the handler that calls it (src/test/jpms). A program's module that names them does
 * not compile — at the lines marked {@code // refused}, and at no other; the program that performs the effect
 * does */
class JpmsTest {
  static final Path SRC = Path.of("src/test/jpms");

  @Test
  void aModuleOfProgramsCannotNameTheBank() throws IOException {
    Path bypass = SRC.resolve("shop/shop/Bypass.java");
    List<String> lines = Files.readAllLines(bypass);
    TreeSet<Long> marked = new TreeSet<>();
    IntStream.range(0, lines.size()).filter(i -> lines.get(i).contains("// refused")).forEach(i -> marked.add(i + 1L));
    assertFalse(marked.isEmpty());
    List<Diagnostic<? extends JavaFileObject>> all = compile(true);
    TreeSet<Long> at = new TreeSet<>();
    for (var d : all) {
      if (d.getKind() != Diagnostic.Kind.ERROR) continue;
      assertTrue(d.getSource().getName().endsWith("Bypass.java"), d.toString());
      assertTrue(d.getMessage(null).contains("is not visible"), d.toString());
      at.add(d.getLineNumber());
    }
    assertEquals(marked, at);
    // without the bypass: both modules compile
    assertTrue(compile(false).stream().noneMatch(d -> d.getKind() == Diagnostic.Kind.ERROR));
  }

  /** both modules compiled, okay-java and this module as automatic modules */
  static List<Diagnostic<? extends JavaFileObject>> compile(boolean withBypass) throws IOException {
    return compile(withBypass, Files.createTempDirectory("jpms"));
  }

  static List<Diagnostic<? extends JavaFileObject>> compile(boolean withBypass, Path out) throws IOException {
    List<Path> sources;
    try (Stream<Path> s = Files.walk(SRC)) {
      sources = s.filter(f -> f.toString().endsWith(".java"))
          .filter(f -> withBypass || !f.getFileName().toString().equals("Bypass.java")).toList();
    }
    var javac = ToolProvider.getSystemJavaCompiler();
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    try (var files = javac.getStandardFileManager(null, null, null)) {
      javac.getTask(null, files, diagnostics, List.of("--release", "25", "--module-source-path", SRC.toString(),
          "--module-path", modulePath(), "-d", out.toString()), null,
          files.getJavaFileObjectsFromPaths(sources)).call();
    }
    return diagnostics.getDiagnostics();
  }

  /** okay-java, this module's classes and okay-kernel-api, each a jar named for its automatic module */
  static String modulePath() throws IOException {
    Path dir = Files.createTempDirectory("modules");
    List<String> path = new ArrayList<>();
    for (String e : System.getProperty("java.class.path").split(File.pathSeparator)) {
      Path p = Path.of(e);
      String name = p.toString();
      if (p.getFileName().toString().startsWith("okay-kernel-api")) path.add(name);
      else if (p.endsWith(Path.of("resumable/build/classes/java/main")))
        path.add(jar(p, dir.resolve("okay-java-resumable.jar")));
      else if (p.endsWith(Path.of("build/classes/java/main"))) path.add(jar(p, dir.resolve("okay-java.jar")));
      else if (p.getFileName().toString().matches("okay-java-[0-9].*\\.jar"))
        path.add(copy(p, dir.resolve("okay-java.jar")));
    }
    assertEquals(3, path.size(), path.toString());
    return String.join(File.pathSeparator, path);
  }

  static String copy(Path from, Path to) throws IOException {
    Files.copy(from, to);
    return to.toString();
  }

  static String jar(Path classes, Path jar) throws IOException {
    try (OutputStream o = Files.newOutputStream(jar); JarOutputStream j = new JarOutputStream(o);
        Stream<Path> s = Files.walk(classes)) {
      for (Path f : s.filter(Files::isRegularFile).toList()) {
        j.putNextEntry(new JarEntry(classes.relativize(f).toString().replace(File.separatorChar, '/')));
        j.write(Files.readAllBytes(f));
        j.closeEntry();
      }
    }
    return jar.toString();
  }

  @Test
  void reflectionCannotReachIntoAPackageNotOpened() throws Exception {
    Path out = Files.createTempDirectory("jpms");
    assertTrue(compile(false, out).stream().noneMatch(d -> d.getKind() == Diagnostic.Kind.ERROR));
    // the modules as an application runs them: a layer of their own
    var finder = java.lang.module.ModuleFinder.of(Stream.concat(Stream.of(out),
        Stream.of(modulePath().split(File.pathSeparator)).map(Path::of)).toArray(Path[]::new));
    var parent = ModuleLayer.boot();
    var config = parent.configuration().resolve(finder, java.lang.module.ModuleFinder.of(), java.util.Set.of("shop"));
    var layer = parent.defineModulesWithOneLoader(config, ClassLoader.getSystemClassLoader());
    Class<?> bank = layer.findLoader("payments").loadClass("payments.bank.Bank");
    // the class is there, and public; its package is neither exported nor opened: no call, no field, not even
    // made accessible by force
    assertThrows(IllegalAccessException.class,
        () -> bank.getMethod("transfer", String.class, int.class).invoke(null, "thief", 900));
    var balance = bank.getDeclaredField("balance");
    assertThrows(java.lang.reflect.InaccessibleObjectException.class, () -> balance.setAccessible(true));
    assertThrows(IllegalAccessException.class, () -> java.lang.invoke.MethodHandles.privateLookupIn(bank,
        java.lang.invoke.MethodHandles.lookup()));
    // the exported package is reachable as any: the effect itself
    assertEquals("payments.Pay", layer.findLoader("payments").loadClass("payments.Pay").getName());
  }
}
