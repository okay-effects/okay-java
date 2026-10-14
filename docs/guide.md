# Guide

This guide covers writing programs with effects and delimited control, running them, and writing effects of your
own. `GuideTest` takes every `java` block here, compiles the blocks together into one class and runs it. A block
that starts with `// run` is executed, and its `check(actual, expected)` calls must hold. A block that starts with
`// does not compile` is compiled on its own and must fail.

```java
import okay.core.*;           // Eff, Prompt, Cont
import okay.core.Eff.Top;     // the level outside every handler
import okay.std.*;            // the standard effects
import okay.std.State.Final;
import okay.std.Writer.Log;
import java.util.ArrayList;
import java.util.List;
```

## Programs and their level

A program is a value of `Eff<L, A>`, a program of value `A` at the level `L`. The level stands for the handlers in
force where the program runs. `Top` is the level outside every handler. Each handler's body is at a level of its
own, fresh, one further in. A program that needs no particular handler is generic in its level:

```java
static <L> Eff<L, Integer> twice(int x) {
  return Eff.<L, Integer>pure(x).map(y -> y * 2);
}

static <L> Eff<L, Boolean> even(int i) {   // a tail call in constant stack: delay builds the rest when run
  return i == 0 ? Eff.pure(true) : Eff.delay(() -> odd(i - 1));
}

static <L> Eff<L, Boolean> odd(int i) {
  return i == 0 ? Eff.pure(false) : Eff.delay(() -> even(i - 1));
}
```

- `flatMap`, `map` and `then` compose programs. `pure(a)` is a value as a program. `delay(() -> p)` builds `p` when
  it is run.
- `Eff.run` runs a program at the top and gives its value. Only a program at `Top` runs, so a program with a handler
  still missing does not compile.

```java
// run
check(Eff.run(Guide.<Top>twice(21)), 42);
check(Eff.run(Guide.<Top>even(100_001)), false);
```

## The standard effects

Each effect is an interface at a level, its capability. A handler gives the capability to a body, which is an
object with one generic method, `<M extends L> Eff<M, A> in(Capability<M, …> c)`. `M` is the body's own level. Java
writes a generic method as an anonymous class, not a lambda:

```java
// run
Log<String, Integer> log = Eff.run(Writer.writer(new Writer.Body<Top, String, Integer>() {
  public <M extends Top> Eff<M, Integer> in(Writer<M, String> w) {
    return w.tell("a").then(w.tell("b")).map(_ -> 2);
  }
}));
check(log, new Log<>(List.of("a", "b"), 2));
```

| Effect | Operations | Handlers |
|---|---|---|
| `Reader<L, E>` | `ask` | `reader(e, body)`: answered in place, no prompt |
| `State<L, S>` | `get`, `put`, `modify` | `state(s0, body)` → `Final(s, a)`, functional; `cell(s0, body)`, a cell answered in place |
| `Writer<L, W>` | `tell` | `writer(body)` → `Log(ws, a)` |
| `Throws<L, E>` | `raise` | `catching(body, e -> recovery)`, `attempt(body)` → `Result` |
| `Choose<L>` | `among`, `fail` | `all(body)` → `List`, `first(body)` → `Optional` |
| `PState<L, S>` | `get`, `put` (a new type), `done` | `pstate(s0, body)` |
| `Async<L>` | `async`, `await`, `raise` | `runAsync(body)` → `CompletableFuture`; `run(t, body)` |
| `Emit<L, X>` | `emit` | `forEach(body, f)`, `collect(body)`, `generate(body, next, done)`, `iterator(body)`, `stream(body)` |

### Bodies as methods

A lambda cannot implement a generic method, but a method reference can. So a body can be a method of its own,
generic in its level, and a handler is given it by reference. Its level is inferred:

```java
static <M> Eff<M, Integer> counter(State<M, Integer> st) {
  return st.modify(x -> x + 1).then(st.get());
}
```

```java
// run
check(Eff.run(State.state(0, Guide::counter)), new Final<>(1, 1));
```

A body that needs a capability from outside keeps it in a record, and the record's method is at the level in.
There the handler's level is written out: `Choose.<M, Integer>all(new Stepping<>(st)::in)`. The anonymous class is
for a body that needs local variables.

## Several effects

A program that needs several effects takes their capabilities, all at its level. Its parameters are its row:

```java
static <L> Eff<L, Integer> step(State<L, Integer> st, Choose<L> ch) {
  return ch.among(List.of(1, 2)).flatMap(x -> st.modify(s -> s + x)).then(st.get());
}
```

