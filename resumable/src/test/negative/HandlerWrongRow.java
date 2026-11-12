import static okay.rows.Effects.*;

import okay.resumable.Counter;
import okay.resumable.Direct;
import okay.resumable.Log;
import okay.resumable.Pick;
import okay.rows.Comp;
import okay.rows.Eff;
import okay.rows.Handler;
import okay.rows.Pure;

/** a direct-style clause that declares an effect outside its row; a handler whose clauses perform an effect,
 * installed where the row outside it does not have that effect next */
class HandlerWrongRow {
  static Direct.Handler1<Counter, Integer, Integer, Log> declared() {
    return new Direct.Handler1<>() {
      public Integer ret(Integer a) { return a; }
      public Integer on(Counter op, Direct.K<Integer> k) throws Log, Pick, Direct.Resumes { // refused
        return 0;
      }
    };
  }

  static Direct.Handler1<Counter, Integer, Integer, Log> logging() {
    return new Direct.Handler1<>() {
      public Integer ret(Integer a) { return a; }
      public Integer on(Counter op, Direct.K<Integer> k) throws Log, Direct.Resumes {
        Log.tell("seen");
        return switch (op) {
          case Counter.Get get -> k.resume(get, 0);
          case Counter.Put put -> k.resume(put, null);
        };
      }
    };
  }

  public static void main(String[] args) {
    Comp<Integer, Eff<Counter, Eff<Log, Pure>>> withLog = Direct.row(Counter.class, Log.class).of(Counter::get);
    Comp<Integer, Eff<Counter, Pure>> without = Direct.row(Counter.class).of(Counter::get);
    // the row outside the handler has the log next: this line is here to show that the ones after it are refused
    Comp<Integer, Eff<Log, Pure>> right = handle(Counter.class, Direct.row(Log.class).handler(logging()), withLog);
    Comp<Integer, Pure> none = handle(Counter.class, Direct.row(Log.class).handler(logging()), without); // refused
    Handler<Counter, Integer, Integer, Eff<Pick, Pure>> wrong = Direct.row(Pick.class).handler(logging()); // refused
    Handler<Counter, Integer, Integer, Pure> silent = Direct.handler(logging()); // refused
  }
}
