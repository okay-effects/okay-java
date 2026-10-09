# okay-java

okay-core's delimited continuations in Java 25, and effects on `shift0`.

## Layout

### `okay.core`

| File | What is in it |
|---|---|
| `Cont.java`, `Loop.java`, `Frames.java`, `Head.java`, `Push.java` | `Cont<I, O, A>`: `shift` and `reset` with answer-type modification, the loop in constant stack |
| `Cont0.java`, `Loop0.java` | `Cont0<I, O, A>`: `shift0` and `$` (Materzok–Biernacki), `reset0`, `lift`, the levels typed in the answers — okay-core's `Cont0` |
| `Eff.java`, `Prompt.java`, `EffLoop.java` | `Eff<L, A>`: effects on `shift0` by prompt at a level `L`, a handler's body at a fresh one; the answer types erased into the prompts |

### `okay.std`: effects written on `Eff`

Each effect is an interface at a level, `E<L, …>`, with an `at()` that moves a capability to a level further in.
Each handler takes a `Body` whose method `<M extends L> Eff<M, A> in(E<M, …> cap)` is the handler's body at its
fresh level.

| Effect | Operations | Handlers |
|---|---|---|
| `Reader<L, E>` | `ask` | `reader(e, body)`: answered in place, no prompt |
| `State<L, S>` | `get`, `put`, `modify` | `state(s0, body)` → `Final(s, a)`, functional: a k resumed twice resumes the state of its time; `cell(s0, body)`: okay-core's, answered in place from a cell, one per run |
| `Writer<L, W>` | `tell` | `writer(body)` → `Log(ws, a)` |
| `Throws<L, E>` | `raise` | `catching(body, e -> recovery)`, `attempt(body)` → `Result` (`Ok` / `Failed`) |
| `Choose<L>` | `among`, `fail` (multi-shot) | `all(body)` → `List`, `first(body)` → `Optional` |
| `PState<L, S>` | `get`, `put` (a new type), `done` | `pstate(s0, body)`: the type in the capability, a typestate |
| `PState0` | `get`, `put` (a new type) | `pstate(s0, body)` on `Cont0`: okay-core's, the type in the answer type |
| `Async<L>` | `async`, `await` (callback or `CompletionStage`), `raise` | `runAsync(body)` → `CompletableFuture`; `run(t, body)` waits, its failure raised to `t` |
| `Emit<L, X>` | `emit` | `forEach(body, x -> eff)` (in place), `collect(body)`, `iterator(body)` → lazy `java.util.Iterator` |

## Effects on `shift0`

A program is `Eff<L, A>`: of value `A`, at the level `L`, which is the handlers in force. There are two primitives.
`Eff.handle(body, ret)` is `ret $ body` under a prompt that is fresh each time the handler runs. Its body is a
generic method at a level of its own, also fresh: `<M extends L> Eff<M, A> in(Prompt<L, M, R> p)`.
`p.shift0(k -> clause)` is an operation at `M`. It captures the rest up to the prompt's delimiter, the delimiter
included, and runs the clause outside the delimiter, at `L`, in its place.

An effect is an interface at a level. A handler is an implementation of it over a prompt, and the body gets it as a
capability at its fresh level:

```java
interface Ask<L> { Eff<L, Integer> number(); }
interface Asked<L, A> { <M extends L> Eff<M, A> in(Ask<M> a); }   // a handler's body: a level of its own

static <L, A> Eff<L, A> asking(int n, Asked<L, A> body) {
  return Eff.handle(new Eff.Body<L, A, A>() {
    public <M extends L> Eff<M, A> in(Prompt<L, M, A> p) { return body.in(() -> p.shift0(k -> k.apply(n))); }
  }, Eff::pure);
}

static <L> Eff<L, Integer> step(State<L, Integer> st, Choose<L> ch) {   // the row: what it is given, at its level
  return ch.among(List.of(1, 2)).flatMap(x -> st.modify(v -> v + x)).then(st.get());
}

Eff.run(State.state(0, new State.Body<Top, Integer, List<Integer>>() {
  public <M extends Top> Eff<M, List<Integer>> in(State<M, Integer> st) {
    return Choose.all(new Choose.Body<M, Integer>() {
      public <N extends M> Eff<N, Integer> in(Choose<N> ch) { return step(st.at(), ch); }
    });
  }
}));                                                                     // Final(3, [1, 3])
```

An operation goes to its own handler by prompt, and any nearer handlers are crossed. Each crossing is a lift, one
`shift0` more: okay-core's `Perform`, matched by identity instead of a path in a row.

`shift0` gives these properties:

- **A clause runs outside its handler**, so deep handlers need nothing more. A clause can perform the effects of
  the context. A recovery that raises again goes to the handler further out. With `shift`, the clause runs under a
  `reset`, so it has to be lifted out or the handler re-folded.
- **Nothing nests.** A clause's body is not wrapped, so 100 000 captures in a row run in constant stack without
  the `Reset` check in `Loop.delimited`.
- **A tail-resumptive handler is a plain function.** `shift0 k. m >>= k` is `m` performed in place. So a handler
  whose clauses have the form `m.flatMap(k)` is the capability itself, with no prompt and no capture. That covers
  `Reader`, `Emit.forEach`, and forwarding to an outer effect. This is okay-core's `Answering`, which here costs
  nothing to write.
