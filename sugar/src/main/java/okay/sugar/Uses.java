package okay.sugar;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** THE ROW OF A CLASS OF PROGRAMS, named once: {@code @Uses({Prices.class, Ask.class}) class Checkout}. The
 * processor generates {@code CheckoutOps}: a function for every operation of every effect listed, each already at
 * its place in the row — its type {@code Comp<X, Eff<PricesOp<?>, Eff<Ask<?>, ?>>>} whatever the effect, so a
 * program of the class is written with no {@code up} and no {@code perform(new ..)}:
 * {@code import static ..CheckoutOps.*}, then {@code price(item).flatMap(p -> confirm("pay " + p + "?"))}.
 *
 * <p>The effects, in the order of their handlers, the nearest first: an interface marked {@link Effect}, or an
 * effect written by hand — a sealed interface whose operations are records. */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface Uses {
  Class<?>[] value();
}
