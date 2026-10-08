package okay.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
import okay.core.Fx.Top;
import okay.std.Choose;
import okay.std.State;
import okay.std.State.Final;
import org.junit.jupiter.api.Test;

/** the row in the type: a program at a level, a capability at its handler's — what the type rules out, checked by
 * compiling it */
class FxTest {

  /** the row the program needs, as capabilities at its level: any level they are both at */
  static <L> Fx<L, Integer> step(Cap<L, State<Integer>> st, Cap<L, Choose> ch) {
    return ch.perform(c -> c.among(List.of(1, 2)))
        .flatMap(x -> st.perform(s -> s.modify(v -> v + x)))
        .then(st.perform(State::get));
  }

  static <A> Fx.Handler<State<Integer>, A, Final<Integer, A>> state(int init) {
    return body -> State.state(init, body);
  }

  static <A> Fx.Handler<Choose, A, List<A>> all() {
    return Choose::all;
  }

  @Test
  void theHandlersInEitherOrderAnOuterCapabilityLifted() {
    Fx<Top, Final<Integer, List<Integer>>> stateOutside =
        Fx.handle(state(0), st -> Fx.handle(all(), ch -> step(st.lift(), ch)));
    assertEquals(new Final<>(3, List.of(1, 3)), Fx.run(stateOutside));
    Fx<Top, List<Final<Integer, Integer>>> chooseOutside =
        Fx.handle(all(), ch -> Fx.handle(state(0), st -> step(st, ch.lift())));
    assertEquals(List.of(new Final<>(1, 1), new Final<>(2, 2)), Fx.run(chooseOutside));
  }

  @Test
  void aProgramOfTheLevelOutsideLifted() {
    Fx<Top, Final<Integer, List<Integer>>> p = Fx.handle(state(10), st -> {
      Fx<Fx.Under<Top, State<Integer>>, Integer> outside = st.perform(State::get);
      return Fx.handle(all(), _ -> Fx.<Fx.Under<Top, State<Integer>>, Choose, Integer>lift(outside).map(s -> s + 1));
    });
    assertEquals(new Final<>(10, List.of(11)), Fx.run(p));
  }

  private static final String PRELUDE = """
      import okay.core.*;
      import okay.core.Fx.*;
      import okay.std.*;
      import okay.std.State.Final;
      import java.util.List;
      class Snippet {
        static <A> Fx.Handler<State<Integer>, A, Final<Integer, A>> state(int init) {
          return body -> State.state(init, body);
        }
        static <A> Fx.Handler<Choose, A, List<A>> all() {
          return Choose::all;
        }
        static void f() {
      """;

  /** the errors of the body, compiled */
  static List<String> errors(String body) throws IOException {
    Path dir = Files.createTempDirectory("fx");
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

  @Test
  void aCapabilityOutOfItsHandlerIsATypeError() throws IOException {
    // the same, right, compiles
    assertEquals(List.of(), errors("""
        Cap<Under<Top, State<Integer>>, State<Integer>> leaked =
            Fx.run(Fx.handle(state(0), (Cap<Under<Top, State<Integer>>, State<Integer>> st) -> Fx.pure(st))).value();
        """));
    // its level is Under<Top, State<Integer>>: no program at the top is
    assertFalse(errors("""
        Cap<Under<Top, State<Integer>>, State<Integer>> leaked =
            Fx.run(Fx.handle(state(0), (Cap<Under<Top, State<Integer>>, State<Integer>> st) -> Fx.pure(st))).value();
        Integer n = Fx.run(leaked.perform(State::get));
        """).isEmpty());
  }

  @Test
  void anInnerCapabilityAtTheLevelOutsideIsATypeError() throws IOException {
    // the same, right, compiles
    assertEquals(List.of(), errors("""
        Fx<Top, Final<Integer, Final<Integer, Cap<Under<Under<Top, State<Integer>>, State<Integer>>, State<Integer>>>>> p =
            Fx.handle(state(0), outer -> Fx.handle(state(1), inner -> Fx.pure(inner)));
        """));
    assertFalse(errors("""
        Fx<Top, Final<Integer, Integer>> p = Fx.handle(state(0), outer ->
            Fx.handle(state(1), inner -> Fx.pure(inner)).flatMap(f -> f.value().perform(State::get)));
        """).isEmpty());
  }

  @Test
  void anOuterCapabilityNotLiftedIsATypeError() throws IOException {
    // the same, right, compiles
    assertEquals(List.of(), errors("""
        Fx<Top, Final<Integer, List<Integer>>> p =
            Fx.handle(state(0), st -> Fx.handle(all(), ch -> st.<Choose>lift().perform(State::get)));
        """));
    assertFalse(errors("""
        Fx<Top, Final<Integer, List<Integer>>> p =
            Fx.handle(state(0), st -> Fx.handle(all(), ch -> st.perform(State::get)));
        """).isEmpty());
  }

  @Test
  void aProgramWithAHandlerMissingCannotRun() throws IOException {
    // the same, right, compiles
    assertEquals(List.of(), errors("""
        Fx<Top, Final<Integer, Integer>> p = Fx.handle(state(0), st -> st.perform(State::get));
        """));
    assertFalse(errors("""
        Integer n = Fx.run(Fx.handle(state(0), st -> st.perform(State::get)).flatMap(f -> f.value() == 0
            ? Fx.<Under<Top, Choose>, Integer>pure(0) : Fx.pure(1)));
        """).isEmpty());
  }
}
