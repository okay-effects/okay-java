package okay.rows;

import static okay.rows.Effects.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import okay.rows.std.Choose;
import okay.rows.std.Reader;
import okay.rows.std.State;
import okay.rows.std.Throws;
import okay.rows.std.Trail;
import okay.rows.std.Writer;
import org.junit.jupiter.api.Test;

/** CONTROL AND AUDIT: okay-core's programs ({@code docs/intercept.md}, {@code TestIntercept}) in the row notation,
 * on okay-effects' Java names — a sensitive effect audited, a plain one intercepted and observed at its place, an
 * observer of every effect, the trail */
class AuditTest {

  /** a SENSITIVE effect: its operations are {@code Sensitive.Op}s */
  sealed interface Pay {
    record Transfer(String to, int amount) implements Pay, Sensitive.Op<Pay, String, Transfer> {}
    record Balance() implements Pay, Sensitive.Op<Pay, Integer, Balance> {}
  }

  static Comp<String, Sensitive<Pay, ?>> transfer(String to, int amount) {
    return Audit.perform(new Pay.Transfer(to, amount));
  }

  static Comp<Integer, Sensitive<Pay, ?>> balance() { return Audit.perform(new Pay.Balance()); }

  /** a program over payments, knowing nothing of the audit */
  static Comp<Integer, Sensitive<Pay, ?>> payments() {
    return transfer(" acct-1234 ", 100).flatMap(r -> balance())
        .flatMap(b -> transfer("acct-77", 5000).map(r -> b));
  }

  /** the bank: an ordinary handler in place, its state the balance — what makes it the sensitive effect's is
   * {@code Audit.handle}, the only one that takes the audited place */
  static <A, R> InPlace<Pay, Integer, A, A, R> bank() {
    return new InPlace<>() {
      public Comp<A, R> ret(Integer balance, A a) { return pure(a); }
      public Cell.Answered answer(Cell<Integer> balance, Pay op) {
        return switch (op) {
          case Pay.Transfer t -> {
            balance.value -= t.amount();
            yield balance.answer(t, "ok:" + t.to() + ":" + t.amount());
          }
          case Pay.Balance b -> balance.answer(b, balance.value);
        };
      }
    };
  }

  static String masked(String account) { return "****" + account.substring(account.length() - 4); }

  /** the policy: a limit, the account written one way, every payment told to the trail, masked */
  static <A> Interceptor<Pay, A, Eff<Throws<String>, Eff<Trail, Pure>>> guarded(int limit) {
    return (op, pass, k) -> switch (op) {
      case Pay.Transfer t when t.amount() > limit -> {
        String why = "refused: " + t.amount() + " to " + masked(t.to().trim().toUpperCase())
            + " over the limit " + limit;
        yield up(up(Trail.record(why))).flatMap(told -> k.unwind())
            .flatMap(left -> up(Throws.<String, A>raise(why)));
      }
      case Pay.Transfer t -> {
        String to = t.to().trim().toUpperCase();
        yield k.after(t, pass.pass(new Pay.Transfer(to, t.amount())).flatMap(receipt ->
            up(up(Trail.record("transfer " + t.amount() + " to " + masked(to) + " -> " + receipt)))
                .map(told -> receipt)));
      }
      case Pay.Balance b -> k.after(b, pass.pass(b).flatMap(n ->
          up(up(Trail.record("balance -> " + n))).map(told -> n)));
    };
  }

  /** an audit that passes every payment on as it is */
  static <A, R> Interceptor<Pay, A, R> passing() {
    return (op, pass, k) -> switch (op) {
      case Pay.Transfer t -> k.after(t, pass.pass(t));
      case Pay.Balance b -> k.after(b, pass.pass(b));
    };
  }

  @Test
  void anAuditOfPayments() {
    Comp<Integer, Audited<Pay, Eff<Throws<String>, Eff<Trail, Pure>>>> audited =
        Audit.audit(guarded(1000), payments());
    var got = run(Trail.keep(Throws.run(Audit.handle(bank(), 1000, audited))));
    String refused = "refused: 5000 to ****T-77 over the limit 1000";
    assertEquals(new Trail.Kept<>(List.of("transfer 100 to ****1234 -> ok:ACCT-1234:100", "balance -> 900",
        refused), new Throws.Err<String, Integer>(refused)), got);
  }

  static List<String> log = new ArrayList<>();

  static <R> Comp<Void, R> said(String what) {
    return defer(() -> {
      log.add(what);
      return pure(null);
    });
  }

