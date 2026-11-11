package okay.rows;

import static okay.rows.Effects.*;
import static okay.rows.Properties.same;
import static okay.rows.Properties.test;

import java.util.ArrayList;
import java.util.List;
import okay.rows.std.Choose;
import okay.rows.std.Pair;
import okay.rows.std.State;
import okay.rows.std.Throws;
import okay.rows.std.Trail;

/** Control and audit: observe, the trail, sensitive effects — the tests of Scala's {@code Audits}, named alike, the
 * trails line for line. Scala's and Rust's audit the standard {@code State}. Here the auditor is an interceptor by
 * class, and a class does not tell {@code State<Integer>} from {@code State<String>} (negative/WrongIntercept.java):
 * so the state audited is {@code Counter}, a state of integers whose class is its effect. */
final class Audits {
  private Audits() {}

  /** a state of integers, with no type arguments */
  sealed interface Counter {
    record Get() implements Counter, Op<Counter, Integer, Get> {}
    record Put(int value) implements Counter, Op<Counter, Void, Put> {}
  }

  static Comp<Integer, Eff<Counter, ?>> get() { return perform(new Counter.Get()); }
  static Comp<Void, Eff<Counter, ?>> put(int value) { return perform(new Counter.Put(value)); }

  static <A, R> Comp<State.Final<Integer, A>, R> counted(int s0, Comp<A, ? super Eff<Counter, R>> p) {
    return handle(Counter.class, new InPlace<Counter, Integer, A, State.Final<Integer, A>, R>() {
      public Comp<State.Final<Integer, A>, R> ret(Integer s, A a) { return pure(new State.Final<>(s, a)); }
      public Cell.Answered answer(Cell<Integer> state, Counter op) {
        return switch (op) {
          case Counter.Get get -> state.answer(get, state.value);
          case Counter.Put put -> { state.value = put.value(); yield state.answer(put, null); }
        };
      }
    }, s0, p);
  }

  /** a line recorded to the trail, in a row that has it second */
  static <R extends Eff<?, ? extends Eff<Trail, ?>>> Comp<Void, R> note(String line) {
    return fit(up(Trail.record(line)));
  }

  /** an operation of the counter asked again, of the handler outside */
  static <R extends Eff<Counter, ?>, X, O extends Op<Counter, X, ? super O>> Comp<X, R> proceed(O op) {
    return fit(perform(op));
  }

  /** the counter's operations, each recorded when it is asked and when it is answered: `who` says which auditor */
  static <A, R extends Eff<Counter, ? extends Eff<Trail, ?>>> Comp<A, R> recording(String who, Comp<A, ? super R> p) {
    return intercept(Counter.class, new Handler<Counter, A, A, R>() {
      public Comp<A, R> ret(A a) { return pure(a); }
      public Comp<A, R> on(Counter op, K<A, R> k) {
        return switch (op) {
          case Counter.Get get -> Audits.<R>note(who + "get").flatMap(d -> k.after(get,
              Audits.<R, Integer, Counter.Get>proceed(get)
                  .flatMap(x -> Audits.<R>note(who + "get → " + x).map(e -> x))));
          case Counter.Put put -> Audits.<R>note(who + "put " + put.value()).flatMap(d -> k.after(put,
              Audits.<R, Void, Counter.Put>proceed(put)
                  .flatMap(x -> Audits.<R>note(who + "put " + put.value() + " → done").map(e -> x))));
        };
      }
    }, p);
  }

  static <R> Comp<Integer, Eff<Counter, R>> counting() {
    return get().flatMap(s -> put(s + 1)).flatMap(d -> get());
  }

  // ---- a sensitive effect

  sealed interface Pay {
    record Transfer(String to, int amount) implements Pay, Sensitive.Op<Pay, String, Transfer> {}
    record Balance() implements Pay, Sensitive.Op<Pay, Integer, Balance> {}
  }

