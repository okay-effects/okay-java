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
}