- **Handlers are named, so a row is not needed.** Two `State<Integer>` handlers can be in force at once, and each
  operation goes to the one it was taken from. Effect-polymorphic code is plain Java: `traverse(xs, f)` passes
  through whatever effects `f` has.

The trade-offs:

- **No answer-type modification in `Eff`.** The handler's answer is erased into its prompt, so there is nothing to
  modify. A type that changes moves into the capability, see `PState` below. Full ATM is `Cont0`'s, see printf.
- **Crossing a handler costs a lift.** An operation allocates 96 B under its handler and 48 B more per handler
  crossed (okay-core before its Redelimit: 104 B and 96 B). An operation answered in place allocates 64 B however
  many handlers it crosses.
- **Every handler's body is an anonymous class.** A lambda can't implement a generic method, and the fresh level
  is a generic method. That is the price of the static guarantee below.

## No leaks: a fresh level for each handler

A row in Cont0 is the nesting of its answer types: each handler is a delimiter, one level more. `Eff<L, A>` keeps
that nesting and drops the answers. A handler's body is at a level of its own, `M extends L`, a type variable that
nobody outside the body can name (runST's rank-2 trick). Its capabilities and its prompt are at `M`. So whatever
leaves the body, a capability, a prompt or a program built there, is at a level no program outside is at. Each of
these is a compile error, and `LeakTest` compiles each beside the right version, which compiles:

- a capability out of its handler, used at the top;
- the same, used in another handler at the same place;
- an inner handler's capability used at the level outside it;
- a program built inside a body, run outside;
- a prompt out of its handler.

This is the only way to write a handler, and it costs nothing at run time: the levels are types only, a lift is
nothing, and the allocation per operation is the same as without them. An unchecked cast can still force a
capability out. The prompt then catches a general operation at run time
(`aCapabilityForcedOutByAnUncheckedCastHasNoneToReach`), but an answering capability has no prompt to check.

Levels go one way. A level further in is a subtype, `M extends L`, so a program of a level outside runs inside as
it is:
- In a bind's function it fits on its own, `flatMap(Function<A, Eff<? super L, B>>)`.
- At the head of a chain, `.at()` moves it in, for example `outer.get().<N>at().flatMap(…)`.
- A capability goes in with its own `at()`: `step(st.at(), ch)`.

Moving anything outward does not compile.

The types already caught one leak in the old tests: a Reader's capability used inside a separate async run, at the
top, outside the reader. With an answering capability there was no prompt to notice it.

## Answer-type modification

- **On `Cont0`, in full.** A `shift0` modifies the answer of its delimiter. `Cont0Test`'s printf: `%d` turns the
  answer from `T` into `Integer -> T`, so `reset0("a=" + %d + ", b=" + %s)` is a
  `Function<Integer, Function<String, String>>`. `PState0` is okay-core's `PState`: the state's type is carried in
  the answer type, and a `get` of the wrong type doesn't compile.
- **On `Eff`, as a typestate.** What ATM is mostly used for is a state whose type changes (okay-core's `PState`).
  Here the type is in the capability: `put` of a `T` gives a `PState<L, T>`, and the old capability is stale. Java has
  no linear types, so a stale use is an `IllegalStateException`, checked by a generation that the state carries
  and that backtracks with it.

## Measured

Measured on 2026-10-08 with JMH (2 forks, 3+5 iterations, `-prof gc`, JDK 25), one run after the other on the
same machine (load average 2.5–3.5). Runs: okay-java `037967d` and okay-core `dbdb497` (branch `cont0`), CSVs in
`bench.d/`. The time is per run, and the allocation per operation.

`EffBenchmark`: 10 000 operations, each crossing `crossed` handlers.

| clause, crossed | Java `Eff` | okay-core `Cont0` | okay-core `Cont` |
|---|---|---|---|
| general, 0 | 68.5 µs, 96 B | 68.6 µs, 104 B | 80.4 µs, 136 B |
| general, 1 | 121.4 µs, 144 B | 112.9 µs, 160 B | 131.2 µs, 184 B |
| general, 4 | 276.2 µs, 288 B | 314.0 µs, 328 B | 263.5 µs, 328 B |
| answering, 0 | 35.9 µs, 64 B | 82.9 µs, 96 B | 43.9 µs, 72 B |
| answering, 1 | 36.4 µs, 64 B | 59.3 µs, 96 B | 110.5 µs, 120 B |
| answering, 4 | 35.9 µs, 64 B | 82.2 µs, 96 B | 215.4 µs, 264 B |

`EffectsBenchmark`: 1 000 operations. okay-core's numbers are on `Cont`, because its `EffectsBenchmark` is.

| | Java `Eff` | okay-core `Cont` |
|---|---|---|
| state get+put | 32.9 µs, 502 B functional; 24.0 µs, 262 B `cell` | 15.6 µs, 214 B (a cell) |
| writer tell | 21.0 µs, 253 B | 20.1 µs, 258 B |
| throws raise | 15.4 µs, 280 B | 13.5 µs, 192 B |
| choose among | 32.2 µs, 284 B | 28.8 µs, 394 B |

Queens 8: Java 143 µs, okay-core `Cont0` 1.19 ms, `Cont` 551 µs. That comparison is not like for like:
okay-core's `Vector` concatenation and `zipWithIndex` are not Java's `ArrayList` and loop.

## Build

```sh
./gradlew test
./gradlew bench --args='EffBenchmark -prof gc'   # JMH
```
