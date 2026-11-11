package okay.rows;

import okay.core.Rows;

/** The rest of a handled program, waiting for the answer of the operation it asked. A value, never changed:
 * resumed never, once, or many times; or UNWOUND — left: the finalizers that wait in it ({@code ensuring}) run, and
 * nothing else of it. Nothing is remembered of what was done with it: every resumption is a path, which runs its
 * finalizers at its end, and every unwinding is the end of a path — unwound twice, the finalizers run twice. A
 * clause that neither resumes nor unwinds runs nothing of it: the rest is kept, and may be resumed later
 * ({@code Async}). A clause that does not go on with the rest says {@code k.unwind()} first, so that what the
 * program holds is released. */
public final class K<B, R> {
  private final Object asked;
  private final Rows.Rest rest;
  /** an interceptor's (by the class): it is no place, and leaves none when unwound */
  private final boolean place;

  K(Object asked, Rows.Rest rest, boolean place) {
    this.asked = asked;
    this.rest = rest;
    this.place = place;
  }

  /** the rest resumed with the answer; the operation is the witness of the answer's type:
   * {@code case Among<X> among -> k.resume(among, among.values().get(0))} */
  public <X> Comp<B, R> resume(Op<?, X, ?> op, X answer) {
    if (op != asked) throw new IllegalArgumentException("not the operation asked: " + op);
    return new Comp<>(Comp.DOOR.resume(rest, answer));
  }

  /** the rest left: its finalizers, the innermost first, then nothing ({@code null}). No bind of it runs, no
   * {@code ret} of the handlers in it; their states are dropped. Each finalizer runs where it was installed: the
   * handlers of the rest answer it. The handler that unwinds has left: an operation of its own that a finalizer
   * asks is answered by nobody — the finalizer ends there, and the unwinding goes on. (An interceptor's effect
   * has its handler outside, and its operations go there.) A failure of a finalizer answered inside what is
   * unwound does not stop the unwinding. */
  public Comp<Void, R> unwind() {
    return new Comp<>(Comp.DOOR.unwind(rest, place ? Rows.GONE : Rows.NONE));
  }

  /** {@code k.unwind()}, by a handler that kept its effect in the row — an interceptor at a place
   * ({@code handle(.., under(p))}, as {@code Reader.local}): the row outside begins with the same effect
   * {@code F}, and an operation of {@code F} that a finalizer asks goes to that handler. The operation asked is
   * the witness that {@code F} is this handler's effect. */
  public static <F, B, R extends Eff<F, ?>, O extends Op<F, ?, ? super O>> Comp<Void, R> unwindKeeping(
      K<B, R> k, O op) {
    if (op != k.asked) throw new IllegalArgumentException("not the operation asked: " + op);
    return new Comp<>(Comp.DOOR.unwind(k.rest, Rows.KEPT));
  }

  /** the same rest, for {@code okay.rows}' own: an audit's, an observer's */
  Rows.Rest rest() {
    return rest;
  }
}