The handlers nest, outside in. A capability from outside is moved to the level in by its `at()`. The order of the
handlers is the meaning of the program:

```java
// run
// the state outside the choice: one state, threaded through the paths in turn
Final<Integer, List<Integer>> threaded = Eff.run(State.state(0, new State.Body<Top, Integer, List<Integer>>() {
  public <M extends Top> Eff<M, List<Integer>> in(State<M, Integer> st) {
    return Choose.all(new Choose.Body<M, Integer>() {
      public <N extends M> Eff<N, Integer> in(Choose<N> ch) {
        return step(st.at(), ch);
      }
    });
  }
}));
check(threaded, new Final<>(3, List.of(1, 3)));

// the choice outside the state: a state per path
List<Final<Integer, Integer>> perPath = Eff.run(Choose.all(new Choose.Body<Top, Final<Integer, Integer>>() {
  public <M extends Top> Eff<M, Final<Integer, Integer>> in(Choose<M> ch) {
    return State.state(0, new State.Body<M, Integer, Integer>() {
      public <N extends M> Eff<N, Integer> in(State<N, Integer> st) {
        return step(st, ch.at());
      }
    });
  }
}));
check(perPath, List.of(new Final<>(1, 1), new Final<>(2, 2)));
```

A program of a level further out runs further in as it is. A bind's function may give one directly. At the head of
a chain it is moved in by `.at()`, for example `outer.get().<N>at().flatMap(…)`.

Two handlers of the same effect can be in force at once. Each operation goes to the handler its capability came
from, not to the nearest of its type:

```java
// run
Final<Integer, Final<Integer, Integer>> two = Eff.run(State.state(0, new State.Body<Top, Integer, Final<Integer, Integer>>() {
  public <M extends Top> Eff<M, Final<Integer, Integer>> in(State<M, Integer> outer) {
    return State.state(100, new State.Body<M, Integer, Integer>() {
      public <N extends M> Eff<N, Integer> in(State<N, Integer> inner) {
        return outer.put(1).<N>at().then(inner.modify(x -> x + 1)).then(outer.get()).flatMap(a -> inner.get().map(b -> a + b));
      }
    });
  }
}));
check(two, new Final<>(1, new Final<>(101, 102)));
```

## An effect of your own

An effect is an interface at a level, with its operations and an `at()`. A handler's body is a generic method at a
fresh level:

```java
interface Ask<L> {
  Eff<L, Integer> number();

  @SuppressWarnings("unchecked")
  default <N extends L> Ask<N> at() {
    return (Ask<N>) (Ask<?>) this;
  }
}

interface Asked<L, A> {
  <M extends L> Eff<M, A> in(Ask<M> a);
}
```

A handler is `Eff.handle(body, ret)`, a delimiter under a fresh prompt. Its body gets the prompt, a `Prompt<L, M, R>`:
the handler at `L`, its body at `M`, its answer `R`. An operation is `p.shift0(k -> clause)`. `k` is the rest of
the body up to the handler, the handler included, and the clause runs outside the handler, at `L`, in its place:

```java
/** every number answered n, the body's value doubled */
static <L> Eff<L, Integer> asking(int n, Asked<L, Integer> body) {
  return Eff.handle(new Eff.Body<L, Integer, Integer>() {
    public <M extends L> Eff<M, Integer> in(Prompt<L, M, Integer> p) {
      return body.in(() -> p.shift0(k -> k.apply(n)));
    }
  }, a -> Eff.pure(a * 2));
}
```

```java
// run
check(Eff.run(asking(20, new Asked<Top, Integer>() {
  public <M extends Top> Eff<M, Integer> in(Ask<M> a) {
    return a.number().flatMap(x -> a.number().map(y -> x + y + 2));
  }
})), 84);
```

A clause can do anything at the handler's level: resume `k` once (as above), not at all (`Throws`), or several
times (`Choose`). It can also perform effects of the handlers further out. A recovery that raises again raises to
the handler outside.

### Answering in place

A handler whose clause is `k(value)` needs no prompt at all. `shift0 k. k(v)` is `v`, so the capability can just
return the value. No capture is made, and the operation costs the same however many handlers are between it and
its handler:

```java
static <L, A> Eff<L, A> answering(int n, Asked<L, A> body) {
  Eff<L, Integer> v = Eff.pure(n);
  return body.<L>in(() -> v);
}
```

```java
// run
check(Eff.run(answering(41, new Asked<Top, Integer>() {
  public <M extends Top> Eff<M, Integer> in(Ask<M> a) {
    return a.number().map(x -> x + 1);
  }
})), 42);
```

