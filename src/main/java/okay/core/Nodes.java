package okay.core;

import java.util.function.Function;
import java.util.function.Supplier;

/** {@link Comp}'S NODES, what a program is made of, and the frames a value goes through — run by the {@link Machine} */
@SuppressWarnings({"unchecked", "rawtypes"})
final class Nodes {
  private Nodes() {}

  /** a node: its step the machine's, each kind's its own method (see {@link Machine#run}) — an abstract class, not an
   * interface, so the loop's call to it is a virtual call, one load, not an interface's search */
  abstract static sealed class Node<L, A> implements Comp<A, L>
      permits Return, Bind, Map, Delay, Sync, Shift0, Suspend, Handle, Reset, Push, Machine.Resumption,
      Kernel.Perform, Kernel.Shift0K, Kernel.Fail, Kernel.Signalled, Kernel.Foreign, Rows.Ask, Rows.Enter, Rows.Here {
    abstract Comp<?, ?> step(Machine.Run m);
  }

  static final class Return<L, A> extends Node<L, A> {
    private final A value;

    Return(A value) {
      this.value = value;
    }

    A value() {
      return value;
    }
    Comp<?, ?> step(Machine.Run m) {
      return m.value(value);
    }
  }

  static final class Bind<L, A, B> extends Node<L, B> {
    private final Comp<A, L> m;
    private final Function f;

    Bind(Comp<A, L> m, Function f) {
      this.m = m;
      this.f = f;
    }

    Comp<A, L> m() {
      return m;
    }

    Function f() {
      return f;
    }
    Comp<?, ?> step(Machine.Run m) {
      return m.bind(this);
    }
  }

  static final class Map<L, A, B> extends Node<L, B> {
    private final Comp<A, L> m;
    private final Function<? super A, ? extends B> f;

    Map(Comp<A, L> m, Function<? super A, ? extends B> f) {
      this.m = m;
      this.f = f;
    }

    Comp<A, L> m() {
      return m;
    }

    Function<? super A, ? extends B> f() {
      return f;
    }
    Comp<?, ?> step(Machine.Run m) {
      return m.map(this);
    }
  }

  /** a program built when the loop gets to it: a tail call, one node */
  static final class Delay<L, A> extends Node<L, A> {
    private final Supplier<? extends Comp<A, ?>> c;

    Delay(Supplier<? extends Comp<A, ?>> c) {
      this.c = c;
    }

    Supplier<? extends Comp<A, ?>> c() {
      return c;
    }
    Comp<?, ?> step(Machine.Run m) {
      return c.get();
    }
  }

  /** a value computed when the loop gets to it: an operation answered in place from a cell, one node */
  static final class Sync<L, A> extends Node<L, A> {
    private final Supplier<? extends A> a;

    Sync(Supplier<? extends A> a) {
      this.a = a;
    }

    Supplier<? extends A> a() {
      return a;
    }
    Comp<?, ?> step(Machine.Run m) {
      return m.value(a.get());
    }
  }

  static final class Shift0<M, X> extends Node<M, X> {
    private final Prompt<?, ?, ?> prompt;
    private final Function f;

    Shift0(Prompt<?, ?, ?> prompt, Function f) {
      this.prompt = prompt;
      this.f = f;
    }

    Prompt<?, ?, ?> prompt() {
      return prompt;
    }

    Function f() {
      return f;
    }
    Comp<?, ?> step(Machine.Run m) {
      return m.shift0(this);
    }

    /** an operation bound: one node, {@link Suspend} */
    @Override
    public <B> Comp<B, M> flatMap(Function<? super X, ? extends Comp<B, ? super M>> g) {
      return new Suspend<>(prompt, f, g);
    }
  }

  /** a {@code shift0} with the rest bound to its value: {@code Bind(Shift0(p, f), k)} as one node — what
   * {@code flatMap} on an operation makes, and what a capture crossing a delimiter goes on outside as */
  static final class Suspend<L, B> extends Node<L, B> {
    private final Prompt<?, ?, ?> prompt;
    private final Function f;
    private final Function k;

    Suspend(Prompt<?, ?, ?> prompt, Function f, Function k) {
      this.prompt = prompt;
      this.f = f;
      this.k = k;
    }

    Prompt<?, ?, ?> prompt() {
      return prompt;
    }

    Function f() {
      return f;
    }

    Function k() {
      return k;
    }
    Comp<?, ?> step(Machine.Run m) {
      return m.suspend(this);
    }
  }

  static final class Handle<L, A, R> extends Node<L, R> {
    private final Comp.Body<L, A, R> body;
    private final Function ret;
    /** a kernel delimiter's frame, given to its prompt; null for okay-java's own */
    private final Object frame;

    Handle(Comp.Body<L, A, R> body, Function ret) {
      this(body, ret, null);
    }

    Handle(Comp.Body<L, A, R> body, Function ret, Object frame) {
      this.body = body;
      this.ret = ret;
      this.frame = frame;
    }

    Object frame() {
      return frame;
    }

    Comp.Body<L, A, R> body() {
      return body;
    }

    Function ret() {
      return ret;
    }
    Comp<?, ?> step(Machine.Run m) {
      return m.handle(this, null);
    }
  }

  /** a body that is a program already, at the level outside — a guard's: no fresh level, nothing to give the prompt */
  static <L, A, R> Comp.Body<L, A, R> plain(Comp<A, L> body) {
    return new Comp.Body<>() {
      public <M extends L> Comp<A, M> in(Prompt<L, M, R> p) {
        return body.at();
      }
    };
  }

  /** a delimiter under a prompt there already: {@code p.reset(body)}, its answer the body's value */
  static final class Reset<L, R> extends Node<L, R> {
    private final Prompt<?, ?, ?> prompt;
    private final Comp<R, ?> body;

    Reset(Prompt<?, ?, ?> prompt, Comp<R, ?> body) {
      this.prompt = prompt;
      this.body = body;
    }

    Prompt<?, ?, ?> prompt() {
      return prompt;
    }

    Comp<R, ?> body() {
      return body;
    }
    Comp<?, ?> step(Machine.Run m) {
      return m.reset(this);
    }
  }

  /** {@code m} with the rest as a stack already: what applying a captured k makes */
  static final class Push<L, B> extends Node<L, B> {
    private final Comp<?, ?> m;
    private final Frames k;

    Push(Comp<?, ?> m, Frames k) {
      this.m = m;
      this.k = k;
    }

    Comp<?, ?> m() {
      return m;
    }

    Frames k() {
      return k;
    }
    Comp<?, ?> step(Machine.Run run) {
      return run.push(this);
    }
  }

  sealed interface Frames extends Function<Object, Comp<?, ?>> {
    Frames END = new End();

    record End() implements Frames {}

    record Then(Function f, Frames next) implements Frames {}

    record Mapped(Function f, Frames next) implements Frames {}

    @Override
    default Comp<?, ?> apply(Object a) {
      return switch (this) {
        case End _ -> new Return<>(a);
        case Then(var f, var next) -> next instanceof End ? (Comp) f.apply(a) : new Push<>((Comp) f.apply(a), next);
        case Mapped(var f, var next) ->
            next instanceof End ? new Return<>(f.apply(a)) : new Push<>(new Return<>(f.apply(a)), next);
      };
    }
  }
}
