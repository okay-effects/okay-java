package okay.resumable;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The mark the transformation leaves on a class it rewrote: it is not rewritten twice (a jar built with the plugin,
 * run with the agent), and a refusal can tell a class that was not transformed from a method that could not be. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Transformed {}
