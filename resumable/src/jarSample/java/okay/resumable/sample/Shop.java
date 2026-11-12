package okay.resumable.sample;

import static okay.rows.Effects.handle;
import static okay.rows.Effects.pure;
import static okay.rows.Effects.run;

import java.util.ArrayList;
import java.util.List;
import okay.resumable.Direct;
import okay.resumable.Suspend;
import okay.rows.Comp;
import okay.rows.Eff;
import okay.rows.Handler;
import okay.rows.K;
import okay.rows.Op;

/** A direct-style program in a jar built WITHOUT the transformation: {@code java -jar} transforms it as it loads,
 * through the manifest's Launcher-Agent-Class */
public final class Shop {
  private Shop() {}

  public abstract static sealed class Size extends Suspend {
    public static final class Choose extends Size implements Op<Size, String, Choose> {}
  }

  static String order(List<String> made) throws Size {
    made.add("order");
    String size = Direct.perform(new Size.Choose());
    return "coffee " + size;
  }

  static <A, R> Comp<List<A>, R> everySize(Comp<A, ? super Eff<Size, R>> p) {
    return handle(Size.class, new Handler<Size, A, List<A>, R>() {
      public Comp<List<A>, R> ret(A a) { return pure(List.of(a)); }
      public Comp<List<A>, R> on(Size op, K<List<A>, R> k) {
        return switch (op) {
          case Size.Choose c -> k.resume(c, "small").flatMap(small -> k.resume(c, "large").map(large -> {
            List<A> both = new ArrayList<>(small);
            both.addAll(large);
            return both;
          }));
        };
      }
    }, p);
  }

  public static void main(String[] args) {
    List<String> made = new ArrayList<>();
    System.out.println(run(everySize(Direct.row(Size.class).of(() -> order(made)))) + " " + made);
  }
}
