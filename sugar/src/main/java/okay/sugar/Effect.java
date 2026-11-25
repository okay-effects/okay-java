package okay.sugar;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** ON AN EFFECT WRITTEN BY HAND — a sealed interface, a record for each operation:
 * {@code @Effect sealed interface Prices<T> extends Operation<T, Prices<?>> { record Price(String item) implements
 * Prices<Long> {} }}. The effect stays what it is, its records the names a handler switches over. The processor
 * generates, beside it, {@code PricesOps}: a function for each operation ({@code PricesOps.price(item)}, the
 * program that asks it); the handler that only answers as an interface, {@code PricesOps.Handler}, a method for
 * each operation ({@code long price(String item)}); and {@code PricesOps.handledBy(impl, program)}, the program
 * under an implementation of it, answered in place.
 *
 * <p>Refused: an effect with a type parameter of its own ({@code State<S>}). */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface Effect {}
