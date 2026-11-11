package okay.rows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** WHAT THE COMPILER PROMISES, as okay-effects' {@code negative.sh} checks it: every program in
 * {@code src/test/negative/rows} is refused, and for its reason — javac's errors are at the lines marked
 * {@code // refused}, at every one of them and at no other */
class NegativeTest {

  static final Path DIR = Path.of("src/test/negative/rows");

  @TestFactory
  Stream<DynamicTest> everyProgramIsRefusedAtTheMarkedLines() throws IOException {
    List<Path> files;
    try (Stream<Path> s = Files.list(DIR)) {
      files = s.filter(f -> f.toString().endsWith(".java")).sorted().toList();
    }
    assertFalse(files.isEmpty());
    return files.stream().map(f -> DynamicTest.dynamicTest(f.getFileName().toString(), () -> {
      List<String> lines = Files.readAllLines(f);
      TreeSet<Long> marked = new TreeSet<>();
      IntStream.range(0, lines.size()).filter(i -> lines.get(i).contains("// refused"))
          .forEach(i -> marked.add(i + 1L));
      assertFalse(marked.isEmpty(), f + " marks no line");
      assertEquals(marked, errorLines(f), f.toString());
    }));
  }

  /** the lines of {@code file} javac reports an error at, compiled against the library */
  static TreeSet<Long> errorLines(Path file) throws IOException {
    var javac = ToolProvider.getSystemJavaCompiler();
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    Path out = Files.createTempDirectory("negative");
    try (var files = javac.getStandardFileManager(null, null, null)) {
      javac.getTask(null, files, diagnostics,
          List.of("--release", "25", "-classpath", System.getProperty("java.class.path"), "-d", out.toString()),
          null, files.getJavaFileObjects(file)).call();
    }
    TreeSet<Long> at = new TreeSet<>();
    for (var d : diagnostics.getDiagnostics())
      if (d.getKind() == Diagnostic.Kind.ERROR) at.add(d.getLineNumber());
    return at;
  }
}
