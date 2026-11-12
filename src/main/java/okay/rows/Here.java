package okay.rows;

import java.util.function.Function;
import okay.core.Rows;

/** WHERE A PROGRAM RUNS, while it runs: what direct-style code (okay-java-resumable) asks of the handlers in force
 * on its own Java stack, before it suspends. An operation whose handler is in place ({@link InPlace}) is answered at
 * once — the same handler, the same state, the same answer as if the operation were asked as a program, and nothing
 * captured. Any other is left to be asked as a program: {@link #ONCE} if its handler is general and declared
 * {@link Once}, {@link #ELSEWHERE} otherwise. A {@code Here} is good only while the function given it runs. */
public final class Here {
  public static final Object ELSEWHERE = Rows.Probe.ELSEWHERE;
  public static final Object ONCE = Rows.Probe.ONCE;

  private final Rows.Probe probe;

  private Here(Rows.Probe probe) {
    this.probe = probe;
  }

  /** a computation made when it is reached, as {@link Effects#defer}, by a function given where it runs */
  public static <A, R> Comp<A, R> here(Function<Here, ? extends Comp<A, ? super R>> body) {
    return new Comp<>(Comp.DOOR.here(p -> body.apply(new Here(p)).eff));
  }

  /** the answer to {@code op}, an operation of the effect {@code place} places along the row, if its handler is in
   * place; else {@link #ONCE} or {@link #ELSEWHERE}. A sensitive operation is never answered here: it is not an
   * {@code Op} */
  public Object ask(Op<?, ?, ?> op, int place) {
    return probe.ask(op, place);
  }

  /** {@link #ONCE} or {@link #ELSEWHERE} for a sensitive operation: never answered here, even by a handler in place,
   * so that it passes its audit */
  public Object kind(Sensitive.Op<?, ?, ?> op, int place) {
    return probe.kind(op, place);
  }

  /** an operation whose handler is general and declared {@link Once} — {@link #ONCE} — asked from here, the
   * handler's clause called on this stack: the answer, if the clause resumed the rest at once; else a
   * {@link Pending}, whose program the function given {@link #here} must return at once — and {@code later} is
   * given the answer when the rest is resumed */
  public Object resumeHere(Op<?, ?, ?> op, int place, Function<Object, ? extends Comp<?, ?>> later) {
    return pending(probe.resumeHere(op, place, x -> later.apply(x).eff));
  }

  /** the same, of a sensitive operation: its audit's clause, which passes it on as a program, and so never answers
   * it at once */
  public Object resumeHere(Sensitive.Op<?, ?, ?> op, int place, Function<Object, ? extends Comp<?, ?>> later) {
    return pending(probe.resumeHere(op, place, x -> later.apply(x).eff));
  }

  private static Object pending(Object r) {
    return r instanceof Rows.Probe.Pending p ? new Pending(new Comp<>(p.next())) : r;
  }

  /** what {@link #resumeHere} gives when the clause did not resume at once: the program to go on with */
  public record Pending(Comp<Object, Object> next) {}
}