  @Test
  void anInterceptorGoesOnByAfterSoAHandlerThatLeavesUnwindsTheBody() {
    Comp<Integer, Sensitive<Pay, ?>> body = transfer("acct", 1).map(r -> 1).ensuring(() -> said("closed"));
    // the bank leaves: what waits for its answer is unwound, the body's rest with it, through `after`
    Handler<Pay, Integer, Integer, Pure> closed = new Handler<>() {
      public Comp<Integer, Pure> ret(Integer a) { return pure(a); }
      public Comp<Integer, Pure> on(Pay op, K<Integer, Pure> k) { return k.unwind().then(() -> pure(-1)); }
    };
    log = new ArrayList<>();
    assertEquals(-1, run(Audit.handle(closed, Audit.audit(passing(), body))));
    assertEquals(List.of("closed"), log);
    // passed on by a plain bind, the rest is neither resumed nor unwound: its finalizer does not run
    Interceptor<Pay, Integer, Pure> bound = (op, pass, k) -> switch (op) {
      case Pay.Transfer t -> pass.pass(t).flatMap(r -> k.resume(t, r));
      case Pay.Balance b -> pass.pass(b).flatMap(r -> k.resume(b, r));
    };
    log = new ArrayList<>();
    assertEquals(-1, run(Audit.handle(closed, Audit.audit(bound, body))));
    assertEquals(List.of(), log);
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
          case Writer.Tell<String> tell -> k.after(tell, Writer.tell(tell.value().toUpperCase()));
        };
      }
    }, under(p));
  }

  @Test
  void aPlainEffectObservedAtItsPlace() {
    Comp<Integer, Eff<State<Integer>, Eff<Trail, Pure>>> p =
        State.<Integer>modify(s -> s + 1).flatMap(done -> State.<Integer>get());
    var got = run(Trail.keep(State.run(5, Effects.observe(State.class, op -> up(Trail.record(op.toString())), p))));
    assertEquals(new Trail.Kept<>(List.of("Get[]", "Put[value=6]", "Get[]"), new State.Final<>(6, 6)), got);
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
  void anObserverOfEveryEffectButTheSensitiveOnes() {
    Comp<String, Eff<Reader<Integer>, Eff<State<Integer>, Sensitive<Pay, Eff<Trail, Pure>>>>> p =
        Reader.<Integer>ask().flatMap(n -> up(State.<Integer>modify(s -> s + n)).flatMap(done ->
            up(State.<Integer>get()).flatMap(s -> up(up(transfer("acct", s))))));
    Comp<String, Eff<Reader<Integer>, Eff<State<Integer>, Sensitive<Pay, Eff<Trail, Pure>>>>> observed =
        Observer.observe(telling4(), p);
    Comp<State.Final<Integer, String>, Sensitive<Pay, Eff<Trail, Pure>>> seen =
        State.run(1, Reader.run(5, observed));
    var got = run(Trail.keep(Audit.handle(bank(), 100, Audit.audit(passing(), seen))));
    // the payment passed the observer unseen: only its audit sees it
    assertEquals(new Trail.Kept<>(List.of("Reader Ask[]", "  -> 5", "State Get[]", "  -> 1",
        "State Put[value=6]", "  -> null", "State Get[]", "  -> 6"), new State.Final<>(6, "ok:acct:6")), got);
  }

  @Test
  void aPolicyOverEveryEffect() {
    // a read-only run: every put refused, every other operation passed on
    Observer<Integer, Integer, Eff<State<Integer>, Eff<Throws<String>, Pure>>> readOnly = new Observer<>() {
      public Comp<Integer, Eff<State<Integer>, Eff<Throws<String>, Pure>>> ret(Integer a) { return pure(a); }
      public <X> Comp<Integer, Eff<State<Integer>, Eff<Throws<String>, Pure>>> on(
          Observer.Seen<X, Integer, Eff<State<Integer>, Eff<Throws<String>, Pure>>> seen) {
        if (!(seen.op() instanceof State.Put<?>)) return seen.proceed().flatMap(seen::resume);
        return seen.unwind().flatMap(left -> up(Throws.raise("read-only: " + seen.describe() + " refused")));
      }
    };
    Comp<Integer, Eff<State<Integer>, Eff<Throws<String>, Pure>>> p =
        State.<Integer>get().flatMap(s -> State.put(s + 1).flatMap(done -> State.get()));
    assertEquals(new Throws.Err<>("read-only: Put[value=1] refused"),
        run(Throws.run(State.run(0, Observer.observe(readOnly, p)))));
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
    var got = run(Trail.keep(Choose.all(Observer.observe(telling, Choose.among(List.of(true, false))))));
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
        run(Trail.keep(State.run(7, Observer.observe(telling, counted)))));
  }

  @Test
  void aSensitiveOperationAtTheTopIsNotHandedOut() {
    // `step` hands out what no handler answers; a sensitive operation it does not: only its audit sees it
    assertThrows(NoHandler.class, () -> step(transfer("acct", 1)));
  }
}
