package okay.resumable;

import static okay.rows.Effects.handle;
import static okay.rows.Effects.pure;
import static okay.rows.Effects.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import okay.rows.Audit;
import okay.rows.Audited;
import okay.rows.Cell;
import okay.rows.Comp;
import okay.rows.Eff;
import okay.rows.InPlace;
import okay.rows.std.Pair;
import okay.rows.std.Trail;
import org.junit.jupiter.api.Test;

/** MISUSE: code inside a program that reaches a handler through a static reference and calls it, or its state, or an
 * effect's real implementation, instead of performing the effect. What happens, and what is refused (README:
 * "Misuse"). The build-time guard over these same programs: {@code GuardTest} */
class MisuseTest {

  /** a rest kept from a clause, where anything can reach it */
  static Direct.K<Integer> kept;
  // and the operation it was asked for: the witness okay.rows' own check wants
  static Counter keptOp;
  /** a handler reachable from anywhere */
  static Keeping global;

  /** a state written generally, which keeps each k it is given in a static field */
  static final class Keeping implements Direct.Handler0<Counter, Integer, Integer> {
    int s;
    /** set: each clause first resumes the k kept from the clause before it */
    boolean resumeTheOld;

    public Integer ret(Integer a) { return a; }

    public Integer on(Counter op, Direct.K<Integer> k) throws Direct.Resumes {
      Direct.K<Integer> old = kept;
      Counter oldOp = keptOp;
      kept = k;
      keptOp = op;
      if (resumeTheOld && oldOp instanceof Counter.Get get) old.resume(get, s);
      return switch (op) {
        case Counter.Get get -> k.resume(get, s);
        case Counter.Put put -> {
          s = put.value;
          yield k.resume(put, null);
        }
      };
    }
  }

  static <R> Comp<Integer, R> under(Keeping h, Comp<Integer, ? super Eff<Counter, R>> p) {
    return handle(Counter.class, Direct.handler(h), p);
  }

  @Test
  void aProgramCannotMakeAK() {
    // its constructor is the boundary's: no K but one a clause was given
    assertEquals(0, Direct.K.class.getConstructors().length);
  }

  /** the body resumes a k it found in a static field: javac lets it, if it catches Resumes */
  static int resumesAKeptK() throws Counter {
    Counter.get();
    try {
      return kept.resume((Counter.Get) keptOp, 99);
    } catch (Direct.Resumes e) {
      throw new AssertionError(e);
    }
  }

  @Test
  void aKResumedByTheProgramsBodyIsRefused() {
    var e = assertThrows(IllegalStateException.class, () -> run(under(new Keeping(),
        Direct.row(Counter.class).of(MisuseTest::resumesAKeptK))));
    assertTrue(e.getMessage().contains("by a program's body"), e.getMessage());
  }

  /** one row, its programs and an interceptor written for it: a k kept by the interceptor */
  static final Direct.Row1<Counter> ROW = Direct.row(Counter.class);
  static Direct.K<Integer> keptByTheInterceptor;

  static int resumesTheInterceptorsK() throws Counter {
    int x = Counter.get();
    try {
      return keptByTheInterceptor.resume((Counter.Get) keptOp, x + 99);
    } catch (Direct.Resumes e) {
      throw new AssertionError(e);
    }
  }

  @Test
  void aKResumedByAProgramOfTheSameRowObjectIsRefused() {
    var interceptor = ROW.<Counter, Integer, Integer, okay.rows.Pure>handler(new Direct.Handler1<>() {
      public Integer ret(Integer a) { return a; }
      public Integer on(Counter op, Direct.K<Integer> k) throws Counter, Direct.Resumes {
        keptByTheInterceptor = k;
        keptOp = op;
        return k.resume((Counter.Get) op, Counter.get());
      }
    });
    var e = assertThrows(IllegalStateException.class, () -> run(Counter.run(1, okay.rows.Effects.intercept(
        Counter.class, interceptor, ROW.of(MisuseTest::resumesTheInterceptorsK)))));
    assertTrue(e.getMessage().contains("by a program's body"), e.getMessage());
  }

  /** the body calls the handler's clause itself, with a k it found */
  static int callsTheClause() throws Counter {
    Counter.get();
    try {
      return global.on(keptOp, kept);
    } catch (Direct.Resumes e) {
      throw new AssertionError(e);
    }
  }

