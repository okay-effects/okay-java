package okay.rows;

import java.util.function.Function;
import java.util.function.Supplier;
import okay.core.Rows;

/** WHERE A PROGRAM RUNS, while it runs: what direct-style code (okay-java-resumable) asks of the handlers in force
 * on its own Java stack, before it suspends. An operation whose handler is in place ({@link InPlace}) is answered at
 * once — the same handler, the same state, the same answer as if the operation were asked as a program, and nothing
 * captured. Any other is left to be asked as a program: {@link #ONCE} if its handler is general and declared
 * {@link Once}, {@link #ELSEWHERE} otherwise. A {@code Here} is good only while the function given it runs. */
public final class Here {
  public static final Object ELSEWHERE = Rows.Probe.ELSEWHERE;
  public static final Object ONCE = Rows.Probe.ONCE;
  /** a general handler that may resume more than once, of a clause made where it runs ({@link Eager}) */
  public static final Object EAGER = Rows.Probe.EAGER;

  /** A GENERAL HANDLER WHOSE CLAUSE IS A PROGRAM MADE WHERE IT RUNS ({@link #here}): a direct-style handler's
   * (okay-java-resumable). Its operation may be asked from the probing stack ({@link #resumeHere}) though it may
   * resume more than once: answered at once if the clause resumes the rest in tail position. {@link #eager}: whether
   * to ask it so now — false, the operation is asked as a program, as of any general handler */
  public interface Eager {
    default boolean eager() { return true; }
  }

  private final Rows.Probe probe;

  private Here(Rows.Probe probe) {
    this.probe = probe;
  }

  /** a computation made when it is reached, as {@link Effects#defer}, by a function given where it runs */
  public static <A, R> Comp<A, R> here(Function<Here, ? extends Comp<A, ? super R>> body) {
    return new Comp<>(Comp.DOOR.here(p -> body.apply(new Here(p)).eff));
  }

  /** {@code c} with one more place put at {@code place} of its row: its operations at that place or further go one
   * handler further out, the others where they would — {@link Effects#up} at 0, {@link Effects#under} at 1. For
   * okay-java-resumable: a sub-program of a shorter row run as a part of a program of a longer one */
  public static <A> Comp<A, Object> lifted(Comp<A, ?> c, int place) {
    return new Comp<>(Comp.DOOR.lift(c.eff, place));
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

  /** {@code finalizer} from here on, as {@link Comp#ensuring} puts it: the program the function given {@link #here}
   * returns, and everything it goes on with, are under it — so a rest taken after this has it */
  public void ensure(Supplier<? extends Comp<?, ?>> finalizer) {
    probe.ensure(() -> finalizer.get().eff);
  }

  /** an operation whose handler is general and declared {@link Once} — {@link #ONCE} — asked from here, the
   * handler's clause called on this stack: the answer, if the clause resumed the rest at once; else a
   * {@link Pending}, whose program the function given {@link #here} must return at once — and {@code later} is
   * given the answer when the rest is resumed */
  public Object resumeHere(Op<?, ?, ?> op, int place, Function<Object, ? extends Comp<?, ?>> later) {
    return pending(probe.resumeHere(op, place, x -> later.apply(x).eff));
  }

  /** the same, of a handler {@link #EAGER} or {@link #ONCE}: its clause, made where it runs, is made on this stack;
   * answered at once if it resumes at once — in tail position only, unless {@code once}. {@code unwound}, if not
   * null, the finalizer of what {@code later} resumes: run first when the rest is unwound */
  public Object resumeHere(Op<?, ?, ?> op, int place, Function<Object, ? extends Comp<?, ?>> later,
      Supplier<? extends Comp<?, ?>> unwound, boolean once) {
    return pending(probe.resumeHere(op, place, x -> later.apply(x).eff, unwound == null ? null
        : () -> unwound.get().eff, once));
  }

  /** the same, of a sensitive operation: its audit's clause, which passes it on as a program, and so never answers
   * it at once */
  public Object resumeHere(Sensitive.Op<?, ?, ?> op, int place, Function<Object, ? extends Comp<?, ?>> later) {
    return pending(probe.resumeHere(op, place, x -> later.apply(x).eff));
  }

  /** the same, of a sensitive operation, with {@code unwound} as above */
  public Object resumeHere(Sensitive.Op<?, ?, ?> op, int place, Function<Object, ? extends Comp<?, ?>> later,
      Supplier<? extends Comp<?, ?>> unwound, boolean once) {
    return pending(probe.resumeHere(op, place, x -> later.apply(x).eff, unwound == null ? null
        : () -> unwound.get().eff, once));
  }

  private static Object pending(Object r) {
    return r instanceof Rows.Probe.Pending p ? new Pending(new Comp<>(p.next())) : r;
  }

  /** what {@link #resumeHere} gives when the clause did not resume at once: the program to go on with */
  public record Pending(Comp<Object, Object> next) {}
}
