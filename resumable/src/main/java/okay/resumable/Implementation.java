package okay.resumable;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** AN EFFECT'S REAL IMPLEMENTATION, not for programs: the client a handler calls (a bank, a database), or a handler
 * that does not implement one of the handler interfaces. With the transformation's guard on ({@code guard = warn} or
 * {@code error} in the build's plugin), effectful code may name such a class only to construct it — to install it —
 * not call it or touch its state. A handler implementing {@code Direct.Handler0..4}, {@code Direct.InPlace} or one of
 * okay.rows' handler interfaces is one without the mark. A check of the bytecode, not a proof: see the README's
 * "Misuse" for what Java cannot prevent, and for JPMS, which can. */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface Implementation {}
