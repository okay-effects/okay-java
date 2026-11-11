package okay.rows;

import static okay.rows.Effects.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import okay.rows.std.Choose;
import okay.rows.std.Reader;
import okay.rows.std.State;
import okay.rows.std.Throws;
import okay.rows.std.Trail;
import okay.rows.std.Writer;
import org.junit.jupiter.api.Test;

/** CONTROL AND AUDIT: okay-core's programs ({@code docs/intercept.md}, {@code TestIntercept}) in the row notation —
 * a sensitive effect audited, a plain one intercepted, an observer of every effect, the trail */
class AuditTest {

  /** a SENSITIVE effect: its operations are {@code Audited.Op}s */
  sealed interface Pay {
    record Transfer(String to, int amount) implements Pay, Audited.Op<Pay, String, Transfer> {}
    record Balance() implements Pay, Audited.Op<Pay, Integer, Balance> {}
  }

  static Comp<String, Eff<Pay, ?>> transfer(String to, int amount) {
    return Audit.perform(new Pay.Transfer(to, amount));
  }

  static Comp<Integer, Eff<Pay, ?>> balance() { return Audit.perform(new Pay.Balance()); }

  /** a program over payments, knowing nothing of the audit */
  static Comp<Integer, Eff<Pay, ?>> payments() {
    return transfer(" acct-1234 ", 100).flatMap(r -> balance())
        .flatMap(b -> transfer("acct-77", 5000).map(r -> b));
  }

  /** the bank: an ordinary handler of payments — what makes it the sensitive effect's is {@code Audit.handle} */
  static <A, R> Audit.Handler<Pay, A, A, R> bank(int start) {
    int[] balance = {start};
    return new Audit.Handler<>() {
      public Comp<A, R> ret(A a) { return pure(a); }
      public Comp<A, R> on(Pay op, Audited.Rest<Pay, A, R> k) {
        return switch (op) {
          case Pay.Transfer t -> {
            balance[0] -= t.amount();
            yield k.resume(t, "ok:" + t.to() + ":" + t.amount());
          }
          case Pay.Balance b -> k.resume(b, balance[0]);
        };
      }
    };
  }

  static String masked(String account) { return "****" + account.substring(account.length() - 4); }

  /** the row outside the audit: the payments audited, a failure, the trail */
  static <A> Interceptor<Pay, A, A, Eff<Audited<Pay>, Eff<Throws<String>, Eff<Trail, Pure>>>> guarded(int limit) {
    return new Interceptor<>() {
      public Comp<A, Eff<Audited<Pay>, Eff<Throws<String>, Eff<Trail, Pure>>>> ret(A a) { return pure(a); }
      public Comp<A, Eff<Audited<Pay>, Eff<Throws<String>, Eff<Trail, Pure>>>> on(Pay op, Interceptor.Pass<Pay> pass,
          Audited.Rest<Pay, A, Eff<Audited<Pay>, Eff<Throws<String>, Eff<Trail, Pure>>>> k) {
        return switch (op) {
          case Pay.Transfer t when t.amount() > limit -> {
            // the policy: refused, told, the rest left, failed
            String why = "refused: " + t.amount() + " to " + masked(t.to().trim().toUpperCase())
                + " over the limit " + limit;
            yield up(up(Trail.record(why))).flatMap(told -> k.unwind())
                .flatMap(left -> up(Throws.<String, A>raise(why)));
          }
          case Pay.Transfer t -> {
            String to = t.to().trim().toUpperCase(); // the account written one way: changed
            yield pass.pass(new Pay.Transfer(to, t.amount())).flatMap(receipt ->
                up(up(Trail.record("transfer " + t.amount() + " to " + masked(to) + " -> " + receipt)))
                    .flatMap(told -> k.resume(t, receipt)));
          }
          case Pay.Balance b -> pass.pass(b).flatMap(n ->
              up(up(Trail.record("balance -> " + n))).flatMap(told -> k.resume(b, n)));
        };
      }
    };
  }

