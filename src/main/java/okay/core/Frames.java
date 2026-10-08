package okay.core;

import java.util.function.Function;

/**
 * WHAT IS BOUND TO A VALUE, the loop's stack: the functions a value goes through, innermost first — each
 * {@code Bind} the loop enters pushes its function, no tree rebuilt. Immutable, so a captured stack can be
 * resumed any number of times. As a function it is the rest itself, {@code k}: applying it runs one function,
 * the rest bound after it — one call, whatever the depth.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
sealed interface Frames extends Function<Object, Cont<?, ?, ?>> {

  /** nothing bound: the value is the program's */
  Frames END = new End();

  record End() implements Frames {}

  /** {@code f} bound to the value, the rest of the stack to its result */
  record Then(Function f, Frames next) implements Frames {}

  /** {@code f} mapped over the value, the rest of the stack to its result */
  record Mapped(Function f, Frames next) implements Frames {}

  /** the stack applied to a value: one step built, and the rest as a stack, not run here in the caller's stack */
  @Override
  default Cont<?, ?, ?> apply(Object a) {
    return switch (this) {
      case End _ -> new Cont.Return<>(a);
      case Then(var f, var next) ->
          next instanceof End ? (Cont<?, ?, ?>) f.apply(a) : new Push<>((Cont) f.apply(a), next);
      case Mapped(var f, var next) ->
          next instanceof End ? new Cont.Return<>(f.apply(a)) : new Push<>(new Cont.Return<>(f.apply(a)), next);
    };
  }
}
