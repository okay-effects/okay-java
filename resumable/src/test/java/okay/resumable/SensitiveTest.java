package okay.resumable;

import static okay.rows.Effects.fit;
import static okay.rows.Effects.run;
import static okay.rows.Effects.up;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import okay.rows.Audit;
import okay.rows.Audited;
import okay.rows.Comp;
import okay.rows.Eff;
import okay.rows.Interceptor;
import okay.rows.Once;
import okay.rows.Pure;
import okay.rows.Sensitive;
import okay.rows.std.Pair;
import okay.rows.std.Trail;
import okay.rows.std.Writer;
import org.junit.jupiter.api.Test;

/** Sensitive effects in direct style: performed by plain methods, answered under their audit only — okay.rows'
 * {@code Audits} scenario with the program written in direct style. javac's refusals are
 * {@code src/test/negative/SensitiveDirect.java} */
class SensitiveTest {

  /** two transfers, a balance, a transfer over the limit; the finally block is the frames' own */
  static int payments(List<String> log) throws Pay {
    try {
      Pay.transfer("a", 100);
      Pay.transfer("b", 500);
      int b = Pay.balance();
      Pay.transfer("c", 5000);
      return b;
    } finally {
      log.add("finally");
    }
  }

  static Comp<Integer, Sensitive<Pay, ?>> program(List<String> log) {
    return Direct.row(Direct.sensitive(Pay.class)).of(() -> payments(log));
  }

  /** a line recorded to the trail, outside the audit */
  static <R extends Eff<?, ? extends Eff<Trail, ?>>> Comp<Void, R> note(String line) {
    return fit(up(Trail.record(line)));
  }

  /** the policy: every operation recorded when asked and when answered; a transfer over 200 made one of 200; one
   * over 1000 refused, the program left with -1 */
  static Comp<Integer, Audited<Pay, Eff<Trail, ?>>> policy(Pay op, okay.rows.Pass<Pay, Eff<Trail, ?>> pass,
      okay.rows.K<Integer, Audited<Pay, Eff<Trail, ?>>> k) {
    return switch (op) {
      case Pay.Transfer t when t.amount > 1000 -> SensitiveTest.<Audited<Pay, Eff<Trail, ?>>>note(
          "transfer " + t.amount + " to " + t.to + " refused").flatMap(d -> k.unwind().map(left -> -1));
      case Pay.Transfer t -> {
        Pay.Transfer passed = t.amount > 200 ? new Pay.Transfer(t.to, 200) : t;
        String asked = "transfer " + t.amount + " to " + t.to + (passed == t ? "" : " as 200");
        yield SensitiveTest.<Audited<Pay, Eff<Trail, ?>>>note(asked).flatMap(d -> k.after(t, pass.pass(passed)
            .flatMap(x -> SensitiveTest.<Audited<Pay, Eff<Trail, ?>>>note("→ " + x).map(e -> x))));
      }
      case Pay.Balance b -> SensitiveTest.<Audited<Pay, Eff<Trail, ?>>>note("balance").flatMap(d -> k.after(b,
          pass.pass(b).flatMap(x -> SensitiveTest.<Audited<Pay, Eff<Trail, ?>>>note("→ " + x).map(e -> x))));
    };
  }

  /** the same policy, declared to resume at most once */
  static final class OncePolicy implements Interceptor<Pay, Integer, Eff<Trail, ?>>, Once {
    public Comp<Integer, Audited<Pay, Eff<Trail, ?>>> on(Pay op, okay.rows.Pass<Pay, Eff<Trail, ?>> pass,
        okay.rows.K<Integer, Audited<Pay, Eff<Trail, ?>>> k) {
      return policy(op, pass, k);
    }
  }

  static final Trail.Kept<Pair<Integer, Integer>> EXPECTED = new Trail.Kept<>(List.of(
      "transfer 100 to a", "→ ok:a:100", "transfer 500 to b as 200", "→ ok:b:200", "balance", "→ 700",
      "transfer 5000 to c refused"), new Pair<>(700, -1));

  @Test
  void auditedTheTrailCompleteOneRewrittenOneRefusedTheFramesUnwound() {
    List<String> log = new ArrayList<>();
    // the policy may resume more than once: the frames are saved at each operation
    assertEquals(List.of(0, 0, 0, 4), TiersTest.taken(() -> assertEquals(EXPECTED,
        run(Trail.keep(Pay.bank(1000, Audit.audit(SensitiveTest::policy, program(log))))))));
    assertEquals(List.of("finally"), log);
  }

  @Test
  void anAuditDeclaredOnceYieldsAndIsUnwoundOnTheJvmsContinuation() {
    List<String> log = new ArrayList<>();
    // the audit's clause called from the body's stack does not resume at once (it records first): the body yields
    assertEquals(List.of(0, 0, 4, 0), TiersTest.taken(() -> assertEquals(EXPECTED,
        run(Trail.keep(Pay.bank(1000, Audit.audit(new OncePolicy(), program(log))))))));
    assertEquals(List.of("finally"), log);
  }

  static int loggedPayment() throws Pay, Log {
    Log.tell("paying");
    String r = Pay.transfer("a", 10);
    Log.tell(r);
    return Pay.balance();
  }

  @Test
  void neverAnsweredInPlaceThoughItsHandlerIsThePlainOnesOfTheRowAre() {
    Interceptor<Pay, Integer, Eff<Log, Pure>> passing = (op, pass, k) -> switch (op) {
      case Pay.Transfer t -> k.after(t, pass.pass(t));
      case Pay.Balance b -> k.after(b, pass.pass(b));
    };
    Comp<Integer, Sensitive<Pay, ? extends Eff<Log, ?>>> p =
        Direct.row(Direct.sensitive(Pay.class), Log.class).of(SensitiveTest::loggedPayment);
    // the bank is in place, yet its operations pass the audit: saved frames; the log's are answered in place
    assertEquals(List.of(2, 0, 0, 2), TiersTest.taken(() -> assertEquals(
        new Writer.Logged<>(List.of("paying", "ok:a:10"), new Pair<>(90, 90)),
        run(Log.run(Pay.bank(100, Audit.audit(passing, p)))))));
  }

  @Test
  void aRawClassCannotPutASensitiveEffectAtAPlainPlace() {
    @SuppressWarnings({"unchecked", "rawtypes"}) // the hole the test is about: a raw Class passes javac
    Class<Suspend> raw = (Class) Pay.class;
    assertThrows(IllegalArgumentException.class, () -> Direct.row(raw));
  }
}