`Reader` and `Emit.forEach` are written so.

## What does not compile

The body's level is a type variable nobody outside the body can name. So nothing made in a body can be used once
the body is left: not a capability, not a prompt, not a program built there. `LeakTest` tries each way out. A
capability returned from its handler can only be typed `State<?, Integer>` outside, and no program is at its level:

```java
// does not compile
State<?, Integer> leaked = Eff.run(State.state(0, new State.Body<Top, Integer, State<?, Integer>>() {
  public <M extends Top> Eff<M, State<?, Integer>> in(State<M, Integer> st) { return Eff.pure(st); }
})).value();
Integer n = Eff.run(leaked.get());   // a level no program is at
```

The same holds in another handler's body at the same place: its level is another variable. A body by method
reference may even return its capability (javac infers the handler's answer with the body's own level in it), and
the capability is of no use either: its level is no program's.

## Delimited control

A reset is a delimiter that answers no operation. Its body gets its prompt, and `p.shift(k -> body)` is
Danvy–Filinski's `shift` to it. `k` is the rest up to the reset, and the body runs under the reset again:

```java
// run
check(Eff.run(Eff.reset(new Eff.Body<Top, Integer, Integer>() {
  public <M extends Top> Eff<M, Integer> in(Prompt<Top, M, Integer> p) {
    return p.<Integer>shift(k -> k.apply(10).flatMap(k).at()).map(x -> x + 1);
  }
})), 12);
```

- `k` gives a program of the level outside the reset. In the shift's body, a level in, it is moved in by `.at()`.
- A shift goes to its prompt, not to the nearest delimiter, so it can cross a nearer reset.
- `p.shift0(k -> body)` runs the body outside the delimiter, in its place. Handlers are made of it.
- A shift to a handler's prompt captures the rest with the handler. Each resumption has the handler in force, its
  `ret` included. A raise in `k` is that `k`'s own failure.

## Failures

A function of the program's that throws — a bind's, a map's, a clause — is a failure of the program: it unwinds from
where it was, through the handlers in force, to the nearest guard that takes it. `Eff.recover(body, h)` gives the
failure to `h`, whose program runs in the body's place. `Eff.ensuring(body, fin)` runs `fin` after the body whatever
way it is left, then goes on the same way. `Eff.failed(e)` is a failure as a program. A failure no guard takes leaves
`Eff.run` thrown, as before:

```java
// run
Eff<Top, Integer> failing = Eff.<Top, Integer>pure(1).map(x -> {
  throw new IllegalStateException("boom");
});
check(Eff.run(Eff.recover(failing, t -> Eff.pure(t.getMessage().length()))), 4);

List<String> log = new ArrayList<>();
Eff<Top, Void> fin = Eff.sync(() -> {
  log.add("fin");
  return null;
});
check(Eff.run(Eff.recover(Eff.ensuring(failing, fin), t -> Eff.pure(-1))), -1);
check(log, List.of("fin"));
```

- A clause runs outside its handler, so a guard inside the handler's body does not take the clause's failure; a
  guard outside the handler does.
- A `shift0` across a guard keeps it in `k`: each resumption has the guard in force again, and `fin` runs once a
  resumption.
- A failing handler of `recover`, or a failing `fin`, is a failure in the guard's place: the next guard out takes it.
- Errors of the JVM (`VirtualMachineError`, `LinkageError`) are not failures and are never caught.
- `Eff.uncancelable(body)` is for programs of the kernel's (`docs/kernel.md`): a cancellation of a handed-out
  operation waits for the region to end. Nothing of okay-java's own cancels.

## A state whose type changes

`PState` is a typestate. `put` of a `T` gives the capability of a `PState<L, T>`, and the one it was put by is stale
from then on. Using a stale capability fails at run time, because Java has no linear types:

```java
// run
Final<String, Integer> r = Eff.run(PState.pstate(7, new PState.Body<Top, Integer, String, Integer>() {
  public <M extends Top> Eff<M, Final<String, Integer>> in(PState<M, Integer> st) {
    return st.get().flatMap(n -> st.put("x".repeat(n))).flatMap(st2 -> st2.get().flatMap(s -> st2.done(s.length())));
  }
}));
check(r, new Final<>("xxxxxxx", 7));
```

`ContPState`, on `Cont`, carries the state's type in the answer type instead, okay-core's way. There, a `get` of the
wrong type does not compile.

## Generators

