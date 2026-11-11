package okay.rows;

import static okay.rows.Comp.DOOR;

import okay.core.Eff.Top;

/**
 * WHERE THE TWO NOTATIONS MEET. A program of the row notation and one of the capability notation ({@code okay.core},
 * {@code okay.std}) are both programs of okay-java's machine: one may run inside the other, and a handler written in
 * one may answer the operations of a program written in the other. An operation of the capability notation goes to
 * its handler by prompt, whatever handlers of rows are between; one of the row notation, by its place, whatever
 * handlers of capabilities are between: neither counts the other's.
 */
public final class Bridge {
  private Bridge() {}

  /** a row program with nothing left to ask, as a program of the capability notation at any level */
  @SuppressWarnings("unchecked") // levels are phantom: a program asking nothing runs at any
  public static <L, A> okay.core.Eff<L, A> eff(Comp<A, ? super Pure> p) {
    return (okay.core.Eff<L, A>) (okay.core.Eff<?, A>) p.eff;
  }

  /** a program of the capability notation at the top — it holds no capability of a handler outside — as a row
   * program, in any row */
  public static <A, R> Comp<A, R> comp(okay.core.Eff<Top, A> p) {
    return new Comp<>(p);
  }

  // -- a capability program, its operations answered by a row handler

  /** what performs the row's first effect from a program of the capability notation, at its level {@code M} */
  public interface Asker<M, F> {
    <X, O extends Op<F, X, ? super O>> okay.core.Eff<M, X> perform(O op);
  }

  /** a body of the capability notation: at a fresh level, given what performs the row's first effect there */
  public interface Asking<F, A> {
    <M> okay.core.Eff<M, A> in(Asker<M, F> ask);
  }

  /** the body as a row program: its operations of {@code F} go to the handler of the row's first place */
  public static <F, A> Comp<A, Eff<F, ?>> performing(Asking<F, A> body) {
    return new Comp<>(okay.core.Eff.delay(() -> body.<Object>in(new Asker<>() {
      @SuppressWarnings("unchecked") // the operation is answered by an X: it is an Op<F, X, ..>
      public <X, O extends Op<F, X, ? super O>> okay.core.Eff<Object, X> perform(O op) {
        return (okay.core.Eff<Object, X>) DOOR.ask(op, 0);
      }
    })));
  }

  /** {@code okay.std.State}'s body, its state the row's first effect, {@code okay.rows.std.State}'s */
  public static <S, A> Comp<A, Eff<okay.rows.std.State<S>, ?>> state(okay.std.State.Body<Top, S, A> body) {
    okay.std.State<Top, S> st = new okay.std.State<>() {
      public okay.core.Eff<Top, S> get() {
        return Handlers.trusted(DOOR.ask(new okay.rows.std.State.Get<S>(), 0));
      }

      public okay.core.Eff<Top, Void> put(S s) {
        return Handlers.trusted(DOOR.ask(new okay.rows.std.State.Put<>(s), 0));
      }
    };
    return new Comp<>(okay.core.Eff.delay(() -> body.<Top>in(st)));
  }

  // -- a row program, its operations answered by programs of the capability notation

  /** a clause: the operation's answer, a program of the capability notation at the level {@code L}, given to
   * {@code to} with the operation as the witness of its type */
  public interface Answering<L, F> {
    okay.core.Eff<L, Cell.Answered> answer(F op, To<L> to);
  }

  /** where a clause's answer goes */
  public static final class To<L> {
    private final Object asked;
    private Object value;

    private To(Object asked) {
      this.asked = asked;
    }

    /** {@code x}'s value, the answer to {@code op}: a {@code Get<S>} is answered by an {@code S} */
    public <X> okay.core.Eff<L, Cell.Answered> answer(Op<?, X, ?> op, okay.core.Eff<L, X> x) {
      if (op != asked) throw new IllegalArgumentException("not the operation asked: " + op);
      return x.map(v -> {
        value = v;
        return ANSWERED;
      });
    }
  }

  private static final Cell.Answered ANSWERED = Cell.answered();

  /** the row program {@code p}, its effect {@code F} answered by {@code h} at the level {@code L}, where the result
   * runs: each operation's clause a program there, the rest resumed with its answer */
  public static <L, F, A> okay.core.Eff<L, A> answering(
      Class<? super F> effect, Answering<L, F> h, Comp<A, ? super Eff<F, Pure>> p) {
    Comp<A, Pure> handled = Effects.handle(effect, new Handler<F, A, A, Pure>() {
      public Comp<A, Pure> ret(A a) {
        return Effects.pure(a);
      }

      public Comp<A, Pure> on(F op, K<A, Pure> k) {
        To<L> to = new To<>(op);
        return new Comp<A, Pure>(h.answer(op, to).flatMap(done -> Handlers.<okay.core.Eff<L, A>>trusted(DOOR.resume(k.rest(), to.value))));
      }
    }, p);
    return eff(handled);
  }

  /** the row program's state answered by a capability of {@code okay.std.State} */
  public static <L, S, A> okay.core.Eff<L, A> state(
      okay.std.State<L, S> st, Comp<A, ? super Eff<okay.rows.std.State<S>, Pure>> p) {
    return answering(okay.rows.std.State.class, new Answering<L, okay.rows.std.State<S>>() {
      public okay.core.Eff<L, Cell.Answered> answer(okay.rows.std.State<S> op, To<L> to) {
        return switch (op) {
          case okay.rows.std.State.Get<S> get -> to.answer(get, st.get());
          case okay.rows.std.State.Put<S> put -> to.answer(put, st.put(put.value()));
        };
      }
    }, p);
  }
}