  static <A, R> Comp<Pair<Integer, A>, R> bank(int start, Comp<A, ? super Audited<Pay, R>> p) {
    return Audit.handle(new InPlace<Pay, Integer, A, Pair<Integer, A>, R>() {
      public Comp<Pair<Integer, A>, R> ret(Integer s, A a) { return pure(new Pair<>(s, a)); }
      public Cell.Answered answer(Cell<Integer> balance, Pay op) {
        return switch (op) {
          case Pay.Transfer t -> {
            balance.value -= t.amount();
            yield balance.answer(t, "ok:" + t.to() + ":" + t.amount());
          }
          case Pay.Balance b -> balance.answer(b, balance.value);
        };
      }
    }, start, p);
  }

  static Comp<Integer, Sensitive<Pay, ?>> payments() {
    return Audit.perform(new Pay.Transfer("a", 100)).flatMap(r -> Audit.perform(new Pay.Balance()))
        .flatMap(b -> Audit.perform(new Pay.Transfer("b", 50)).map(r -> b));
  }

  /** every operation passed on, recorded when asked and when answered */
  static Interceptor<Pay, Integer, Eff<Trail, ?>> recorded() {
    return (op, pass, k) -> switch (op) {
      case Pay.Transfer t -> {
        String asked = "transfer " + t.amount() + " to " + t.to();
        yield Audits.<Audited<Pay, Eff<Trail, ?>>>note(asked).flatMap(d ->
            k.after(t, pass.pass(t).flatMap(x -> Audits.<Audited<Pay, Eff<Trail, ?>>>note(asked + " → " + x)
                .map(e -> x))));
      }
      case Pay.Balance b -> Audits.<Audited<Pay, Eff<Trail, ?>>>note("balance").flatMap(d ->
          k.after(b, pass.pass(b).flatMap(x -> Audits.<Audited<Pay, Eff<Trail, ?>>>note("balance → " + x)
              .map(e -> x))));
    };
  }

  static Comp<Integer, Sensitive<Pay, ?>> balances(int n, int sum) {
    return n == 0 ? pure(sum) : Audit.perform(new Pay.Balance()).flatMap(b -> balances(n - 1, sum + b));
  }

  // ---- a ledger that leaves at a deposit below nothing

  sealed interface Ledger {
    record Deposit(int amount) implements Ledger, Op<Ledger, Integer, Deposit> {}
  }

  static Comp<Integer, Eff<Ledger, ?>> deposit(int amount) { return perform(new Ledger.Deposit(amount)); }

  static <A, R> Comp<Integer, R> ledger(Comp<A, ? super Eff<Ledger, R>> p) {
    return handle(Ledger.class, new Handler<Ledger, A, Integer, R>() {
      public Comp<Integer, R> ret(A a) { return pure(0); }
      public Comp<Integer, R> on(Ledger op, K<Integer, R> k) {
        return switch (op) {
          case Ledger.Deposit d when d.amount() < 0 -> k.unwind().map(left -> -1);
          case Ledger.Deposit d -> k.resume(d, d.amount());
        };
      }
    }, p);
  }

  static <A, R extends Eff<Ledger, ? extends Eff<Trail, ?>>> Comp<A, R> recordingLedger(Comp<A, ? super R> p) {
    return intercept(Ledger.class, new Handler<Ledger, A, A, R>() {
      public Comp<A, R> ret(A a) { return pure(a); }
      public Comp<A, R> on(Ledger op, K<A, R> k) {
        return switch (op) {
          case Ledger.Deposit d -> Audits.<R>note("deposit " + d.amount()).flatMap(e -> k.after(d,
              Effects.<Integer, R>fit(perform(d))
                  .flatMap(x -> Audits.<R>note("deposit " + d.amount() + " → " + x).map(f -> x))));
        };
      }
    }, p);
  }

