package okay.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import okay.core.Eff;
import okay.std.State;
import okay.std.Throws;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** programs in transactions of a manager that writes down what it was asked, in an application not of the web */
@SpringBootTest(classes = TransactionsTest.App.class, properties = "spring.main.web-application-type=none")
class TransactionsTest {

  static final class Recording extends AbstractPlatformTransactionManager {
    final List<String> log = new ArrayList<>();

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object tx, TransactionDefinition definition) {
      log.add("begin");
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      log.add("commit");
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
      log.add("rollback");
    }
  }

  @SpringBootApplication
  static class App {
    @Bean
    Recording transactionManager() {
      return new Recording();
    }
  }

  @Autowired
  EffTransactions txs;

  @Autowired
  Recording tm;

  @BeforeEach
  void clear() {
    tm.log.clear();
  }

  static Eff<Eff.Top, Boolean> active() {
    return Eff.sync(TransactionSynchronizationManager::isActualTransactionActive);
  }

  @Test
  void aValueCommits() {
    assertTrue(Eff.run(txs.transactional(active())), "the program runs in the transaction");
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    assertEquals(List.of("begin", "commit"), tm.log);
  }

  @Test
  void nothingBeginsBeforeTheRunGetsToIt() {
    Eff<Eff.Top, Boolean> p = txs.transactional(active());
    assertEquals(List.of(), tm.log);
    Eff.run(p.then(p));
    assertEquals(List.of("begin", "commit", "begin", "commit"), tm.log);
  }

  @Test
  void aFailureRollsBackAndGoesOn() {
    Eff<Eff.Top, String> p = txs.transactional(Eff.failed(new IllegalStateException("broken")));
    assertEquals("broken", assertThrows(IllegalStateException.class, () -> Eff.run(p)).getMessage());
    assertEquals(List.of("begin", "rollback"), tm.log);
  }

  @Test
  void aRaiseCaughtInsideCommitsTheRecovery() {
    Eff<Eff.Top, String> body = Throws.catching(new Throws.Body<Eff.Top, String, String>() {
      public <M extends Eff.Top> Eff<M, String> in(Throws<M, String> t) {
        return active().<M>at().flatMap(_ -> t.<String>raise("no"));
      }
    }, e -> Eff.pure("caught " + e));
    assertEquals("caught no", Eff.run(txs.transactional(body)));
    assertEquals(List.of("begin", "commit"), tm.log);
  }

  @Test
  void aRaiseNotCaughtInsideRollsBack() {
    Eff<Eff.Top, Throws.Result<String, String>> body = Throws.attempt(new Throws.Body<Eff.Top, String, String>() {
      public <M extends Eff.Top> Eff<M, String> in(Throws<M, String> t) {
        return t.raise("no");
      }
    }).flatMap(r -> r instanceof Throws.Result.Failed<String, String> f
        ? Eff.failed(new IllegalStateException(f.error())) : Eff.pure(r));
    assertThrows(IllegalStateException.class, () -> Eff.run(txs.transactional(body)));
    assertEquals(List.of("begin", "rollback"), tm.log);
  }

  /** the value goes on at the handler's level: the transaction between two of its operations */
  @Test
  void inTheMiddleOfAProgramUnderAHandler() {
    var p = State.state(1, new State.Body<Eff.Top, Integer, Boolean>() {
      public <M extends Eff.Top> Eff<M, Boolean> in(State<M, Integer> st) {
        return st.modify(s -> s + 41).then(txs.transactional(active())).flatMap(a -> st.modify(s -> s * 2).then(
            Eff.pure(a)));
      }
    });
    assertEquals(new State.Final<>(84, true), Eff.run(p));
    assertEquals(List.of("begin", "commit"), tm.log);
  }
}
