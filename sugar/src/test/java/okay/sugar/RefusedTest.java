package okay.sugar;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import okay.sugar.processor.SugarProcessor;
import org.junit.jupiter.api.Test;

/** What the processor refuses, and in which words: each source is compiled here, with the processor. */
class RefusedTest {
  private static String errors(String source) {
    var compiler = ToolProvider.getSystemJavaCompiler();
    var seen = new DiagnosticCollector<JavaFileObject>();
    JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///T.java"), JavaFileObject.Kind.SOURCE) {
      @Override
      public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
    };
    var options = List.of("-proc:only", "-classpath", System.getProperty("java.class.path"));
    var task = compiler.getTask(null, null, seen, options, null, List.of(file));
    task.setProcessors(List.of(new SugarProcessor()));
    boolean ok = task.call();
    StringBuilder out = new StringBuilder();
    for (var d : seen.getDiagnostics()) if (d.getKind() == Diagnostic.Kind.ERROR) out.append(d.getMessage(null));
    assertFalse(ok && out.isEmpty(), "compiled, and should not have");
    return out.toString();
  }

  private static void refused(String source, String words) {
    String errors = errors("import okay.sugar.*; import okay.effect.*;\n" + source);
    assertTrue(errors.contains(words), errors);
  }

  private static final String PRICES =
      "@Effect sealed interface P<T> extends Operation<T, P<?>> { record Get() implements P<Long> {} }\n";

  @Test
  void anEffectIsASealedInterfaceOfRecords() {
    refused("@Effect interface T { long price(String item); }", "is not an effect");
    refused("@Effect class T {}", "is not an effect");
  }

  @Test
  void anEffectWithATypeParameterOfItsOwn() {
    refused("@Effect sealed interface S<X> { record Get<X>() implements S<X>, Op<S<X>, X, Get<X>> {} }",
        "with a type parameter of its own");
  }

  @Test
  void aRowListsEffectsOnly() {
    refused("@Uses({String.class}) class T {}", "java.lang.String is not an effect");
  }

  @Test
  void anOperationOfTwoEffectsOfTheRowIsAskedByHand() {
    refused(PRICES + "sealed interface Q<T> extends Operation<T, Q<?>> { record Get() implements Q<Long> {} }\n"
        + "@Uses({P.class, Q.class}) class T {}", "is an operation of both");
  }
}
