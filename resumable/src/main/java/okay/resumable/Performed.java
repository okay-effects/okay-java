package okay.resumable;

import okay.effect.Operation;

/** AN EFFECT AS A GADT, PERFORMED IN DIRECT STYLE: its interface says, once, its answers' index, itself, and its
 * name in a {@code throws} —
 *
 * <pre>{@code
 * sealed interface Prices<T> extends Performed<T, Prices<?>, Prices.Fx> {
 *   final class Fx extends Suspend implements Marks<Prices<?>> {}
 *   record Price(String item) implements Prices<Long> {}
 *   record InStock(String item) implements Prices<Boolean> {}
 * }
 *
 * static long total(List<String> cart) throws Prices.Fx {
 *   long sum = 0;
 *   for (String item : cart)
 *     if (Direct.perform(new Prices.InStock(item))) sum += Direct.perform(new Prices.Price(item));
 *   return sum;
 * }
 * }</pre>
 *
 * It is an {@link Operation} of {@code F}: the same operations are asked by {@code okay.rows.Effects.perform} in a
 * program written as data, and the same handlers answer both. */
public interface Performed<T, F, X extends Suspend & Marks<F>> extends Operation<T, F> {}
