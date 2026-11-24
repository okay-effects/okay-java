package okay.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import okay.core.Comp;
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

  static Comp<Boolean, Comp.Top> active() {
    return Comp.sync(TransactionSynchronizationManager::isActualTransactionActive);
  }

  @Test
  void aValueCommits() {
    assertTrue(Comp.run(txs.transactional(active())), "the program runs in the transaction");
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    assertEquals(List.of("begin", "commit"), tm.log);
  }

  @Test
  void nothingBeginsBeforeTheRunGetsToIt() {
    Comp<Boolean, Comp.Top> p = txs.transactional(active());
    assertEquals(List.of(), tm.log);
    Comp.run(p.then(p));
    assertEquals(List.of("begin", "commit", "begin", "commit"), tm.log);
  }

  @Test
  void aFailureRollsBackAndGoesOn() {
    Comp<String, Comp.Top> p = txs.transactional(Comp.failed(new IllegalStateException("broken")));
    assertEquals("broken", assertThrows(IllegalStateException.class, () -> Comp.run(p)).getMessage());
    assertEquals(List.of("begin", "rollback"), tm.log);
  }

  @Test
  void aRaiseCaughtInsideCommitsTheRecovery() {
    Comp<String, Comp.Top> body = Throws.catching(new Throws.Body<Comp.Top, String, String>() {
      public <M extends Comp.Top> Comp<String, M> in(Throws<M, String> t) {
        return active().<M>at().flatMap(_ -> t.<String>raise("no"));
      }
    }, e -> Comp.pure("caught " + e));
    assertEquals("caught no", Comp.run(txs.transactional(body)));
    assertEquals(List.of("begin", "commit"), tm.log);
  }

  @Test
  void aRaiseNotCaughtInsideRollsBack() {
    Comp<Throws.Result<String, String>, Comp.Top> body = Throws.attempt(new Throws.Body<Comp.Top, String, String>() {
      public <M extends Comp.Top> Comp<String, M> in(Throws<M, String> t) {
        return t.raise("no");
      }
    }).flatMap(r -> r instanceof Throws.Result.Failed<String, String> f
        ? Comp.failed(new IllegalStateException(f.error())) : Comp.pure(r));
    assertThrows(IllegalStateException.class, () -> Comp.run(txs.transactional(body)));
    assertEquals(List.of("begin", "rollback"), tm.log);
  }

  /** the value goes on at the handler's level: the transaction between two of its operations */
  @Test
  void inTheMiddleOfAProgramUnderAHandler() {
    var p = State.state(1, new State.Body<Comp.Top, Integer, Boolean>() {
      public <M extends Comp.Top> Comp<Boolean, M> in(State<M, Integer> st) {
        return st.modify(s -> s + 41).then(txs.transactional(active())).flatMap(a -> st.modify(s -> s * 2).then(
            Comp.pure(a)));
      }
    });
    assertEquals(new State.Final<>(84, true), Comp.run(p));
    assertEquals(List.of("begin", "commit"), tm.log);
  }
}
