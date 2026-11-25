package okay.sugar;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** AN EFFECT WRITTEN AS A PLAIN INTERFACE: {@code @Effect interface Prices { long price(String item); }}. The
 * processor generates, beside it, {@code PricesOp<T>} — the effect as okay.rows has it: a sealed interface indexed
 * by the answers, a record for each method ({@code PricesOp.Price(String item)}, a {@code PricesOp<Long>}); a
 * function for each operation ({@code PricesOp.price(item)}, the program that asks it); and
 * {@code PricesOp.handledBy(impl, program)}, the handler any implementation of the interface is — it answers in
 * place. A handler that needs the rest of the program is written as ever, a {@code switch} over the records.
 *
 * <p>The interface: no type parameters; its abstract methods not generic, not overloaded, with no {@code throws}.
 * Default and static methods are left alone. */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface Effect {}
