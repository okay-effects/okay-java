import java.util.List;
import okay.resumable.Counter;
import okay.resumable.Direct;
import okay.resumable.Log;
import okay.resumable.Pick;

/** a direct-style clause that performs an effect not in the rest of the row, outside its handler; and one that
 * resumes the rest inside a JDK lambda, which cannot suspend */
class HandlerUndeclared {
  static Direct.Handler1<Counter, Integer, Integer, Log> outsideTheRow() {
    return new Direct.Handler1<>() {
      public Integer ret(Integer a) { return a; }
      public Integer on(Counter op, Direct.K<Integer> k) throws Log, Direct.Resumes {
        Log.tell("seen");
        int x = Pick.among(1, 2); // refused
        return switch (op) {
          case Counter.Get get -> k.resume(get, x);
          case Counter.Put put -> k.resume(put, null);
        };
      }
    };
  }

  static Direct.Handler0<Pick, Integer, Integer> inALambda() {
    return new Direct.Handler0<>() {
      public Integer ret(Integer a) { return a; }
      public Integer on(Pick op, Direct.K<Integer> k) throws Direct.Resumes {
        return switch (op) {
          case Pick.Among among -> List.of(1, 2).stream().mapToInt(v -> k.resume(among, v)).sum(); // refused
        };
      }
    };
  }
}
