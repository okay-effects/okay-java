package okay.core;

import java.util.function.Function;

/** a program's head, what the loop over its binds comes to: its value, or a capture with the rest */
sealed interface Head {
  record Done(Object value) implements Head {}

  record Cut(Function<Object, Object> f, Frames k) implements Head {}
}
