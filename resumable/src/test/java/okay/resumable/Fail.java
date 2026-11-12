package okay.resumable;

import static okay.rows.Effects.handle;
import static okay.rows.Effects.pure;

import okay.rows.Comp;
import okay.rows.Eff;
import okay.rows.Handler;
import okay.rows.K;
import okay.rows.Once;
import okay.rows.Op;
import okay.rows.std.Throws;

/** A FAILURE in direct style: the handler does not go on, and unwinds the rest */
public abstract sealed class Fail extends Suspend {
  public static final class Raise extends Fail implements Op<Fail, Void, Raise> {
    final String why;

    public Raise(String why) { this.why = why; }

    @Override
    public String toString() { return "Raise(" + why + ")"; }
  }

  public static <X> X raise(String why) throws Fail {
    Direct.perform(new Raise(why));
    throw new IllegalStateException("a failure was answered");
  }

  public static <A, R> Comp<Throws.Result<String, A>, R> run(Comp<A, ? super Eff<Fail, R>> p) {
    return handle(Fail.class, new Handler<Fail, A, Throws.Result<String, A>, R>() {
      public Comp<Throws.Result<String, A>, R> ret(A a) { return pure(new Throws.Ok<>(a)); }
      public Comp<Throws.Result<String, A>, R> on(Fail op, K<Throws.Result<String, A>, R> k) {
        return switch (op) {
          case Raise raise -> k.unwind().map(left -> new Throws.Err<>(raise.why));
        };
      }
    }, p);
  }

  /** the same, declared Once (it never resumes): on the JVM's continuation the body yields, and is unwound there */
  public static <A, R> Comp<Throws.Result<String, A>, R> runOnce(Comp<A, ? super Eff<Fail, R>> p) {
    final class Leave implements Handler<Fail, A, Throws.Result<String, A>, R>, Once {
      public Comp<Throws.Result<String, A>, R> ret(A a) { return pure(new Throws.Ok<>(a)); }
      public Comp<Throws.Result<String, A>, R> on(Fail op, K<Throws.Result<String, A>, R> k) {
        return switch (op) {
          case Raise raise -> k.unwind().map(left -> new Throws.Err<>(raise.why));
        };
      }
    }
    return handle(Fail.class, new Leave(), p);
  }
}
