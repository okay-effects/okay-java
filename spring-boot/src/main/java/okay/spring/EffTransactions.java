package okay.spring;

import okay.core.Eff;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

/**
 * a program IN A TRANSACTION, Spring's: {@link #transactional} begins it when the run gets to the program, commits it
 * after the program's value, rolls it back however else the program is left — a failure, an abort or a cancellation
 * of the kernel's. The program is run by the caller's run, not a nested one, and its value goes on at any level: a
 * program at the top fits a bind's function anywhere.
 *
 * <p>The body is a program AT THE TOP, its handlers its own: a capability of a handler outside does not compile in
 * it. A handler outside could drop the rest of the body ({@code Throws.raise} to a {@code catching} outside it), and
 * a dropped rest runs no finalizer, {@code Eff.ensuring}'s included: the transaction would be left open on the
 * thread. A handler inside the body drops nothing past it.
 *
 * <p>Spring's transactions are the thread's: the program between the begin and the end runs on the thread that
 * began it — a program of okay-java's own does; one that goes on in another thread (an {@code Async}'s callback)
 * leaves the transaction behind. {@code @Transactional} on a method that returns an {@code Eff} commits when the
 * method returns, before the program runs: the program's transaction is this.
 *
 * <p>Auto-configured where there is one {@link PlatformTransactionManager}; or {@code new EffTransactions(tm)}
 */
public final class EffTransactions {
  private final PlatformTransactionManager transactions;

  public EffTransactions(PlatformTransactionManager transactions) {
    this.transactions = transactions;
  }

  /** {@code body} in a transaction of Spring's defaults: {@code REQUIRED}, the manager's isolation and timeout */
  public <A> Eff<Eff.Top, A> transactional(Eff<Eff.Top, A> body) {
    return transactional(TransactionDefinition.withDefaults(), body);
  }

  /** {@code body} in a transaction of {@code definition}: a {@code TransactionTemplate} is one */
  public <A> Eff<Eff.Top, A> transactional(TransactionDefinition definition, Eff<Eff.Top, A> body) {
    return Eff.delay(() -> {
      TransactionStatus tx = transactions.getTransaction(definition);
      Eff<Eff.Top, A> committed = body.flatMap(a -> Eff.sync(() -> {
        transactions.commit(tx);
        return a;
      }));
      return Eff.ensuring(committed, Eff.sync(() -> {
        if (!tx.isCompleted()) transactions.rollback(tx);
        return null;
      }));
    });
  }
}
