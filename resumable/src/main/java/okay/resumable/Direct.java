package okay.resumable;

import okay.rows.Comp;
import okay.rows.Eff;
import okay.rows.Op;
import okay.rows.Pure;

/** DIRECT STYLE: a plain Java method performs operations and gets their answers as values; the effects it uses are
 * its {@code throws}. {@link #row}{@code (..).of} makes it an {@code okay.rows} program, whose row is those
 * effects, for the row's handlers to answer:
 *
 * <pre>{@code
 * static Receipt checkout(String user) throws Cart, Payments {
 *   int total = Direct.perform(new Cart.Total());
 *   String id = Direct.perform(new Payments.Charge(user, total));
 *   return new Receipt(id, total);
 * }
 *
 * Comp<Receipt, Eff<Cart, Eff<Payments, Pure>>> p = Direct.row(Cart.class, Payments.class).of(() -> checkout("ann"));
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

  /** how operations whose handler is general and declared {@code Once} are run here: on the JVM's continuations, or
   * — and why — on saved frames */
  public static String continuations() {
    return Loom.AVAILABLE ? "the JVM's continuations" : "saved frames: " + Loom.WHY_NOT;
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

  /** THE ROW of a direct-style program, named once by its effects' classes, in the order of their handlers: an
   * operation goes to the place of its effect. Its {@code of} then takes a body that throws no effect outside it.
   * In two steps because javac would not infer the row in one: every effect a lambda throws becomes a lower bound
   * of every type variable of the interface's {@code throws}, so no single class fits them all. */
  public static <E1 extends Suspend> Row1<E1> row(Class<E1> e1) {
    return new Row1<>(new Class<?>[] {e1});
  }

  public static <E1 extends Suspend, E2 extends Suspend> Row2<E1, E2> row(Class<E1> e1, Class<E2> e2) {
    return new Row2<>(new Class<?>[] {e1, e2});
  }

  public static <E1 extends Suspend, E2 extends Suspend, E3 extends Suspend> Row3<E1, E2, E3> row(
      Class<E1> e1, Class<E2> e2, Class<E3> e3) {
    return new Row3<>(new Class<?>[] {e1, e2, e3});
  }

  public static <E1 extends Suspend, E2 extends Suspend, E3 extends Suspend, E4 extends Suspend>
      Row4<E1, E2, E3, E4> row(Class<E1> e1, Class<E2> e2, Class<E3> e3, Class<E4> e4) {
    return new Row4<>(new Class<?>[] {e1, e2, e3, e4});
  }

  public static final class Row1<E1 extends Suspend> {
    private final Class<?>[] row;

    private Row1(Class<?>[] row) { this.row = row; }

    /** the body as a program of the row: run when the program is */
    public <A> Comp<A, Eff<E1, Pure>> of(Body1<A, E1> body) {
      return Driver.program(row, body::run);
    }
  }

  public static final class Row2<E1 extends Suspend, E2 extends Suspend> {
    private final Class<?>[] row;

    private Row2(Class<?>[] row) { this.row = row; }

    public <A> Comp<A, Eff<E1, Eff<E2, Pure>>> of(Body2<A, E1, E2> body) {
      return Driver.program(row, body::run);
    }
  }

  public static final class Row3<E1 extends Suspend, E2 extends Suspend, E3 extends Suspend> {
    private final Class<?>[] row;

    private Row3(Class<?>[] row) { this.row = row; }

    public <A> Comp<A, Eff<E1, Eff<E2, Eff<E3, Pure>>>> of(Body3<A, E1, E2, E3> body) {
      return Driver.program(row, body::run);
    }
  }

  public static final class Row4<E1 extends Suspend, E2 extends Suspend, E3 extends Suspend, E4 extends Suspend> {
    private final Class<?>[] row;

    private Row4(Class<?>[] row) { this.row = row; }

    public <A> Comp<A, Eff<E1, Eff<E2, Eff<E3, Eff<E4, Pure>>>>> of(Body4<A, E1, E2, E3, E4> body) {
      return Driver.program(row, body::run);
    }
  }
}
