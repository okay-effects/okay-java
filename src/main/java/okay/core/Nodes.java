package okay.core;

import java.util.function.Function;
import java.util.function.Supplier;

/** {@link Eff}'S NODES, what a program is made of, and the frames a value goes through — run by the {@link Machine} */
@SuppressWarnings({"unchecked", "rawtypes"})
final class Nodes {
  private Nodes() {}

  /** a node: its step the machine's, each kind's its own method (see {@link Machine#run}) — an abstract class, not an
   * interface, so the loop's call to it is a virtual call, one load, not an interface's search */
  abstract static sealed class Node<L, A> implements Eff<L, A>
      permits Return, Bind, Map, Delay, Sync, Shift0, Suspend, Handle, Reset, Push, Machine.Resumption {
    abstract Eff<?, ?> step(Machine.Run m);
  }

  static final class Return<L, A> extends Node<L, A> {
    private final A value;

    Return(A value) {
      this.value = value;
    }

    A value() {
      return value;
    }
    Eff<?, ?> step(Machine.Run m) {
      return m.value(value);
    }
  }

  static final class Bind<L, A, B> extends Node<L, B> {
    private final Eff<L, A> m;
    private final Function f;

    Bind(Eff<L, A> m, Function f) {
      this.m = m;
      this.f = f;
    }

    Eff<L, A> m() {
      return m;
    }

    Function f() {
      return f;
    }
    Eff<?, ?> step(Machine.Run m) {
      return m.bind(this);
    }
  }

  static final class Map<L, A, B> extends Node<L, B> {
    private final Eff<L, A> m;
    private final Function<? super A, ? extends B> f;

    Map(Eff<L, A> m, Function<? super A, ? extends B> f) {
      this.m = m;
      this.f = f;
    }

    Eff<L, A> m() {
      return m;
    }

    Function<? super A, ? extends B> f() {
      return f;
    }
    Eff<?, ?> step(Machine.Run m) {
      return m.map(this);
    }
  }

  /** a program built when the loop gets to it: a tail call, one node */
  static final class Delay<L, A> extends Node<L, A> {
    private final Supplier<? extends Eff<?, A>> c;

    Delay(Supplier<? extends Eff<?, A>> c) {
      this.c = c;
    }

    Supplier<? extends Eff<?, A>> c() {
      return c;
    }
    Eff<?, ?> step(Machine.Run m) {
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
    Eff<?, ?> step(Machine.Run m) {
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
    Eff<?, ?> step(Machine.Run m) {
      return m.shift0(this);
    }

    /** an operation bound: one node, {@link Suspend} */
    @Override
    public <B> Eff<M, B> flatMap(Function<? super X, ? extends Eff<? super M, B>> g) {
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
    Eff<?, ?> step(Machine.Run m) {
      return m.suspend(this);
    }
  }

  static final class Handle<L, A, R> extends Node<L, R> {
    private final Eff.Body<L, A, R> body;
    private final Function ret;

    Handle(Eff.Body<L, A, R> body, Function ret) {
      this.body = body;
      this.ret = ret;
    }

    Eff.Body<L, A, R> body() {
      return body;
    }

    Function ret() {
      return ret;
    }
    Eff<?, ?> step(Machine.Run m) {
      return m.handle(this, null);
    }
  }

  /** a delimiter under a prompt there already: {@code p.reset(body)}, its answer the body's value */
  static final class Reset<L, R> extends Node<L, R> {
    private final Prompt<?, ?, ?> prompt;
    private final Eff<?, R> body;

    Reset(Prompt<?, ?, ?> prompt, Eff<?, R> body) {
      this.prompt = prompt;
      this.body = body;
    }

    Prompt<?, ?, ?> prompt() {
      return prompt;
    }

    Eff<?, R> body() {
      return body;
    }
    Eff<?, ?> step(Machine.Run m) {
      return m.reset(this);
    }
  }

  /** {@code m} with the rest as a stack already: what applying a captured k makes */
  static final class Push<L, B> extends Node<L, B> {
    private final Eff<?, ?> m;
    private final Frames k;

    Push(Eff<?, ?> m, Frames k) {
      this.m = m;
      this.k = k;
    }

    Eff<?, ?> m() {
      return m;
    }

    Frames k() {
      return k;
    }
    Eff<?, ?> step(Machine.Run run) {
      return run.push(this);
    }
  }

  sealed interface Frames extends Function<Object, Eff<?, ?>> {
    Frames END = new End();

    record End() implements Frames {}

    record Then(Function f, Frames next) implements Frames {}

    record Mapped(Function f, Frames next) implements Frames {}

    @Override
    default Eff<?, ?> apply(Object a) {
      return switch (this) {
        case End _ -> new Return<>(a);
        case Then(var f, var next) -> next instanceof End ? (Eff) f.apply(a) : new Push<>((Eff) f.apply(a), next);
        case Mapped(var f, var next) ->
            next instanceof End ? new Return<>(f.apply(a)) : new Push<>(new Return<>(f.apply(a)), next);
      };
    }
  }
}
