package okay.resumable;

import java.util.function.Predicate;
import okay.rows.Cell;
import okay.rows.Comp;
import okay.rows.Eff;
import okay.rows.Effects;
import okay.rows.Here;
import okay.rows.Once;
import okay.rows.Op;
import okay.rows.Pure;
import okay.rows.Sensitive;

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

  /** a sensitive operation asked: its effect, at the first place of the program's row, is answered only under its
   * audit ({@link #sensitive}). As {@link #perform(Op)}, thrown for javac only */
  public static <E extends SensitiveSuspend, X, O extends Sensitive.Op<E, X, ? super O>> X perform(O op) throws E {
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
  public interface Body1<A, E1 extends Exception> {
    A run() throws E1;
  }

  @FunctionalInterface
  public interface Body2<A, E1 extends Exception, E2 extends Exception> {
    A run() throws E1, E2;
  }

  @FunctionalInterface
  public interface Body3<A, E1 extends Exception, E2 extends Exception, E3 extends Exception> {
    A run() throws E1, E2, E3;
  }

  @FunctionalInterface
  public interface Body4<A, E1 extends Exception, E2 extends Exception, E3 extends Exception,
      E4 extends Exception> {
    A run() throws E1, E2, E3, E4;
  }

  /** THE ROW of a direct-style program, named once by its effects' classes, in the order of their handlers: an
   * operation goes to the place of its effect. Its {@code of} then takes a body that throws no effect outside it.
   * In two steps because javac would not infer the row in one: every effect a lambda throws becomes a lower bound
   * of every type variable of the interface's {@code throws}, so no single class fits them all. */
  public static <E1 extends Suspend> Row1<E1> row(Class<E1> e1) {
    return new Row1<>(plain(e1));
  }

  public static <E1 extends Suspend, E2 extends Suspend> Row2<E1, E2> row(Class<E1> e1, Class<E2> e2) {
    return new Row2<>(plain(e1, e2));
  }

  public static <E1 extends Suspend, E2 extends Suspend, E3 extends Suspend> Row3<E1, E2, E3> row(
      Class<E1> e1, Class<E2> e2, Class<E3> e3) {
    return new Row3<>(plain(e1, e2, e3));
  }

  public static <E1 extends Suspend, E2 extends Suspend, E3 extends Suspend, E4 extends Suspend>
      Row4<E1, E2, E3, E4> row(Class<E1> e1, Class<E2> e2, Class<E3> e3, Class<E4> e4) {
    return new Row4<>(plain(e1, e2, e3, e4));
  }

  public static final class Row1<E1 extends Suspend> {
    private final Class<?>[] row;

    private Row1(Class<?>[] row) { this.row = row; }

    /** the body as a program of the row: run when the program is */
    public <A> Comp<A, Eff<E1, Pure>> of(Body1<A, E1> body) {
      return Driver.program(row, body::run);
    }

    /** a handler written in direct style, its clauses performing this row's effects, outside the handler */
    public <F, A, B, R> okay.rows.Handler<F, A, B, Eff<E1, R>> handler(Handler1<F, A, B, E1> h) {
      return adapt(row, h, h::ret, h::on, h::mine);
    }
  }

  public static final class Row2<E1 extends Suspend, E2 extends Suspend> {
    private final Class<?>[] row;

    private Row2(Class<?>[] row) { this.row = row; }

    public <A> Comp<A, Eff<E1, Eff<E2, Pure>>> of(Body2<A, E1, E2> body) {
      return Driver.program(row, body::run);
    }

    public <F, A, B, R> okay.rows.Handler<F, A, B, Eff<E1, Eff<E2, R>>> handler(Handler2<F, A, B, E1, E2> h) {
      return adapt(row, h, h::ret, h::on, h::mine);
    }
  }

  public static final class Row3<E1 extends Suspend, E2 extends Suspend, E3 extends Suspend> {
    private final Class<?>[] row;

    private Row3(Class<?>[] row) { this.row = row; }

    public <A> Comp<A, Eff<E1, Eff<E2, Eff<E3, Pure>>>> of(Body3<A, E1, E2, E3> body) {
      return Driver.program(row, body::run);
    }

    public <F, A, B, R> okay.rows.Handler<F, A, B, Eff<E1, Eff<E2, Eff<E3, R>>>> handler(
        Handler3<F, A, B, E1, E2, E3> h) {
      return adapt(row, h, h::ret, h::on, h::mine);
    }
  }

  public static final class Row4<E1 extends Suspend, E2 extends Suspend, E3 extends Suspend, E4 extends Suspend> {
    private final Class<?>[] row;

    private Row4(Class<?>[] row) { this.row = row; }

    public <A> Comp<A, Eff<E1, Eff<E2, Eff<E3, Eff<E4, Pure>>>>> of(Body4<A, E1, E2, E3, E4> body) {
      return Driver.program(row, body::run);
    }

    public <F, A, B, R> okay.rows.Handler<F, A, B, Eff<E1, Eff<E2, Eff<E3, Eff<E4, R>>>>> handler(
        Handler4<F, A, B, E1, E2, E3, E4> h) {
      return adapt(row, h, h::ret, h::on, h::mine);
    }
  }

  /** the classes of a row's plain places. javac keeps a sensitive effect out of them; a raw {@code Class} would not,
   * so it is refused here too */
  private static Class<?>[] plain(Class<?>... effects) {
    for (Class<?> e : effects)
      if (!Suspend.class.isAssignableFrom(e)) throw new IllegalArgumentException("okay.resumable: " + e.getName()
          + " is not a plain effect (a subclass of Suspend); a sensitive one is placed by Direct.sensitive");
    return effects;
  }

  /** the row's classes: the sensitive effect first, then the plain ones */
  private static Class<?>[] places(SensitivePlace<?> s, Class<?>... effects) {
    Class<?>[] all = new Class<?>[effects.length + 1];
    all[0] = s.effect;
    System.arraycopy(plain(effects), 0, all, 1, effects.length);
    return all;
  }

  /** A SENSITIVE EFFECT's place, first in a row: {@code Direct.row(Direct.sensitive(Pay.class), Log.class)}. The
   * program's row then begins with {@code Sensitive<Pay, ..>}, which only {@code Audit.audit} takes */
  public static <S extends SensitiveSuspend> SensitivePlace<S> sensitive(Class<S> effect) {
    if (!SensitiveSuspend.class.isAssignableFrom(effect)) throw new IllegalArgumentException("okay.resumable: "
        + effect.getName() + " is not a sensitive effect (a subclass of SensitiveSuspend)");
    return new SensitivePlace<>(effect);
  }

  public static final class SensitivePlace<S extends SensitiveSuspend> {
    private final Class<S> effect;

    private SensitivePlace(Class<S> effect) { this.effect = effect; }
  }

  public static <S extends SensitiveSuspend> SensitiveRow1<S> row(SensitivePlace<S> s) {
    return new SensitiveRow1<>(places(s));
  }

  public static <S extends SensitiveSuspend, E1 extends Suspend> SensitiveRow2<S, E1> row(SensitivePlace<S> s,
      Class<E1> e1) {
    return new SensitiveRow2<>(places(s, e1));
  }

  public static <S extends SensitiveSuspend, E1 extends Suspend, E2 extends Suspend> SensitiveRow3<S, E1, E2> row(
      SensitivePlace<S> s, Class<E1> e1, Class<E2> e2) {
    return new SensitiveRow3<>(places(s, e1, e2));
  }

  public static <S extends SensitiveSuspend, E1 extends Suspend, E2 extends Suspend, E3 extends Suspend>
      SensitiveRow4<S, E1, E2, E3> row(SensitivePlace<S> s, Class<E1> e1, Class<E2> e2, Class<E3> e3) {
    return new SensitiveRow4<>(places(s, e1, e2, e3));
  }

  /** a row whose first place is sensitive: its programs are run, and that effect handled, under its audit only.
   * Open after its effects, unlike a plain row: an audit's interceptor asks effects of its own outside the program,
   * a trail, a failure — the program fits a row that has them further out */
  public static final class SensitiveRow1<S extends SensitiveSuspend> {
    private final Class<?>[] row;

    private SensitiveRow1(Class<?>[] row) { this.row = row; }

    public <A> Comp<A, Sensitive<S, ?>> of(Body1<A, S> body) {
      return Driver.program(row, body::run);
    }
  }

  public static final class SensitiveRow2<S extends SensitiveSuspend, E1 extends Suspend> {
    private final Class<?>[] row;

    private SensitiveRow2(Class<?>[] row) { this.row = row; }

    public <A> Comp<A, Sensitive<S, ? extends Eff<E1, ?>>> of(Body2<A, S, E1> body) {
      return Driver.program(row, body::run);
    }
  }

  public static final class SensitiveRow3<S extends SensitiveSuspend, E1 extends Suspend, E2 extends Suspend> {
    private final Class<?>[] row;

    private SensitiveRow3(Class<?>[] row) { this.row = row; }

    public <A> Comp<A, Sensitive<S, ? extends Eff<E1, ? extends Eff<E2, ?>>>> of(Body3<A, S, E1, E2> body) {
      return Driver.program(row, body::run);
    }
  }

  public static final class SensitiveRow4<S extends SensitiveSuspend, E1 extends Suspend, E2 extends Suspend,
      E3 extends Suspend> {
    private final Class<?>[] row;

    private SensitiveRow4(Class<?>[] row) { this.row = row; }

    public <A> Comp<A, Sensitive<S, ? extends Eff<E1, ? extends Eff<E2, ? extends Eff<E3, ?>>>>> of(
        Body4<A, S, E1, E2, E3> body) {
      return Driver.program(row, body::run);
    }
  }

  // ---- handlers in direct style

  /** What a clause that resumes the rest declares, beside the effects it performs: {@code k.resume} suspends the
   * clause while the rest of the handled program runs, and javac then keeps it out of code that cannot suspend (a
   * JDK lambda). No operation is of it */
  public static final class Resumes extends Suspend {
    private Resumes() {}
  }

  /** THE REST of a handled program, given to a direct-style clause: {@code k.resume(op, x)} runs it with the answer
   * {@code x} to the handler's result, and returns that — once, many times, or never ({@code k.unwind()}: its
   * finalizers run). Deep, as okay.rows' {@code K}: the rest runs under the handler again. Called by the handler's
   * own clauses only; nothing is replayed — the clause's frames are saved while the rest runs, and resumed with its
   * result. {@code return k.resume(..)} adds nothing for each operation: in tail position the clause's program is the
   * resumption itself */
  public static final class K<B> extends Driver.Prog {
    private final okay.rows.K<B, ?> k;
    /** the operation asked, and the handler whose clause was given this */
    final Object asked;
    final Adapter<?, ?, B, ?> handler;

    K(okay.rows.K<B, ?> k, Class<?>[] row, Object asked, Adapter<?, ?, B, ?> handler) {
      super(row);
      this.k = k;
      this.asked = asked;
      this.handler = handler;
    }

    /** the rest resumed with the answer to {@code op}, the operation asked, and run to the handler's result */
    public <X> B resume(Op<?, X, ?> op, X answer) throws Resumes {
      Driver.valid(this);
      return Driver.await(k.resume(op, answer));
    }

    /** the same, of a sensitive operation: by a handler under its audit ({@code Audit.handle}) */
    public <X> B resume(Sensitive.Op<?, X, ?> op, X answer) throws Resumes {
      Driver.valid(this);
      return Driver.await(k.resume(op, answer));
    }

    /** the rest left: its finalizers run, the innermost first; nothing else of it */
    public void unwind() throws Resumes {
      Driver.valid(this);
      Driver.await(k.unwind());
    }
  }

  /** A GENERAL HANDLER IN DIRECT STYLE of the effect {@code F}, in a program of an {@code A}: a {@code B}. Its
   * clauses are plain methods, transformed as a body is: {@code on} is given the operation and the rest, and returns
   * the handler's result. Made an okay.rows {@code Handler} by {@link Direct#handler}, so it is installed, and
   * composes, as any: {@code handle}, {@code intercept}, {@code Audit.handle}. Declaring {@code okay.rows.Once}
   * declares it of that handler */
  public interface Handler0<F, A, B> {
    B ret(A a);

    B on(F op, K<B> k) throws Resumes;

    /** for an interceptor: of the operations of its class, those it takes */
    default boolean mine(F op) { return true; }
  }

  /** the same, its clauses performing {@code E1}, the effect next outside it: {@code Direct.row(E1.class)
   * .handler(h)}. javac refuses a clause that performs any other */
  public interface Handler1<F, A, B, E1 extends Suspend> {
    B ret(A a) throws E1;

    B on(F op, K<B> k) throws E1, Resumes;

    default boolean mine(F op) { return true; }
  }

  public interface Handler2<F, A, B, E1 extends Suspend, E2 extends Suspend> {
    B ret(A a) throws E1, E2;

    B on(F op, K<B> k) throws E1, E2, Resumes;

    default boolean mine(F op) { return true; }
  }

  public interface Handler3<F, A, B, E1 extends Suspend, E2 extends Suspend, E3 extends Suspend> {
    B ret(A a) throws E1, E2, E3;

    B on(F op, K<B> k) throws E1, E2, E3, Resumes;

    default boolean mine(F op) { return true; }
  }

  public interface Handler4<F, A, B, E1 extends Suspend, E2 extends Suspend, E3 extends Suspend,
      E4 extends Suspend> {
    B ret(A a) throws E1, E2, E3, E4;

    B on(F op, K<B> k) throws E1, E2, E3, E4, Resumes;

    default boolean mine(F op) { return true; }
  }

  /** a direct-style handler whose clauses perform nothing outside it, as an okay.rows {@code Handler} */
  public static <F, A, B, R> okay.rows.Handler<F, A, B, R> handler(Handler0<F, A, B> h) {
    return adapt(new Class<?>[0], h, h::ret, h::on, h::mine);
  }

  /** A HANDLER IN PLACE in direct style: each operation answered at once from a state, per path — and so on the
   * body's own stack, nothing suspended. As okay.rows' {@code InPlace}, its result a value */
  public interface InPlace<F, S, A, B> {
    B ret(S state, A a);

    Cell.Answered answer(Cell<S> state, F op);

    default boolean mine(F op) { return true; }
  }

  public static <F, S, A, B, R> okay.rows.InPlace<F, S, A, B, R> inPlace(InPlace<F, S, A, B> h) {
    return new okay.rows.InPlace<>() {
      public Comp<B, R> ret(S state, A a) { return Effects.pure(h.ret(state, a)); }
      public Cell.Answered answer(Cell<S> state, F op) { return h.answer(state, op); }
      public boolean mine(F op) { return h.mine(op); }
    };
  }

  /** a clause as the adapter calls it */
  interface Ret<A, B> {
    B apply(A a) throws Exception;
  }

  interface On<F, B> {
    B apply(F op, K<B> k) throws Exception;
  }

  /** the okay.rows handler of a direct-style one: each clause run as a program of the row outside, {@code row} its
   * effects' classes — one array for each handler, by which its {@code k} knows its clauses */
  private static <F, A, B, R> okay.rows.Handler<F, A, B, R> adapt(Class<?>[] row, Object h, Ret<A, B> ret,
      On<F, B> on, Predicate<F> mine) {
    return h instanceof Once ? new OnceAdapter<>(row, ret, on, mine) : new Adapter<>(row, ret, on, mine);
  }

  /** asked from the body's stack ({@link Here.Eager}): its clause made there, and answered at once if it resumes in
   * tail position */
  static class Adapter<F, A, B, R> implements okay.rows.Handler<F, A, B, R>, Here.Eager {
    private final Class<?>[] row;
    private final Ret<A, B> ret;
    private final On<F, B> on;
    private final Predicate<F> mine;
    /** the program of each ret: a clause given no k */
    private final Driver.Prog rets;

    Adapter(Class<?>[] row, Ret<A, B> ret, On<F, B> on, Predicate<F> mine) {
      this.row = row;
      this.ret = ret;
      this.on = on;
      this.mine = mine;
      this.rets = new Driver.Prog(row);
    }

    /** how often its clauses resumed the rest in tail position, and not: counted from any thread, unsynchronized —
     * a count lost only changes which way an operation is asked, never its answer */
    private int tails;
    private int nonTails;

    void resumed(boolean tail) {
      if (tail) tails++;
      else nonTails++;
    }

    /** asked from the body's stack while its clauses resume in tail position as often as not: a handler that
     * resumes otherwise (a choice, a log after the rest) gains nothing there, its frames saved anyway */
    @Override
    public boolean eager() {
      return nonTails <= tails;
    }

    public Comp<B, R> ret(A a) {
      return Driver.clause(rets, () -> ret.apply(a), Driver.RET);
    }

    public Comp<B, R> on(F op, okay.rows.K<B, R> k) {
      K<B> rest = new K<>(k, row, op, this);
      return Driver.clause(rest, () -> on.apply(op, rest), Driver.ON);
    }

    public boolean mine(F op) {
      return mine.test(op);
    }
  }

  private static final class OnceAdapter<F, A, B, R> extends Adapter<F, A, B, R> implements Once {
    OnceAdapter(Class<?>[] row, Ret<A, B> ret, On<F, B> on, Predicate<F> mine) {
      super(row, ret, on, mine);
    }
  }
}
