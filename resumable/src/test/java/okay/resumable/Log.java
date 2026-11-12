package okay.resumable;

import static okay.rows.Effects.handle;
import static okay.rows.Effects.pure;

import java.util.ArrayList;
import java.util.List;
import okay.rows.Cell;
import okay.rows.Comp;
import okay.rows.Eff;
import okay.rows.InPlace;
import okay.rows.Op;
import okay.rows.std.Writer;

/** A LOG in direct style, answered in place */
public abstract sealed class Log extends Suspend {
  public static final class Tell extends Log implements Op<Log, Void, Tell> {
    final String line;

    public Tell(String line) { this.line = line; }

    @Override
    public String toString() { return "Tell(" + line + ")"; }
  }

  public static void tell(String line) throws Log {
    Direct.perform(new Tell(line));
  }

  public static <A, R> Comp<Writer.Logged<String, A>, R> run(Comp<A, ? super Eff<Log, R>> p) {
    return handle(Log.class, new InPlace<Log, List<String>, A, Writer.Logged<String, A>, R>() {
      public Comp<Writer.Logged<String, A>, R> ret(List<String> log, A a) { return pure(new Writer.Logged<>(log, a)); }
      public Cell.Answered answer(Cell<List<String>> state, Log op) {
        return switch (op) {
          case Tell tell -> {
            List<String> more = new ArrayList<>(state.value);
            more.add(tell.line);
            state.value = more;
            yield state.answer(tell, null);
          }
        };
      }
    }, List.of(), p);
  }
}