  static void tests() {
    test("an observer changes nothing", () -> {
      List<String> seen = new ArrayList<>();
      Comp<Integer, Eff<State<Integer>, Eff<Choose, ?>>> p = up(Choose.among(List.of(1, 2)))
          .flatMap(x -> State.<Integer>get().flatMap(s -> State.put(s + x).map(d -> s + x)));
      Comp<Integer, Eff<State<Integer>, ? extends Eff<Choose, ?>>> observed = observe(State.class,
          op -> { seen.add(op.toString()); return pure(null); }, p);
      same(run(Choose.all(State.run(0, observed))), run(Choose.all(State.run(0, p))));
      same(seen.subList(0, 4), List.of("Get[]", "Put[value=1]", "Get[]", "Put[value=2]"));
    });

    test("the trail of a one-path program is its operations in order", () ->
        same(run(Trail.keep(counted(5, recording("", Audits.<Eff<Trail, ?>>counting())))),
            new Trail.Kept<>(List.of("get", "get → 5", "put 6", "put 6 → done", "get", "get → 6"),
                new State.Final<>(6, 6))));

    test("the trail under a choice: one trail through all paths, or each path its own", () -> {
      Comp<Integer, Eff<Choose, Eff<Counter, Eff<Trail, ?>>>> through = Choose.among(List.of(1, 2))
          .flatMap(x -> up(get().flatMap(s -> put(s + x).map(d -> s + x))));
      same(run(Trail.keep(counted(0, recording("", Choose.all(through))))), new Trail.Kept<>(
          List.of("get", "get → 0", "put 1", "put 1 → done", "get", "get → 1", "put 3", "put 3 → done"),
          new State.Final<>(3, List.of(1, 3))));
      Comp<Integer, Eff<Counter, Eff<Trail, Eff<Choose, ?>>>> each = Effects.<Integer, Eff<Counter, Eff<Trail,
          Eff<Choose, ?>>>>fit(up(up(Choose.among(List.of(1, 2))))).flatMap(x -> get().flatMap(s -> put(s + x)
          .map(d -> s + x)));
      same(run(Choose.all(Trail.keep(counted(0, recording("", each))))), List.of(
          new Trail.Kept<>(List.of("get", "get → 0", "put 1", "put 1 → done"), new State.Final<>(1, 1)),
          new Trail.Kept<>(List.of("get", "get → 0", "put 2", "put 2 → done"), new State.Final<>(2, 2))));
      // an answer is recorded in the rest of whoever answers it: given twice, it is recorded twice
      Comp<Integer, Eff<Choose, Eff<Trail, ?>>> pick = fit(Choose.among(List.of(0, 1)));
      same(run(Trail.keep(Choose.all(picking(pick)))),
          new Trail.Kept<>(List.of("among", "→ 0", "→ 1"), List.of(0, 1)));
    });

    test("an auditor that refuses", () -> {
      Comp<Integer, Eff<Counter, Eff<Trail, ?>>> puts = put(1).flatMap(d -> put(5)).flatMap(d -> get());
      Comp<Integer, Eff<Throws<String>, Eff<Counter, Eff<Trail, ?>>>> p = fit(up(puts));
      same(run(Trail.keep(counted(0, Throws.run(refusing(p))))), new Trail.Kept<>(List.of("put 1", "put 5 refused"),
          new State.Final<>(1, new Throws.Err<>("put 5 refused"))));
      // refused, the program is left: its finalizer runs, and its operation passes the auditor
      same(run(Trail.keep(counted(0, Throws.run(refusing(p.ensuring(() -> up(put(2)))))))), new Trail.Kept<>(
          List.of("put 1", "put 5 refused", "put 2"), new State.Final<>(2, new Throws.Err<>("put 5 refused"))));
    });

    test("an auditor that changes an operation", () ->
        same(run(Trail.keep(counted(0, capped(put(1).flatMap(d -> put(5)).flatMap(d -> get()))))),
            new Trail.Kept<>(List.of("put 1", "put 5 as 2"), new State.Final<>(2, 2))));

    test("two auditors of one effect, inner and outer", () -> {
      Comp<Integer, Eff<Counter, Eff<Trail, ?>>> p = get().flatMap(s -> put(s + 1).map(d -> s));
      same(run(Trail.keep(counted(0, recording("outer ", recording("inner ", p))))), new Trail.Kept<>(List.of(
          "inner get", "outer get", "outer get → 0", "inner get → 0",
          "inner put 1", "outer put 1", "outer put 1 → done", "inner put 1 → done"), new State.Final<>(1, 0)));
    });

    test("an audit between two handlers of one effect", () -> {
      Comp<Void, Eff<Counter, Eff<Counter, Eff<Trail, ?>>>> puts = put(1).flatMap(d -> up(put(10)));
      Comp<Pair<Integer, Integer>, Eff<Counter, Eff<Counter, Eff<Trail, ?>>>> p = puts.flatMap(d -> get())
          .flatMap(a -> up(get()).map(b -> new Pair<>(a, b)));
      // between them: what the inner one answers does not come here, the outer one's operations do
      same(run(Trail.keep(counted(0, recording("", counted(0, p))))), new Trail.Kept<>(
          List.of("put 10", "put 10 → done", "get", "get → 10"),
          new State.Final<>(10, new State.Final<>(1, new Pair<>(1, 10)))));
    });

    test("sensitive: handled under its audit it runs, the trail complete", () ->
        same(run(Trail.keep(bank(1000, Audit.audit(recorded(), payments())))), new Trail.Kept<>(List.of(
            "transfer 100 to a", "transfer 100 to a → ok:a:100", "balance", "balance → 900",
            "transfer 50 to b", "transfer 50 to b → ok:b:50"), new Pair<>(850, 900))));

    test("an audited body asks past its sensitive place", () -> {
      // the audit and its handler are one place of the row: what the body asks of the next place passes both
      Comp<Integer, Sensitive<Pay, Eff<Trail, ?>>> body = Audit.perform(new Pay.Balance())
          .flatMap(b -> Effects.<Void, Sensitive<Pay, Eff<Trail, ?>>>fit(up(Trail.record("after " + b))).map(d -> b));
      Interceptor<Pay, Integer, Eff<Trail, ?>> passing = (op, pass, k) -> switch (op) {
        case Pay.Transfer t -> k.after(t, pass.pass(t));
        case Pay.Balance b -> k.after(b, pass.pass(b));
      };
      same(run(Trail.keep(bank(7, Audit.audit(passing, body)))), new Trail.Kept<>(List.of("after 7"),
          new Pair<>(7, 7)));
    });

    // javac's refusals are the programs of negative/Sensitive*.java; what is left to test here is the one refusal
    // at run time: a program stepped, whose sensitive operation would be given to whoever steps it
    test("sensitive: unaudited, the compiler refuses it", () -> {
      Step<Integer, Sensitive<Pay, ?>> at = null;
      try {
        at = step(payments());
      } catch (NoHandler refused) {
        same(refused.op, new Pay.Transfer("a", 100));
      }
      same(at, null);
    });

    test("audit with ensuring: a finalizer's operations are in the trail; one that ends it is asked, not answered",
        () -> {
          Comp<Integer, Eff<Throws<String>, Eff<Counter, Eff<Trail, ?>>>> failing =
              Effects.<Void, Eff<Throws<String>, Eff<Counter, Eff<Trail, ?>>>>fit(up(put(1)))
                  .flatMap(d -> Throws.raise("no"));
          Comp<Integer, Eff<Throws<String>, Eff<Counter, Eff<Trail, ?>>>> p =
              failing.ensuring(() -> up(get().flatMap(s -> put(s + 10))));
          same(run(Trail.keep(counted(0, recording("", Throws.run(p))))), new Trail.Kept<>(
              List.of("put 1", "put 1 → done", "get", "get → 1", "put 11", "put 11 → done"),
              new State.Final<>(11, new Throws.Err<>("no"))));
          // the ledger leaves at a deposit below nothing: the finalizer's deposit asks a handler that has left
          Comp<Integer, Eff<Ledger, Eff<Trail, ?>>> deposits = deposit(1).flatMap(d -> deposit(-1));
          Comp<Integer, Eff<Ledger, Eff<Trail, ?>>> q = deposits.ensuring(() -> deposit(5));
          same(run(Trail.keep(ledger(recordingLedger(q)))),
              new Trail.Kept<>(List.of("deposit 1", "deposit 1 → 1", "deposit -1", "deposit 5"), -1));
        });

    test("constant stack: a hundred thousand audited operations", () -> {
      Interceptor<Pay, Integer, Pure> passing = (op, pass, k) -> switch (op) {
        case Pay.Transfer t -> k.after(t, pass.pass(t));
        case Pay.Balance b -> k.after(b, pass.pass(b));
      };
      same(run(bank(1, Audit.audit(passing, balances(100_000, 0)))), new Pair<>(1, 100_000));
    });
  }