  /** an audit that passes every payment on as it is */
  static <A, T extends Eff<Audited<Pay>, ?>> Interceptor<Pay, A, A, T> passing() {
    return new Interceptor<>() {
      public Comp<A, T> ret(A a) { return pure(a); }
      public Comp<A, T> on(Pay op, Interceptor.Pass<Pay> pass, Audited.Rest<Pay, A, T> k) {
        return switch (op) {
          case Pay.Transfer t -> pass.pass(t).flatMap(r -> k.resume(t, r));
          case Pay.Balance b -> pass.pass(b).flatMap(r -> k.resume(b, r));
        };
      }
    };
  }

  @Test
  void anAuditOfPayments() {
    Comp<Integer, Eff<Audited<Pay>, Eff<Throws<String>, Eff<Trail, Pure>>>> audited =
        Audit.audit(Pay.class, guarded(1000), payments());
    var got = run(Trail.keep(Throws.run(Audit.handle(Pay.class, bank(1000), audited))));
    String refused = "refused: 5000 to ****T-77 over the limit 1000";
    assertEquals(new Trail.Kept<>(List.of("transfer 100 to ****1234 -> ok:ACCT-1234:100", "balance -> 900",
        refused), new Throws.Err<String, Integer>(refused)), got);
  }

  @Test
  void aPlainEffectInterceptedAtItsPlace() {
    Comp<Void, Eff<Writer<String>, Pure>> p = Writer.<String>tell("a").flatMap(told -> shouting(Writer.tell("b")));
    assertEquals(new Writer.Logged<>(List.of("a", "B"), null), run(Writer.run(p)));
  }

  static <A, T> Comp<A, Eff<Writer<String>, T>> shouting(Comp<A, ? super Eff<Writer<String>, T>> p) {
    return handle(Writer.class, new Handler<Writer<String>, A, A, Eff<Writer<String>, T>>() {
      public Comp<A, Eff<Writer<String>, T>> ret(A a) { return pure(a); }
      public Comp<A, Eff<Writer<String>, T>> on(Writer<String> op, K<A, Eff<Writer<String>, T>> k) {
        return switch (op) {
          case Writer.Tell<String> tell -> Writer.tell(tell.value().toUpperCase()).flatMap(d -> k.resume(tell, d));
        };
      }
    }, under(p));
  }

  /** every operation and its answer, told to the trail, the fourth place of the row */
  static <A, R extends Eff<?, ? extends Eff<?, ? extends Eff<?, ? extends Eff<Trail, ?>>>>>
      Observer<A, A, R> telling4() {
    return new Observer<>() {
      public Comp<A, R> ret(A a) { return pure(a); }
      public <X> Comp<A, R> on(Observer.Seen<X, A, R> seen) {
        return up(up(up(Trail.record(seen.effect() + " " + seen.describe())))).flatMap(told -> seen.proceed())
            .flatMap(x -> up(up(up(Trail.record("  -> " + x)))).flatMap(told -> seen.resume(x)));
      }
    };
  }

  @Test
  void anObserverOfEveryEffect() {
    Comp<String, Eff<Reader<Integer>, Eff<State<Integer>, Eff<Pay, Eff<Trail, Pure>>>>> p =
        Reader.<Integer>ask().flatMap(n -> up(State.<Integer>modify(s -> s + n)).flatMap(done ->
            up(State.<Integer>get()).flatMap(s -> up(up(transfer("acct", s))))));
    Comp<String, Eff<Reader<Integer>, Eff<State<Integer>, Eff<Pay, Eff<Trail, Pure>>>>> observed =
        Audit.observe(telling4(), p);
    Comp<State.Final<Integer, String>, Eff<Pay, Eff<Trail, Pure>>> seen = State.run(1, Reader.run(5, observed));
    var got = run(Trail.keep(Audit.handle(Pay.class, bank(100), Audit.audit(Pay.class,
        AuditTest.<State.Final<Integer, String>, Eff<Audited<Pay>, Eff<Trail, Pure>>>passing(), under(seen)))));
    assertEquals(new Trail.Kept<>(List.of("Reader Ask[]", "  -> 5", "State Get[]", "  -> 1",
        "State Put[value=6]", "  -> null", "State Get[]", "  -> 6", "Pay Transfer[to=acct, amount=6]",
        "  -> ok:acct:6"), new State.Final<>(6, "ok:acct:6")), got);
  }

