package okay.rows;

import java.util.List;

/** A HANDLER'S CLAUSE FOR A SET OF OPERATIONS asked at once ({@code Effects.performAll}, a {@code gather}, a
 * round of a {@link Together}): all of
 * them, before any is answered — to answer them by one query, in parallel, from a cache, each repeated one once.
 * It answers each through the continuation, the operation the witness of its answer's type, and resumes:
 *
 * <pre>{@code
 * (ops, k) -> {
 *   Map<String, Long> table = prices(items(ops));          // one query for all
 *   for (Prices<?> op : ops) switch (op) {
 *     case Prices.Price p -> k.answer(p, table.get(p.item()));
 *   }
 *   return k.resume();
 * }
 * }</pre>
 *
 * A handler without this clause is asked the operations of a set one by one: a program that asks a set runs under
 * any handler of its effect. */
@FunctionalInterface
public interface Batch<F, A, R> {
  Comp<A, R> on(List<F> ops, KAll<A, R> k);

  /** How one operation of a set is answered, for a set answered operation by operation: it answers through the
   * continuation, {@code (op, k) -> { switch (op) { case Price p -> k.answer(p, lookup(p.item())); } }}. What it
   * does may wait — a query, a call: it is a handler's, and runs outside the program. */
  @FunctionalInterface
  interface One<F, A, R> {
    void answer(F op, KAll<A, R> k);
  }

  /** THE OPERATIONS OF A SET ANSWERED AT THE SAME TIME: {@code one} for each, each on a virtual thread; the
   * program goes on when all are answered. The operations of a set are independent, so nothing in the program
   * says an order between them — the handler chooses, and the program is the same. A failure of {@code one}, the
   * first, is the clause's, when all have ended. */
  static <F, A, R> Batch<F, A, R> concurrently(One<F, A, R> one) {
    return (ops, k) -> {
      Throwable[] failed = new Throwable[1];
      List<Thread> threads = new java.util.ArrayList<>(ops.size());
      for (F op : ops) {
        threads.add(Thread.ofVirtual().start(() -> {
          try {
            one.answer(op, k);
          } catch (Throwable t) {
            synchronized (failed) {
              if (failed[0] == null) failed[0] = t;
            }
          }
        }));
      }
      boolean interrupted = false;
      for (Thread t : threads) {
        while (true) {
          try {
            t.join();
            break;
          } catch (InterruptedException e) {
            interrupted = true;
          }
        }
      }
      if (interrupted) Thread.currentThread().interrupt();
      synchronized (failed) {
        if (failed[0] instanceof RuntimeException e) throw e;
        if (failed[0] instanceof Error e) throw e;
        if (failed[0] != null) throw new IllegalStateException(failed[0]);
      }
      return k.resume();
    };
  }
}
