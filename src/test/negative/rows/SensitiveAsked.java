import static okay.rows.Effects.*;

import okay.rows.Audit;
import okay.rows.Audited;
import okay.rows.Comp;
import okay.rows.Eff;
import okay.rows.Op;
import okay.rows.Pass;
import okay.rows.Pure;
import okay.rows.Sensitive;

/** A sensitive operation asked past its audit: by {@code perform}, which takes no sensitive operation; at the
 * audited place, which only an audit's {@code Pass} asks at, and only an audit makes one. */
final class SensitiveAsked {
  sealed interface Pay {
    record Transfer(String to, int amount) implements Pay, Sensitive.Op<Pay, String, Transfer> {}
  }

  static Comp<String, Sensitive<Pay, ?>> transfer() {
    // asked as a sensitive operation: this line is here to show that the ones after it are refused for theirs
    return Audit.perform(new Pay.Transfer("a", 1));
  }

  static Comp<String, Eff<Pay, ?>> plain() {
    return perform(new Pay.Transfer("a", 1)); // refused: perform takes no sensitive operation
  }

  static Comp<String, Audited<Pay, Pure>> past() {
    Pass<Pay, Pure> forged = new Pass<>(); // refused: only an audit makes a Pass
    return forged.pass(new Pay.Transfer("a", 1));
  }
}