  /** each choice recorded when it is asked and each time it is answered */
  static <A, R extends Eff<Choose, ? extends Eff<Trail, ?>>> Comp<A, R> picking(Comp<A, ? super R> p) {
    return intercept(Choose.class, new Handler<Choose, A, A, R>() {
      public Comp<A, R> ret(A a) { return pure(a); }
      public Comp<A, R> on(Choose op, K<A, R> k) {
        return switch (op) {
          case Choose.Among<?> among -> picked(among, k);
        };
      }
    }, p);
  }

  static <X, A, R extends Eff<Choose, ? extends Eff<Trail, ?>>> Comp<A, R> picked(Choose.Among<X> among, K<A, R> k) {
    return Audits.<R>note("among").flatMap(d -> k.after(among,
        Effects.<X, R>fit(perform(among)).flatMap(x -> Audits.<R>note("→ " + x).map(e -> x))));
  }

  static <A, R extends Eff<Throws<String>, ? extends Eff<Counter, ? extends Eff<Trail, ?>>>> Comp<A, R> refusing(
      Comp<A, ? super R> p) {
    return intercept(Counter.class, new Handler<Counter, A, A, R>() {
      public Comp<A, R> ret(A a) { return pure(a); }
      public Comp<A, R> on(Counter op, K<A, R> k) {
        return switch (op) {
          case Counter.Put put when put.value() > 2 -> Effects.<Void, R>fit(up(up(Trail.record(
              "put " + put.value() + " refused")))).flatMap(d -> k.after(put,
              Effects.<Void, R>fit(Throws.raise("put " + put.value() + " refused"))));
          case Counter.Put put -> Effects.<Void, R>fit(up(up(Trail.record("put " + put.value()))))
              .flatMap(d -> k.after(put, Effects.<Void, R>fit(up(perform(put)))));
          case Counter.Get get -> k.after(get, Effects.<Integer, R>fit(up(perform(get))));
        };
      }
    }, p);
  }

  static <A, R extends Eff<Counter, ? extends Eff<Trail, ?>>> Comp<A, R> capped(Comp<A, ? super R> p) {
    return intercept(Counter.class, new Handler<Counter, A, A, R>() {
      public Comp<A, R> ret(A a) { return pure(a); }
      public Comp<A, R> on(Counter op, K<A, R> k) {
        return switch (op) {
          case Counter.Put put when put.value() > 2 -> Audits.<R>note("put " + put.value() + " as 2")
              .flatMap(d -> k.after(put, Effects.<Void, R>fit(put(2))));
          case Counter.Put put -> Audits.<R>note("put " + put.value())
              .flatMap(d -> k.after(put, Audits.<R, Void, Counter.Put>proceed(put)));
          case Counter.Get get -> k.after(get, Audits.<R, Integer, Counter.Get>proceed(get));
        };
      }
    }, p);
  }
}
