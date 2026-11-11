import static okay.rows.Effects.*;

import okay.rows.Comp;
import okay.rows.Eff;
import okay.rows.Op;
import okay.rows.std.State;

/** An operation that is not of the effect it names: its handler would be given what is not its effect. A class that
 * names itself is refused where it is declared; one that names another class, where it is performed. */
final class WrongOperation {
  record Odd() implements Op<State<Integer>, Integer, Odd> {} // refused: an Odd is not a State

  /** names an operation of the state as itself: this compiles, and nothing can perform it */
  record Liar() implements Op<State<Integer>, Integer, State.Get<Integer>> {}

  /** names the effect as itself */
  record Vague() implements Op<State<Integer>, Integer, State<Integer>> {}

  /** names whatever its user says */
  record Any<T extends State<Integer>>() implements Op<State<Integer>, Integer, T> {}

  static Comp<Integer, Eff<State<Integer>, ?>> honest() {
    // an operation of the state: this line is here to show that the ones after it are refused for theirs
    return perform(new State.Get<Integer>());
  }

  static Comp<Integer, Eff<State<Integer>, ?>> liar() {
    return perform(new Liar()); // refused: a Liar is not the Get it names
  }

  static Comp<Integer, Eff<State<Integer>, ?>> vague() {
    return perform(new Vague()); // refused: a Vague is not a State
  }

  static Comp<Integer, Eff<State<Integer>, ?>> any() {
    return perform(new Any<State.Get<Integer>>()); // refused: an Any is not the Get it names
  }

  /** the operation's own class forgotten: nothing says it is of the effect */
  static Comp<Integer, Eff<State<Integer>, ?>> forgotten(Op<State<Integer>, Integer, ?> op) {
    return perform(op); // refused: of no class known
  }
}