  @Test
  void aPolicyOverEveryEffect() {
    // a read-only run: every operation but a payment passes
    Observer<Integer, Integer, Eff<State<Integer>, Eff<Pay, Eff<Throws<String>, Pure>>>> readOnly = new Observer<>() {
      public Comp<Integer, Eff<State<Integer>, Eff<Pay, Eff<Throws<String>, Pure>>>> ret(Integer a) { return pure(a); }
      public <X> Comp<Integer, Eff<State<Integer>, Eff<Pay, Eff<Throws<String>, Pure>>>> on(
          Observer.Seen<X, Integer, Eff<State<Integer>, Eff<Pay, Eff<Throws<String>, Pure>>>> seen) {
        if (!seen.effect().equals("Pay")) return seen.proceed().flatMap(seen::resume);
        return seen.unwind().flatMap(left -> up(up(Throws.raise("read-only: " + seen.describe() + " refused"))));
      }
    };
    Comp<Integer, Eff<State<Integer>, Eff<Pay, Eff<Throws<String>, Pure>>>> p =
        State.<Integer>modify(s -> s + 1).flatMap(done -> up(transfer("acct", 1)).flatMap(r -> State.get()));
    var got = run(Throws.run(Audit.handle(Pay.class, bank(0),
        Audit.audit(Pay.class,
            AuditTest.<State.Final<Integer, Integer>, Eff<Audited<Pay>, Eff<Throws<String>, Pure>>>passing(),
            under(State.run(0, Audit.observe(readOnly, p)))))));
    assertEquals(new Throws.Err<>("read-only: Transfer[to=acct, amount=1] refused"), got);
  }

  @Test
  void anAnswerIsToldEachTimeTheRestGetsIt() {
    Observer<Boolean, Boolean, Eff<Choose, Eff<Trail, Pure>>> telling = new Observer<>() {
      public Comp<Boolean, Eff<Choose, Eff<Trail, Pure>>> ret(Boolean a) { return pure(a); }
      public <X> Comp<Boolean, Eff<Choose, Eff<Trail, Pure>>> on(
          Observer.Seen<X, Boolean, Eff<Choose, Eff<Trail, Pure>>> seen) {
        return up(Trail.record(seen.effect() + " " + seen.describe())).flatMap(told -> seen.proceed())
            .flatMap(x -> up(Trail.record("  -> " + x)).flatMap(told -> seen.resume(x)));
      }
    };
    var got = run(Trail.keep(Choose.all(Audit.observe(telling, Choose.among(List.of(true, false))))));
    assertEquals(new Trail.Kept<>(List.of("Choose Among[values=[true, false]]", "  -> true", "  -> false"),
        List.of(true, false)), got);
  }

  /** a program of the capability notation, its state the row's: observed as any row program */
  static <M> okay.core.Eff<M, Integer> counter(okay.std.State<M, Integer> st) {
    return st.modify(x -> x + 1).then(st.get());
  }

  @Test
  void aCapabilityProgramObservedThroughTheBridge() {
    Observer<Integer, Integer, Eff<State<Integer>, Eff<Trail, Pure>>> telling = new Observer<>() {
      public Comp<Integer, Eff<State<Integer>, Eff<Trail, Pure>>> ret(Integer a) { return pure(a); }
      public <X> Comp<Integer, Eff<State<Integer>, Eff<Trail, Pure>>> on(
          Observer.Seen<X, Integer, Eff<State<Integer>, Eff<Trail, Pure>>> seen) {
        return up(Trail.record(seen.describe())).flatMap(told -> seen.proceed()).flatMap(seen::resume);
      }
    };
    Comp<Integer, Eff<State<Integer>, Eff<Trail, Pure>>> counted =
        fit(Bridge.<Integer, Integer>state(AuditTest::counter));
    assertEquals(new Trail.Kept<>(List.of("Get[]", "Put[value=8]", "Get[]"), new State.Final<>(8, 8)),
        run(Trail.keep(State.run(7, Audit.observe(telling, counted)))));
  }

  @Test
  void aSensitiveOperationAtTheTopIsNotHandedOut() {
    // `step` hands out what no handler answers; a sensitive operation it refuses: none answers it but its audit
    assertThrows(IllegalStateException.class, () -> step(transfer("acct", 1)));
  }
}
