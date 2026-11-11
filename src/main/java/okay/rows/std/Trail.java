package okay.rows.std;

import static okay.rows.Effects.*;

import java.util.List;
import okay.rows.Cell;
import okay.rows.Comp;
import okay.rows.Eff;
import okay.rows.InPlace;
import okay.rows.Op;

/** AN AUDIT TRAIL: lines recorded in order. An ordinary effect, handled outside the interceptors that record to
 * it: the lines are a value beside the program's, and another handler may send them anywhere. */
public sealed interface Trail {
  record Record(String line) implements Trail, Op<Trail, Void, Record> {}

  static Comp<Void, Eff<Trail, ?>> record(String line) { return perform(new Record(line)); }

  /** the lines a program recorded, and its value */
  record Kept<A>(List<String> lines, A value) {}

  /** the lines kept in place: outside a handler that resumes many times one trail through all paths, inside it
   * each path's own */
  static <A, R> Comp<Kept<A>, R> keep(Comp<A, ? super Eff<Trail, R>> p) {
    return handle(Trail.class, new InPlace<Trail, Chain<String>, A, Kept<A>, R>() {
      public Comp<Kept<A>, R> ret(Chain<String> lines, A a) { return pure(new Kept<>(Chain.list(lines), a)); }
      public Cell.Answered answer(Cell<Chain<String>> lines, Trail op) {
        return switch (op) {
          case Record record -> {
            lines.value = new Chain<>(record.line(), lines.value);
            yield lines.answer(record, null);
          }
        };
      }
    }, null, p);
  }
}