  @Test
  void aClauseCalledDirectlyByTheBodyCannotResume() {
    global = new Keeping();
    var e = assertThrows(IllegalStateException.class, () -> run(under(global,
        Direct.row(Counter.class).of(MisuseTest::callsTheClause))));
    assertTrue(e.getMessage().contains("by a program's body"), e.getMessage());
    // with no k at all: the clause fails on its own null
    assertThrows(NullPointerException.class, () -> global.on(new Counter.Get(), null));
  }

  @Test
  void aKKeptFromAnEarlierClauseIsRefusedInALaterOne() {
    kept = null;
    keptOp = null;
    Keeping h = new Keeping();
    h.resumeTheOld = true;
    var e = assertThrows(IllegalStateException.class, () -> run(under(h,
        Direct.row(Counter.class).of(() -> DirectTest.count(2)))));
    assertTrue(e.getMessage().contains("by a later clause of its handler"), e.getMessage());
  }

  @Test
  void aKUsedAfterItsProgramEndedOrOnAnotherThreadIsRefused() throws InterruptedException {
    assertEquals(3, run(under(new Keeping(), Direct.row(Counter.class).of(() -> DirectTest.count(3)))));
    Direct.K<Integer> k = kept;
    Counter.Get get = (Counter.Get) keptOp;
    var here = assertThrows(IllegalStateException.class, () -> k.resume(get, 1));
    assertTrue(here.getMessage().contains("where no program runs"), here.getMessage());
    AtomicReference<Throwable> there = new AtomicReference<>();
    Thread t = new Thread(() -> {
      try {
        k.resume(get, 1);
      } catch (Throwable x) {
        there.set(x);
      }
    });
    t.start();
    t.join();
    assertTrue(there.get() instanceof IllegalStateException, String.valueOf(there.get()));
  }

  /** the body sets the handler's state through a static reference, performing nothing */
  static int setsTheState(List<Integer> seen) throws Counter, Pick {
    int x = Pick.among(1, 2);
    global.s += x;
    seen.add(global.s);
    return Counter.get();
  }

  @Test
  void aHandlersStateChangedDirectlyIsSeenByNoneAndSharedByEveryPath() {
    global = new Keeping();
    List<Integer> seen = new ArrayList<>();
    // no operation: nothing on the way sees it — an interceptor, an observer, an audit; and the two paths of the
    // choice outside share the one field, the second seeing the first's change
    assertEquals(List.of(1, 3), run(Pick.all(under(global, Direct.row(Counter.class, Pick.class).of(() ->
        setsTheState(seen))))));
    assertEquals(List.of(1, 3), seen);
  }

  /** the bank behind the payments: an effect's real implementation */
  @Implementation
  static final class Bank {
    static int balance;

    static String transfer(String to, int amount) {
      balance -= amount;
      return "ok:" + to + ":" + amount;
    }
  }

  static <A, R> Comp<Pair<Integer, A>, R> banked(Comp<A, ? super Audited<Pay, R>> p) {
    return Audit.handle(new InPlace<Pay, Integer, A, Pair<Integer, A>, R>() {
      public Comp<Pair<Integer, A>, R> ret(Integer s, A a) { return pure(new Pair<>(Bank.balance, a)); }
      public Cell.Answered answer(Cell<Integer> unused, Pay op) {
        return switch (op) {
          case Pay.Transfer t -> unused.answer(t, Bank.transfer(t.to, t.amount));
          case Pay.Balance b -> unused.answer(b, Bank.balance);
        };
      }
    }, 0, p);
  }

  /** a transfer performed, and one made by calling the bank itself */
  static int paysAroundTheAudit() throws Pay {
    Pay.transfer("a", 100);
    Bank.transfer("b", 900);
    return Pay.balance();
  }

  @Test
  void anEffectsImplementationCalledDirectlyIsNotAudited() {
    Bank.balance = 1000;
    var program = Direct.row(Direct.sensitive(Pay.class)).of(MisuseTest::paysAroundTheAudit);
    // the trail has the transfer performed and the balance; the 900 went out with no line of it
    assertEquals(new Trail.Kept<>(List.of("transfer 100 to a", "→ ok:a:100", "balance", "→ 0"), new Pair<>(0, 0)),
        run(Trail.keep(banked(Audit.audit(SensitiveTest::policy, program)))));
  }
}
