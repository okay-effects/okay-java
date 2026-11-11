package okay.rows.std;

import static okay.rows.Effects.*;

import java.util.List;
import okay.rows.Cell;
import okay.rows.Comp;
import okay.rows.Eff;
import okay.rows.InPlace;
import okay.rows.Op;

/** WRITER: values told, collected in order. */
public sealed interface Writer<W> {
  record Tell<W>(W value) implements Writer<W>, Op<Writer<W>, Void, Tell<W>> {}

  static <W> Comp<Void, Eff<Writer<W>, ?>> tell(W w) { return perform(new Tell<>(w)); }

  /** what was told, with the value */
  record Logged<W, A>(List<W> log, A value) {}

  static <W, A, R> Comp<Logged<W, A>, R> run(Comp<A, ? super Eff<Writer<W>, R>> p) {
    return handle(Writer.class, new InPlace<Writer<W>, Chain<W>, A, Logged<W, A>, R>() {
      public Comp<Logged<W, A>, R> ret(Chain<W> told, A a) { return pure(new Logged<>(Chain.list(told), a)); }
      public Cell.Answered answer(Cell<Chain<W>> told, Writer<W> op) {
        return switch (op) {
          case Tell<W> tell -> { told.value = new Chain<>(tell.value(), told.value); yield told.answer(tell, null); }
        };
      }
    }, null, p);
  }
}
