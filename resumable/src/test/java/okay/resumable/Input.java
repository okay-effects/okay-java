package okay.resumable;

import static okay.rows.Effects.handle;
import static okay.rows.Effects.pure;

import java.util.Iterator;
import okay.rows.Comp;
import okay.rows.Eff;
import okay.rows.Handler;
import okay.rows.K;
import okay.rows.Once;
import okay.rows.Op;

/** INPUT in direct style, answered by general handlers: one that declares it resumes once, one that does not */
public abstract sealed class Input extends Suspend {
  public static final class Next extends Input implements Op<Input, Integer, Next> {
    @Override
    public String toString() { return "Next"; }
  }

  public static int next() throws Input {
    return Direct.perform(new Next());
  }

  /** each answer the next of {@code values}: resumed once — declared */
  public static <A, R> Comp<A, R> once(Iterator<Integer> values, Comp<A, ? super Eff<Input, R>> p) {
    final class Feed implements Handler<Input, A, A, R>, Once {
      public Comp<A, R> ret(A a) { return pure(a); }
      public Comp<A, R> on(Input op, K<A, R> k) {
        return switch (op) {
          case Next n -> k.resume(n, values.next());
        };
      }
    }
    return handle(Input.class, new Feed(), p);
  }

  /** the same, not declared: its programs' frames are saved */
  public static <A, R> Comp<A, R> many(Iterator<Integer> values, Comp<A, ? super Eff<Input, R>> p) {
    return handle(Input.class, new Handler<Input, A, A, R>() {
      public Comp<A, R> ret(A a) { return pure(a); }
      public Comp<A, R> on(Input op, K<A, R> k) {
        return switch (op) {
          case Next n -> k.resume(n, values.next());
        };
      }
    }, p);
  }

  /** declared Once, and resumes twice: refused */
  public static <A, R> Comp<A, R> lying(Comp<A, ? super Eff<Input, R>> p) {
    final class Twice implements Handler<Input, A, A, R>, Once {
      public Comp<A, R> ret(A a) { return pure(a); }
      public Comp<A, R> on(Input op, K<A, R> k) {
        return switch (op) {
          case Next n -> k.resume(n, 1).flatMap(first -> k.resume(n, 2));
        };
      }
    }
    return handle(Input.class, new Twice(), p);
  }
}
