package okay.rows;

import static okay.rows.Effects.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import okay.core.Eff.Top;
import okay.rows.std.Choose;
import okay.rows.std.State;
import okay.rows.std.Writer;
import org.junit.jupiter.api.Test;

/** the row notation and the capability notation on one machine: each inside the other, a handler of one answering
 * the operations of a program of the other */
class BridgeTest {

  /** a row program: a state, a choice under it */
  static Comp<Integer, Eff<State<Integer>, Eff<Choose, ?>>> chosen() {
    return up(Choose.among(List.of(1, 2))).flatMap(x -> State.<Integer>get().flatMap(s ->
        State.put(s + x).map(done -> s + x)));
  }

  /** the same, the choice first: a state outside it is one for both paths */
  static Comp<Integer, Eff<Choose, Eff<State<Integer>, Pure>>> stateOutside() {
    return Choose.among(List.of(1, 2)).flatMap(x -> up(State.<Integer>get().flatMap(s ->
        State.put(s + x).map(done -> s + x))));
  }

  /** a program of the capability notation: the guide's counter, by the capability it is given */
  static <M> okay.core.Eff<M, Integer> counter(okay.std.State<M, Integer> st) {
    return st.modify(x -> x + 1).then(st.get());
  }

  @Test
  void aRowProgramInsideACapabilityProgram() {
    // the row program handled whole, a value of the capability program, under its writer
    okay.std.Writer.Log<String, List<State.Final<Integer, Integer>>> log =
        okay.core.Eff.run(okay.std.Writer.writer(new okay.std.Writer.Body<Top, String,
            List<State.Final<Integer, Integer>>>() {
          public <M extends Top> okay.core.Eff<M, List<State.Final<Integer, Integer>>> in(
              okay.std.Writer<M, String> w) {
            return w.tell("before").then(Bridge.<M, List<State.Final<Integer, Integer>>>eff(
                Choose.all(State.run(0, chosen())))).flatMap(r -> w.tell("after").map(done -> r));
          }
        }));
    assertEquals(new okay.std.Writer.Log<>(List.of("before", "after"),
        List.of(new State.Final<>(1, 1), new State.Final<>(2, 2))), log);
  }

  @Test
  void aCapabilityHandlerAnswersARowProgram() {
    // the row program's state is okay.std's, the one outside: threaded through both paths of its choice
    okay.std.State.Final<Integer, List<Integer>> threaded = okay.core.Eff.run(okay.std.State.state(10,
        new okay.std.State.Body<Top, Integer, List<Integer>>() {
          public <M extends Top> okay.core.Eff<M, List<Integer>> in(okay.std.State<M, Integer> st) {
            return Bridge.state(st, Choose.all(stateOutside()));
          }
        }));
    assertEquals(new okay.std.State.Final<>(13, List.of(11, 13)), threaded);
  }

  @Test
  void aRowHandlerAnswersACapabilityProgram() {
    // the capability program's state is the row's, its place the first; a writer at the next
    assertEquals(new State.Final<>(1, 1), run(State.run(0, Bridge.state(BridgeTest::counter))));
    Comp<Integer, Eff<State<Integer>, Eff<Writer<String>, ?>>> both =
        Bridge.<Integer, Integer>state(BridgeTest::counter)
            .flatMap(n -> up(Writer.tell("counted " + n)).map(done -> n));
    assertEquals(new Writer.Logged<>(List.of("counted 6"), new State.Final<>(6, 6)),
        run(Writer.run(State.run(5, both))));
  }

  @Test
  void aCapabilityProgramAtTheTopIsARowProgram() {
    okay.core.Eff<Top, Integer> plain = okay.core.Eff.<Top, Integer>pure(20).map(x -> x + 1);
    assertEquals(new State.Final<>(0, 42), run(State.run(0, Bridge.<Integer, Eff<State<Integer>, Pure>>comp(plain)
        .map(x -> x * 2))));
  }
}
