package okay.resumable;

import okay.rows.Comp;
import okay.rows.Eff;
import okay.rows.Op;
import okay.rows.Pure;

/** DIRECT STYLE: a plain Java method performs operations and gets their answers as values; the effects it uses are
 * its {@code throws}. {@link #of} makes it an {@code okay.rows} program, whose row is those effects, for the row's
 * handlers to answer:
 *
 * <pre>{@code
 * static Receipt checkout(String user) throws Cart, Payments {
 *   int total = Direct.perform(new Cart.Total());
 *   String id = Direct.perform(new Payments.Charge(user, total));
 *   return new Receipt(id, total);
 * }
 *
 * Comp<Receipt, Eff<Cart, Eff<Payments, Pure>>> p = Direct.of(Cart.class, Payments.class, () -> checkout("ann"));
 * }</pre>
 *
 * A method that performs, or calls a method that may, must be transformed at build time (the module's plugin) or at
 * load time ({@code java -jar} of a jar whose manifest names the module's agent): its frames are then saved as data
 * when it suspends, and re-entered when the rest of the program is resumed — once, many times, or never. */
public final class Direct {
  private Direct() {}

  /** the operation asked, its answer returned. Its effect {@code E} is thrown for javac only: a transformed caller
   * does not call this method but its own path, which suspends. Called here, the caller was not transformed. */
  public static <E extends Suspend, X, O extends Op<E, X, ? super O>> X perform(O op) throws E {
    throw untransformed(op);
  }

  private static IllegalStateException untransformed(Object op) {
    StackWalker.StackFrame caller = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
        .walk(s -> s.skip(2).findFirst()).orElseThrow();
    String method = caller.getClassName() + "." + caller.getMethodName() + " (line " + caller.getLineNumber() + ")";
    String why = caller.getDeclaringClass().isAnnotationPresent(Transformed.class)
        ? "its class was transformed but this method was not (a constructor, a static initializer or a synchronized"
            + " method cannot suspend)"
        : "its class was not transformed: build it with the okay-java-resumable plugin (Gradle: id(\"dev.okay"
            + ".resumable\"); Maven: okay-java-resumable-maven-plugin), or run a jar with java -jar, whose manifest's"
            + " Launcher-Agent-Class transforms it as it is loaded. An IDE that compiles by itself does not run the"
            + " plugin: delegate the build and the tests to Gradle or Maven";
    return new IllegalStateException("okay.resumable: " + method + " performed " + op + ", but " + why
        + " — the rest of the program could not be resumed.");
  }

  @FunctionalInterface
  public interface Body1<A, E1 extends Suspend> {
    A run() throws E1;
  }

  @FunctionalInterface
  public interface Body2<A, E1 extends Suspend, E2 extends Suspend> {
    A run() throws E1, E2;
  }

  @FunctionalInterface
  public interface Body3<A, E1 extends Suspend, E2 extends Suspend, E3 extends Suspend> {
    A run() throws E1, E2, E3;
  }

  @FunctionalInterface
  public interface Body4<A, E1 extends Suspend, E2 extends Suspend, E3 extends Suspend, E4 extends Suspend> {
    A run() throws E1, E2, E3, E4;
  }

  /** the body as a program of the row {@code E1}. The classes name the row at run time, in its order: an operation
   * goes to the place of its effect */
  public static <A, E1 extends Suspend> Comp<A, Eff<E1, Pure>> of(Class<E1> e1, Body1<A, E1> body) {
    return Driver.program(new Class<?>[] {e1}, body::run);
  }

  public static <A, E1 extends Suspend, E2 extends Suspend> Comp<A, Eff<E1, Eff<E2, Pure>>> of(
      Class<E1> e1, Class<E2> e2, Body2<A, E1, E2> body) {
    return Driver.program(new Class<?>[] {e1, e2}, body::run);
  }

  public static <A, E1 extends Suspend, E2 extends Suspend, E3 extends Suspend>
      Comp<A, Eff<E1, Eff<E2, Eff<E3, Pure>>>> of(
          Class<E1> e1, Class<E2> e2, Class<E3> e3, Body3<A, E1, E2, E3> body) {
    return Driver.program(new Class<?>[] {e1, e2, e3}, body::run);
  }

  public static <A, E1 extends Suspend, E2 extends Suspend, E3 extends Suspend, E4 extends Suspend>
      Comp<A, Eff<E1, Eff<E2, Eff<E3, Eff<E4, Pure>>>>> of(
          Class<E1> e1, Class<E2> e2, Class<E3> e3, Class<E4> e4, Body4<A, E1, E2, E3, E4> body) {
    return Driver.program(new Class<?>[] {e1, e2, e3, e4}, body::run);
  }
}