`Emit.stream` makes the body a lazy `java.util.stream.Stream`: an element pulled is a run of the rest, so `limit`,
`takeWhile` and the like stop the body where they stop. The body may go on forever. `Emit.iterator` gives the same
as an `Iterator`:

```java
static <L> Eff<L, Void> naturals(Emit<L, Integer> e, int i) {
  return e.emit(i).flatMap(_ -> naturals(e, i + 1));
}
```

```java
// run
List<Integer> squares = Emit.stream(new Emit.Body<Top, Integer, Void>() {
  public <M extends Top> Eff<M, Void> in(Emit<M, Integer> e) {
    return naturals(e, 0);
  }
}).map(x -> x * x).limit(4).toList();
check(squares, List.of(0, 1, 4, 9));
```

Under effects still to be handled, `Emit.generate(body, next, done)` makes a generator of the consumer's own type.
Each element comes with the rest, a program at the generator's level, and the body's other effects go to their
handlers as the rest is run:

```java
sealed interface Gen<L> {
  record More<L>(int x, Eff<L, Gen<L>> rest) implements Gen<L> {}
  record End<L>() implements Gen<L> {}
}

static <L> Eff<L, Integer> sumOf(int n, Eff<L, Gen<L>> g) {   // the first n, the rest run only as far as asked
  return n == 0 ? Eff.pure(0) : g.flatMap(gen -> switch (gen) {
    case Gen.More<L>(var x, var rest) -> sumOf(n - 1, rest).map(s -> s + x);
    case Gen.End<L> _ -> Eff.pure(0);
  });
}

static <L> Eff<L, Void> told(Writer<L, String> w, Emit<L, Integer> e, int i) {
  return w.tell("made " + i).then(e.emit(i)).flatMap(_ -> told(w, e, i + 1));
}
```

```java
// run
Log<String, Integer> two = Eff.run(Writer.writer(new Writer.Body<Top, String, Integer>() {
  public <M extends Top> Eff<M, Integer> in(Writer<M, String> w) {
    return sumOf(2, Emit.generate(new Emit.Body<M, Integer, Void>() {
      public <N extends M> Eff<N, Void> in(Emit<N, Integer> e) {
        return told(w.at(), e, 1);
      }
    }, Gen.More::new, new Gen.End<>()));
  }
}));
check(two, new Log<>(List.of("made 1", "made 2"), 3));   // two pulled, two told: the third never made
```

## Async

`Async.runAsync(body)` runs the body at the top and gives a `CompletableFuture` of its value. `await` waits for a
callback or a `CompletionStage`. When the callback comes later, the run goes on in the callback's thread, and the
handlers inside go on with it:

```java
// run
check(Async.runAsync(new Async.Body<Integer>() {
  public <M extends Top> Eff<M, Integer> in(Async<M> as) {
    return as.async(() -> 20).flatMap(a -> as.await(java.util.concurrent.CompletableFuture.supplyAsync(() -> a + 1)));
  }
}).join(), 21);
```

On virtual threads: `blocking(() -> …)` runs a blocking call (a database, a file, a socket) on a virtual thread of
its own. The run waits for it without holding a thread, and goes on in that thread. `fork(body)` starts a run of
its own on a virtual thread, and `await(future)` joins it:

```java
// run
check(Async.runAsync(new Async.Body<Integer>() {
  public <M extends Top> Eff<M, Integer> in(Async<M> as) {
    return as.fork(new Async.Body<Integer>() {
      public <N extends Top> Eff<N, Integer> in(Async<N> other) {
        return other.blocking(() -> { Thread.sleep(10); return 40; });
      }
    }).flatMap(f -> as.blocking(() -> 2).flatMap(b -> as.await(f).map(a -> a + b)));
  }
}).join(), 42);
```

## Performance notes

- Answering in place costs a call: about 5 ns an operation, the same across 1 or 16 handlers.
- A general clause costs a capture, about 9 ns, and about 2 ns more for each handler crossed on the way to its own
  (42 ns across 16). Handlers nest as deep as the heap allows: the machine keeps its delimiters on the heap.
- Measure a program of several effects, not one effect in a loop: `MixedBenchmark`. A microbenchmark shows each of
  the machine's call sites one function, which the JIT inlines, and a program does not.
- A value boxed by `Integer.valueOf` (autoboxing) in a hot chain of maps can keep the JIT's box elimination from
  working. A box of your own (`record Int(int value)`) is not cached and is never slower. `MonadsBenchmark`'s
  `captureDeep`, `captureDeepNew` and `captureDeepRecord` measure it.
