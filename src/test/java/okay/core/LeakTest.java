package okay.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** WHAT DOES NOT COMPILE: a capability, a prompt, a program out of its handler — each way out tried, beside the same
 * program written right, which compiles. A handler's body is at a fresh level, a type variable no one outside can
 * name: what leaves it is at a level no program is at */
class LeakTest {

  private static void rightCompilesWrongDoesNot(String right, String wrong) throws IOException {
    assertEquals(List.of(), Javac.errors(right), right);
    assertFalse(Javac.errors(wrong).isEmpty(), wrong);
  }

  /** a capability out of a state's body, as a value of the type it may have outside, {@code State<?, Integer>} */
  private static final String LEAKED = """
      State<?, Integer> leaked = Eff.run(State.state(0, new State.Body<Top, Integer, State<?, Integer>>() {
        public <M extends Top> Eff<M, State<?, Integer>> in(State<M, Integer> st) { return Eff.pure(st); }
      })).value();
      """;

  @Test
  void aCapabilityOutUsedAtTheTop() throws IOException {
    rightCompilesWrongDoesNot(LEAKED, LEAKED + "Integer n = Eff.run(leaked.get());");
    rightCompilesWrongDoesNot(LEAKED, LEAKED + "Integer n = Eff.run(leaked.get().at());");
  }

  private static String secondState(String body) {
    return LEAKED + """
        Final<Integer, Integer> r = Eff.run(State.state(1, new State.Body<Top, Integer, Integer>() {
          public <M extends Top> Eff<M, Integer> in(State<M, Integer> st) { return %s; }
        }));
        """.formatted(body);
  }

  @Test
  void aCapabilityOutUsedInAnotherHandlerAtTheSamePlace() throws IOException {
    // the two bodies' levels are two variables: one's capability is not the other's
    rightCompilesWrongDoesNot(secondState("st.get()"), secondState("leaked.get().at()"));
    rightCompilesWrongDoesNot(secondState("st.get()"), secondState("Eff.<M, Void>pure(null).flatMap(_ -> leaked.get())"));
  }

  private static String innerOut(String use) {
    return """
        Final<Integer, Integer> r = Eff.run(State.state(0, new State.Body<Top, Integer, Integer>() {
          public <M extends Top> Eff<M, Integer> in(State<M, Integer> outer) {
            return State.state(1, new State.Body<M, Integer, State<?, Integer>>() {
              public <N extends M> Eff<N, State<?, Integer>> in(State<N, Integer> inner) { return Eff.pure(inner); }
            }).flatMap(f -> %s);
          }
        }));
        """.formatted(use);
  }

  @Test
  void anInnerCapabilityUsedAtTheLevelOutsideIt() throws IOException {
    rightCompilesWrongDoesNot(innerOut("outer.get()"), innerOut("f.value().get()"));
    rightCompilesWrongDoesNot(innerOut("outer.get()"), innerOut("f.value().get().at()"));
  }

  private static String programOut(String run) {
    return """
        Eff<?, Integer> built = Eff.run(State.state(0, new State.Body<Top, Integer, Eff<?, Integer>>() {
          public <M extends Top> Eff<M, Eff<?, Integer>> in(State<M, Integer> st) { return Eff.pure(st.get()); }
        })).value();
        Integer n = %s;
        """.formatted(run);
  }

  @Test
  void aProgramBuiltInsideRunOutside() throws IOException {
    rightCompilesWrongDoesNot(programOut("0"), programOut("Eff.run(built)"));
    rightCompilesWrongDoesNot(programOut("0"), programOut("Eff.run(built.at())"));
  }

  private static String promptOut(String use) {
    return """
        Prompt<?, ?, ?> p = (Prompt<?, ?, ?>) Eff.run(Eff.handle(new Eff.Body<Top, Object, Object>() {
          public <M extends Top> Eff<M, Object> in(Prompt<Top, M, Object> p) { return Eff.pure(p); }
        }, Eff::pure));
        Object n = %s;
        """.formatted(use);
  }

  @Test
  void aPromptOut() throws IOException {
    rightCompilesWrongDoesNot(promptOut("0"), promptOut("Eff.run(p.shift0(k -> k.apply(null)))"));
  }

  @Test
  void aCapabilityLiftedInwardOnlyNotOutward() throws IOException {
    String body = """
        Final<Integer, Integer> r = Eff.run(State.state(0, new State.Body<Top, Integer, Integer>() {
          public <M extends Top> Eff<M, Integer> in(State<M, Integer> outer) {
            return State.state(1, new State.Body<M, Integer, Integer>() {
              public <N extends M> Eff<N, Integer> in(State<N, Integer> inner) {
                %s
              }
            }).map(Final::value);
          }
        }));
        """;
    // the outer capability at the inner level: in; the inner at the outer: no such move
    rightCompilesWrongDoesNot(body.formatted("State<N, Integer> in = outer.at(); return in.get();"),
        body.formatted("State<M, Integer> out = inner; return inner.get();"));
  }
}
