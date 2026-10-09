package okay.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

/** what the type checker says of a piece of code: it compiled, in a method of a class of its own, against the main
 * classes — the errors, none if it is right */
public final class Javac {
  private Javac() {}

  private static final String PRELUDE = """
      import okay.core.*;
      import okay.core.Eff.Top;
      import okay.std.*;
      import okay.std.State.Final;
      import java.util.List;
      import java.util.function.Function;
      class Snippet {
        static void f() {
      """;

  public static List<String> errors(String body) throws IOException {
    Path dir = Files.createTempDirectory("javac");
    Path src = dir.resolve("Snippet.java");
    Files.writeString(src, PRELUDE + body + "\n  }\n}\n");
    JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    try (var files = javac.getStandardFileManager(null, null, null)) {
      javac.getTask(null, files, diagnostics,
          List.of("-classpath", System.getProperty("java.class.path"), "-d", dir.toString()), null,
          files.getJavaFileObjects(src)).call();
    }
    return diagnostics.getDiagnostics().stream()
        .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
        .map(d -> d.getMessage(null))
        .collect(Collectors.toList());
  }
}
