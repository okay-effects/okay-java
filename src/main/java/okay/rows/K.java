package okay.rows;

import okay.effect.Op;
import okay.effect.Eff;
import okay.effect.Sensitive;
import okay.core.Rows;

/** The rest of a handled program, waiting for the answer of the operation it asked. A value, never changed:
 * resumed never, once, or many times; or UNWOUND — left: the finalizers that wait in it ({@code ensuring}) run, and
 * nothing else of it. Nothing is remembered of what was done with it: every resumption is a path, which runs its
 * finalizers at its end, and every unwinding is the end of a path — unwound twice, the finalizers run twice. A
 * clause that neither resumes nor unwinds runs nothing of it: the rest is kept, and may be resumed later
 * ({@code Async}). A clause that does not go on with the rest says {@code k.unwind()} first, so that what the
 * program holds is released. */
public sealed class K<B, R> permits KS {
  private final Object asked;
  /** taken already; or, of a clause called before its rest is taken (Effects.handle of a Clause), given when it is */
  private Rows.Rest rest;
  private final boolean late;
  /** a late continuation's first resumption and its answer: a clause whose program is that alone answered in place */
  okay.core.Comp<?, ?> first;
  Object firstAnswer;
  boolean answeredInPlace;
  /** of a clause with a state: whether that first resumption says the state to go on with, and the state */
  boolean firstGiven;
  Object firstState;
  /** an interceptor's (by the class): it is no place, and leaves none when unwound */
  private final boolean place;

  K(Object asked, Rows.Rest rest, boolean place) {
    this.asked = asked;
    this.rest = rest;
    this.place = place;
    this.late = false;
  }

  /** the continuation of a clause called before its rest is taken */
  K(Object asked) {
    this.asked = asked;
    this.place = true;
    this.late = true;
  }

  /** the rest, taken now: what the clause made with this continuation runs after this */
  void take(Rows.Rest taken) {
    rest = taken;
  }

  private okay.core.Comp<?, ?> resumed(Object answer) {
    return resumed(answer, false, null);
  }

  /** the operation asked, the witness of an answer's type: any other is refused */
  final void witness(Object op) {
    if (op != asked) throw new IllegalArgumentException("not the operation asked: " + op);
  }

  /** the rest resumed with the answer; {@code given}: its handler's state for that path {@code state} */
  final okay.core.Comp<?, ?> resumed(Object answer, boolean given, Object state) {
    if (!late) return Comp.DOOR.resume(rest, answer);
    okay.core.Comp<?, ?> c = okay.core.Comp.delay(() -> Handlers.trusted(
        Comp.DOOR.resume(given ? Comp.DOOR.with(taken(), state) : taken(), answer)));
    if (first == null) {
      first = c;
      firstAnswer = answer;
      firstGiven = given;
      firstState = state;
    }
    return c;
  }

  /** a program of the rest: made at once, or — the rest not taken yet — when it is run */
  private okay.core.Comp<?, ?> late(java.util.function.Supplier<okay.core.Comp<?, ?>> made) {
    return late ? okay.core.Comp.delay(() -> Handlers.trusted(made.get())) : made.get();
  }

  private Rows.Rest taken() {
    if (rest == null)
      throw new IllegalStateException(answeredInPlace
          ? "a continuation whose clause only answered was resumed again: " + asked
          : "a continuation run before its clause returned: " + asked);
    return rest;
  }

  /** the rest resumed with the answer; the operation is the witness of the answer's type:
   * {@code case Among<X> among -> k.resume(among, among.values().get(0))} */
  public <X> Comp<B, R> resume(Op<?, X, ?> op, X answer) {
    if (op != asked) throw new IllegalArgumentException("not the operation asked: " + op);
    return new Comp<>(resumed(answer));
  }

  /** a sensitive operation answered: by the handler of its audit ({@link Audit#handle}), or by its interceptor */
  public <X> Comp<B, R> resume(Sensitive.Op<?, X, ?> op, X answer) {
    if (op != asked) throw new IllegalArgumentException("not the operation asked: " + op);
    return new Comp<>(resumed(answer));
  }

  /** {@code c}, then the rest resumed with its value. If {@code c} is left — the handler that answers it unwinds
   * what waits for it —, the rest is unwound there too, its handler staying, so its finalizers run. What an
   * interceptor goes on with: {@code k.after(op, proceed)}, where {@code proceed.flatMap(x -> k.resume(op, x))}
   * would leave the rest neither resumed nor unwound. */
  public <X> Comp<B, R> after(Op<?, X, ?> op, Comp<X, ? super R> c) {
    if (op != asked) throw new IllegalArgumentException("not the operation asked: " + op);
    return new Comp<>(late(() -> Comp.DOOR.after(taken(), c.eff)));
  }

  /** the same, of a sensitive operation */
  public <X> Comp<B, R> after(Sensitive.Op<?, X, ?> op, Comp<X, ? super R> c) {
    if (op != asked) throw new IllegalArgumentException("not the operation asked: " + op);
    return new Comp<>(late(() -> Comp.DOOR.after(taken(), c.eff)));
  }

  /** the operation asked again, at the place of the handler's effect in the row outside, the rest resumed with its
   * answer as by {@link #after}: what an interceptor at a place passes on (Effects.observe) */
  Comp<B, R> again() { return new Comp<>(late(() -> Comp.DOOR.after(taken(), Comp.DOOR.ask(asked, 0)))); }

  /** the rest left: its finalizers, the innermost first, then nothing ({@code null}). No bind of it runs, no
   * {@code ret} of the handlers in it; their states are dropped. Each finalizer runs where it was installed: the
   * handlers of the rest answer it. The handler that unwinds has left: an operation of its own that a finalizer
   * asks is answered by nobody — the finalizer ends there, and the unwinding goes on. (An interceptor's effect
   * has its handler outside, and its operations go there.) A failure of a finalizer answered inside what is
   * unwound does not stop the unwinding. */
  public Comp<Void, R> unwind() {
    return new Comp<>(late(() -> Comp.DOOR.unwind(taken(), place ? Rows.GONE : Rows.NONE)));
  }

  /** {@code k.unwind()}, by a handler that kept its effect in the row — an interceptor at a place
   * ({@code handle(.., under(p))}, as {@code Reader.local}): the row outside begins with the same effect
   * {@code F}, and an operation of {@code F} that a finalizer asks goes to that handler. The operation asked is
   * the witness that {@code F} is this handler's effect. */
  public static <F, B, R extends Eff<F, ?>, O extends Op<F, ?, ? super O>> Comp<Void, R> unwindKeeping(
      K<B, R> k, O op) {
    if (op != k.asked) throw new IllegalArgumentException("not the operation asked: " + op);
    return new Comp<>(k.late(() -> Comp.DOOR.unwind(k.taken(), Rows.KEPT)));
  }

  /** the same rest, for {@code okay.rows}' own: an audit's, an observer's */
  Rows.Rest rest() {
    return taken();
  }
}
