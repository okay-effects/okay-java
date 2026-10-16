package okay.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/** a transaction's body cannot use a handler's capability from outside it — what could drop its rest, leaving the
 * transaction open — and the same body with the handler inside compiles */
class TransactionLeakTest {
  static final String SNIPPET = """
      import okay.core.Eff;
      import okay.std.Throws;
      import okay.spring.EffTransactions;
      class Snippet {
        static Eff<Eff.Top, String> f(EffTransactions txs) {
          return Throws.catching(new Throws.Body<Eff.Top, String, String>() {
            public <M extends Eff.Top> Eff<M, String> in(Throws<M, String> t) {
              return %s;
            }
          }, e -> Eff.pure(e));
        }
      }
      """;

  static List<String> errors(String expr) throws Exception {
    Path dir = Files.createTempDirectory("javac");
    Path src = dir.resolve("Snippet.java");
    Files.writeString(src, SNIPPET.formatted(expr));
    var javac = ToolProvider.getSystemJavaCompiler();
    var diagnostics = new DiagnosticCollector<JavaFileObject>();
    try (var files = javac.getStandardFileManager(null, null, null)) {
      javac.getTask(null, files, diagnostics,
          List.of("-classpath", System.getProperty("java.class.path"), "-d", dir.toString()), null,
          files.getJavaFileObjects(src)).call();
    }
    return diagnostics.getDiagnostics().stream()
        .filter(d -> d.getKind() == Diagnostic.Kind.ERROR).map(d -> d.getMessage(null)).toList();
  }

  @Test
  void aCapabilityFromOutsideInTheBodyDoesNotCompile() throws Exception {
    assertFalse(errors("txs.transactional(t.<String>raise(\"no\")).<M>at()").isEmpty());
  }

  @Test
  void theTransactionInsideTheHandlerCompiles() throws Exception {
    assertEquals(List.of(), errors("txs.transactional(Eff.<Eff.Top, String>pure(\"yes\")).<M>at()"));
  }
}
