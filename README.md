# okay-java

okay-core's delimited continuations in Java 25, and effects on `shift0`.

The documentation is in [`docs/`](docs/README.md): a [guide](docs/guide.md) whose examples are compiled and run by
`GuideTest`, the [architecture](docs/architecture.md), and [how it differs from okay-core](docs/vs-okay-core.md).

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
| `Emit<L, X>` | `emit` | `forEach(body, x -> eff)` (in place), `collect(body)`, `generate(body, next, done)` (lazy, of the consumer's type, under any effects; okay-core's), `iterator(body)` → `Iterator`, `stream(body)` → a lazy `java.util.stream.Stream` |

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

`MonadsBenchmark` runs okay-core master's programs under the same names. Both sides ran on JDK 26 on the same
machine: okay-java `4009fed` (2026-10-09 11:38) and okay-core `bb01100` (08:45). JMH, 2 forks, 3+5 iterations,
`-prof gc`. The CSVs are in `bench.d/`. Numbers are per operation, `n = 1000`, except captures, which are per run.

| | Java `Eff` | okay-core `cont` | okay-core `zero` |
|---|---|---|---|
| pure binds | 3.13 ns, 56 B | 3.29 ns, 64 B | 3.24 ns, 64 B |
| left binds / left maps | 6.60 / 5.27 ns | 8.29 / 4.85 ns | 8.42 / 4.86 ns |
| right maps / tail calls by delay | 7.12 / 1.97 ns | 7.03 / 2.41 ns | 7.24 / 2.33 ns |
| answered in place | 4.44 ns, 78 B | 6.51 ns, 86 B | 5.37 ns, 86 B |
| general clause | 7.77 ns, 110 B | 8.96 ns, 150 B | 7.73 ns, 118 B |
| answered across 1 / 4 / 16 handlers | 4.36 / 4.37 / 4.48 ns | 11.67 / 22.60 / 91.84 ns | 5.73 / 6.74 / 17.09 ns |
| general clause across 1 / 16 handlers | 14.06 / 109.87 ns | — | — |
| state (a cell) | 15.71 ns, 166 B | 15.53 ns, 214 B | 14.16 ns, 214 B |
| state, functional | 32.00 ns, 502 B | — | — |
| writer, throwing, choose | 20.42, 13.34, 25.83 ns | 20.49, 13.95, 29.15 ns | 18.30, 14.90, 33.94 ns |
| collect / generate | 7.00 / 15.33 ns | 13.13 / 14.55 ns | 10.45 / 14.09 ns |
| a shift under a reset: 16 maps / 1, `k` 8 times | 519 / 130 ns | 433 / 136 ns | 323 / 126 ns |
| the same under a handler | 512 / 115 ns | 410 / 122 ns | 363 / 139 ns |
| 256 maps, `k` 8 times / 1 map, `k` once | 6708 / 36 ns | 5862 / 38 ns | 4676 / 38 ns |
| 16 maps / 256 maps over a record (`captureRecord`, `captureDeepRecord`) | 339 / 4966 ns | — | — |

Captures by `shift` over a record instead of autoboxed `Integer`s are even with `zero`: 339 ns against 323 with 16
maps. Even when every value comes from `Integer.valueOf`'s cache, its branch costs the loop a third.

An answered operation costs the same in Java whatever the number of handlers it crosses. The capability is the
handler, so nothing is looked up, while okay-core's `zero` walks its evidence one handler a step. Queens is left
out because the two programs differ: okay-core has since rewritten its `safe` as a loop (`638246f`).

How the rows got here, measured against the code before on the same machine:
- A delay is a node of its own, and so is a value computed in place (`Sync`). A bind takes either with no frame,
  and a `Sync` goes through the frames with no `Return` made.
- A reset or a resumption under a delimiter of its own prompt runs nested up to 64 deep, as in `zero`.
- A value goes through the maps on top of the stack in a small method of its own, `feed`, as in okay-core, instead
  of inside the loop's 800 bytes. The JIT compiles it apart and better: a capture with 16 maps went from 949 to
  486–519 ns.
- A handler that is bound is entered at once (okay-core's `248fd36`), and `Throws.attempt` is a handler of its own.
  `throwing` went from 17.2 to 13.3 ns.

Why the deep capture (256 maps) is 1.7× `zero`'s: not the machine, and not the allocation, but `Integer.valueOf`.
Same JDK (26), the same harness (`java` straight), 3 forks:

| deep capture, 256 maps, `k` 8 times | Java | `zero` |
|---|---|---|
| `x -> x + 1`, autoboxed: `Integer.valueOf` | 7.79 µs, 30 KB | 4.67 µs, 30 KB |
| `x -> new Integer(x + 1)` (`captureDeepNew`) | 4.95 µs, 46 KB | 5.15 µs, 47 KB |
| `x -> new Int(x.value() + 1)`, `record Int(int value)` (`captureDeepRecord`) | 4.95 µs, 47 KB | — |
| `x -> x`, no box made | 4.4–4.6 µs | 4.6–4.8 µs |

With the constructor, every map allocates, 50% more than through the cache, and Java is faster than `zero`.
`new Integer(int)` has been deprecated for removal since Java 16. A box of one's own, a record, is not cached and not
deprecated, and is as fast. What
makes the difference is escape analysis, in its part that eliminates boxes: C2 replaces `valueOf(x).intValue()` by
`x`, not reading the box back. Turned off (`-XX:-DoEscapeAnalysis`, `-XX:-EliminateAutoBox` or
`-XX:-AggressiveUnboxing`), `zero`'s deep capture goes from 4.8 to 6.4 µs, while Java's barely moves (8.3 to 8.5–8.9):
in `zero`'s loop it works, in ours it does not. It removes no allocation in either (30 KB a run both ways), only the
unboxing.

The shape of the function matters too. A map written as Scala's specialized one (a static `unbox`, an `int` function,
`valueOf`) runs Java's deep capture in 5.6 µs without the box elimination, faster than `zero` without it (6.4). With
it, the same loop slows to 7.9. Why C2 helps one loop and hurts the other is not visible on a product JVM: the flags
that trace escape analysis are a debug build's.

## Build

```sh
./gradlew test
./gradlew bench --args='MonadsBenchmark -prof gc'   # JMH
```
