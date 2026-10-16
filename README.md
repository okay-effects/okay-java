# okay-java

Delimited continuations in Java 25, and effects on `shift0` — okay-core's design in Java, and an implementation of
okay-kernel's protocol, so its programs run under okay-core's handlers and okay-core's under its.

The documentation is in [`docs/`](docs/README.md): a [guide](docs/guide.md) whose examples are compiled and run by
`GuideTest`, the [architecture](docs/architecture.md), [how it differs from okay-core](docs/vs-okay-core.md),
[okay-java as an implementation of okay-kernel](docs/kernel.md), and [the optional Spring Boot
module](docs/spring-boot.md).

## Layout

### `okay.core`

| File | What is in it |
|---|---|
| `Cont.java`, `Loop.java` | `Cont<I, O, A>`: `shift0` and `$` (Materzok–Biernacki), `reset0`, `lift`, the levels typed in the answers — okay-core's `Cont`, without the rows |
| `Eff.java`, `Prompt.java`, `Nodes.java`, `Machine.java` | `Eff<L, A>`: effects on `shift0` by prompt at a level `L`, a handler's body at a fresh one; the answer types erased into the prompts; a step a node over an explicit stack of delimiters, so handlers nest as deep as the heap allows; failures unwound to a guard (`recover`, `ensuring`) |
| `Kernel.java` | okay-kernel's `Effects` and `Control` on `Eff`'s nodes: a kernel program is an `Eff`, the machine runs the kernel's operations itself ([docs/kernel.md](docs/kernel.md)) |

### `okay.std`: effects written on `Eff`

Each effect is an interface at a level, `E<L, …>`, with an `at()` that moves a capability to a level further in.
Each handler takes a `Body` whose method `<M extends L> Eff<M, A> in(E<M, …> cap)` is the handler's body at its
fresh level. A body is an anonymous class, or a method of your own by reference, `State.state(0, X::counter)` with
`static <M> Eff<M, Integer> counter(State<M, Integer> st)`: a lambda cannot implement a generic method, a method
reference can.

| Effect | Operations | Handlers |
|---|---|---|
| `Reader<L, E>` | `ask` | `reader(e, body)`: answered in place, no prompt |
| `State<L, S>` | `get`, `put`, `modify` | `state(s0, body)` → `Final(s, a)`, functional: a k resumed twice resumes the state of its time; `cell(s0, body)`: okay-core's, answered in place from a cell, one per run |
| `Writer<L, W>` | `tell` | `writer(body)` → `Log(ws, a)` |
| `Throws<L, E>` | `raise` | `catching(body, e -> recovery)`, `attempt(body)` → `Result` (`Ok` / `Failed`) |
| `Choose<L>` | `among`, `fail` (multi-shot) | `all(body)` → `List`, `first(body)` → `Optional` |
| `PState<L, S>` | `get`, `put` (a new type), `done` | `pstate(s0, body)`: the type in the capability, a typestate |
| `ContPState` | `get`, `put` (a new type) | `pstate(s0, body)` on `Cont`: okay-core's kernel's `PState`, the type in the answer type |
| `Async<L>` | `async`, `await` (callback or `CompletionStage`), `raise`; on virtual threads `blocking(call)` and `fork(body)` | `runAsync(body)` → `CompletableFuture`; `run(t, body)` waits, its failure raised to `t` |
| `Emit<L, X>` | `emit` | `forEach(body, x -> eff)` (in place), `collect(body)`, `generate(body, next, done)` (lazy, of the consumer's type, under any effects; okay-core's), `iterator(body)` → `Iterator`, `stream(body)` → a lazy `java.util.stream.Stream` |

### okay-kernel: okay-java implements its API

okay-java depends on `dev.okay:okay-kernel-api` (0.2, from `~/.m2`: interfaces and a few records, no dependency of
their own) and implements it itself, with no wrapper: `Eff` is a kernel `Prog`, and `okay.core.Kernel.EFFECTS` is the
kernel's `Effects` in `Eff`'s own nodes. The machine runs the kernel's operations: it finds an operation's handler on
its own stack of delimiters, where the operation runs, and unwinds failures, aborts and cancellations to the
delimiters they concern. `computation` and `foreign` are the protocol's export and import. The design, and what it
costs okay-java's own API (nothing measurable): [`docs/kernel.md`](docs/kernel.md). `KernelTest` is okay-kernel's
`TestSteps` and the TCK's checks of where implementations went wrong; the TCK itself runs on it in okay-kernel's
`integration`.

### `spring-boot/`: optional Spring Boot support

A module of its own, `dev.okay:okay-java-spring-boot`; okay-java itself depends on nothing of Spring's. On the
classpath of an application of Spring Boot 4.x, it lets a controller's method return a program at the top,
`Eff<Eff.Top, A>`, in web MVC and in WebFlux alike; an `@Async` method return one; and a program run in a
transaction. A controller's program's value is the method's, as if the method had returned it: a body, a
`ResponseEntity`, a view's name. A failure no guard took goes to the `@ExceptionHandler`s. A program that waits
needs nothing of the module: `Async.runAsync`'s `CompletableFuture` is Spring's own in both.

```java
@GetMapping("/sum/{n}")
Eff<Eff.Top, Integer> sum(@PathVariable("n") int n) {
  return State.state(0, new State.Body<Eff.Top, Integer, Void>() {
    public <M extends Eff.Top> Eff<M, Void> in(State<M, Integer> st) { … }
  }).map(State.Final::state);
}
```

- **Web MVC** (`OkayWebMvcAutoConfiguration`): the program runs on the request's thread, its value an async
  dispatch's, as a `CompletableFuture`'s is. The handler goes first among the adapter's, before `@ResponseBody`'s,
  which would otherwise write the program itself as JSON.
- **WebFlux** (`OkayWebFluxAutoConfiguration`): the program runs when the response is subscribed to, on the event
  loop's thread, its value handed to the other result handlers as of the type `A`. It is not a `ReactiveAdapter`,
  because an adapted type's value is its first type argument and an `Eff`'s first is its level. A program that blocks
  belongs on `Async`'s `blocking`, not on the event loop.

Outside the web, in any application:

- **`@Async`** (`OkayAsyncAutoConfiguration`, where `@EnableAsync` is): a method may return an `Eff<Eff.Top, A>`.
  The method and its program run on the `@Async` executor, the call returning at once with a program that is their
  result. Run, that program waits for their value, or fails with their failure, the method's throw included. Spring's
  own interceptor takes nothing but `void` and a `Future`, and goes before every other advisor, so the module puts
  its own in its place in each proxy; any other method is still Spring's.
- **Transactions** (`EffTransactions`, a bean where there is one `PlatformTransactionManager`):
  `txs.transactional(body)` begins a transaction when the run gets to it, commits after the body's value, and rolls
  back however else the body is left. The body is a program at the top, so a capability of a handler outside it does
  not compile there: such a handler could drop the body's rest, and a dropped rest runs no finalizer, not even
  `Eff.ensuring`'s. Its value goes on at any level. The transaction is the thread's, as Spring's are, and
  `@Transactional` on a method returning an `Eff` commits before the program runs.

```java
Eff<M, Order> placed = st.get().flatMap(cart -> txs.transactional(save(cart)));   // save: Eff<Eff.Top, Order>
```

Spring is `compileOnly`, so the application's own Spring Boot is used. Each configuration is on only where its part
of Spring is, and off by `spring.autoconfigure.exclude`. `EffControllerTest` runs one controller on Tomcat,
`WebFluxTest` the same controller on Netty; `AsyncEffTest`, `TransactionsTest` and `TransactionLeakTest` run in an
application not of the web. The whole of it: [`docs/spring-boot.md`](docs/spring-boot.md).

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
`shift0` more: okay-core's `Perform`, found by identity instead of by a path in a row.

`shift0` gives these properties:

- **A clause runs outside its handler**, so deep handlers need nothing more. A clause can perform the effects of
  the context. A recovery that raises again goes to the handler further out. With `shift`, the clause runs under a
  `reset`, so it has to be lifted out or the handler re-folded.
- **Nothing nests.** A clause's body is not wrapped, so 100 000 captures in a row run in constant stack. A `shift`'s
  body has to be put under its reset again, and needs a check that a `shift0`'s does not.
- **A tail-resumptive handler is a plain function.** `shift0 k. m >>= k` is `m` performed in place. So a handler
  whose clauses have the form `m.flatMap(k)` is the capability itself, with no prompt and no capture. That covers
  `Reader`, `Emit.forEach`, and forwarding to an outer effect. This is okay-core's `Answering`, which here costs
  nothing to write and nothing a handler crossed.
- **Handlers are named, so a row is not needed.** Two `State<Integer>` handlers can be in force at once, and each
  operation goes to the one it was taken from. Effect-polymorphic code is plain Java: `traverse(xs, f)` passes
  through whatever effects `f` has.

The trade-offs:

- **No answer-type modification in `Eff`.** The handler's answer is erased into its prompt, so there is nothing to
  modify. A type that changes moves into the capability, see `PState` below. Full ATM is `Cont`'s, see printf.
- **Crossing a handler costs a lift.** An operation allocates 96 B under its handler and 48 B more per handler
  crossed. An operation answered in place allocates 64 B however many handlers it crosses.
- **Every handler's body is an anonymous class.** A lambda can't implement a generic method, and the fresh level
  is a generic method. That is the price of the static guarantee below.

## No leaks: a fresh level for each handler

A row in Cont is the nesting of its answer types: each handler is a delimiter, one level more. `Eff<L, A>` keeps
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

## Failures

A function of the program's that throws — a bind's, a map's, a clause, a handler's return — is a failure of the
program, and `Eff.failed(e)` is one as a program. The machine unwinds it from where it was, through the handlers in
force, to the nearest guard that takes it: `Eff.recover(body, h)`, the failure given to `h` and its program run in
the body's place; `Eff.ensuring(body, fin)`, `fin` run however the body is left, then on the same way. None taking
it, `Eff.run` throws it as before. A clause's failure is outside its handler, a resumed rest's under its delimiters
again, and a `shift0` across a guard keeps the guard in `k`. The guards are the same delimiters okay-kernel's
`recover`, `ensuring` and `uncancelable` are, so a kernel program and an okay-java one guard each other's parts
(`docs/kernel.md`). Errors of the JVM are never caught. `GuardTest`.

## Answer-type modification

- **On `Cont`, in full.** A `shift0` modifies the answer of its delimiter. `ContTest`'s printf: `%d` turns the
  answer from `T` into `Integer -> T`, so `reset0("a=" + %d + ", b=" + %s)` is a
  `Function<Integer, Function<String, String>>`. `ContPState` is okay-core's `PState`: the state's type is carried in
  the answer type, and a `get` of the wrong type doesn't compile.
- **On `Eff`, as a typestate.** What ATM is mostly used for is a state whose type changes (okay-core's `PState`).
  Here the type is in the capability: `put` of a `T` gives a `PState<L, T>`, and the old capability is stale. Java has
  no linear types, so a stale use is an `IllegalStateException`, checked by a generation that the state carries
  and that backtracks with it.

## Measured

The comparison with okay-core as it is now — on its one monad, and through okay-kernel — is okay-bench's, one table
for every implementation on one program: [docs/vs-okay-core.md](docs/vs-okay-core.md). What follows is okay-java
against okay-core's machines of 2026-10-09 (`zero`, `cont`, since replaced by the one monad), microbenchmark by
microbenchmark, kept as the record of what each decision of the machine was taken on.

`MonadsBenchmark` runs okay-core's programs of then under the same names. Both sides ran on JDK 26 on the same
machine: okay-java's machine, a step a node (2026-10-09 20:40, `bench.d/monads-steps-jdk26.csv`, load about 4), and
okay-core `bb01100` (08:45). JMH, 2 forks, 3+5 iterations, `-prof gc`. The CSVs are in `bench.d/`. Numbers are per
operation, `n = 1000`, except captures, which are per run.

| | Java `Eff` | okay-core `cont` | okay-core `zero` |
|---|---|---|---|
| pure binds | 3.50 ns, 56 B | 3.29 ns, 64 B | 3.24 ns, 64 B |
| left binds / left maps | 6.89 / 5.61 ns | 8.29 / 4.85 ns | 8.42 / 4.86 ns |
| right maps / tail calls by delay | 9.54 / 2.17 ns | 7.03 / 2.41 ns | 7.24 / 2.33 ns |
| answered in place | 5.08 ns, 78 B | 6.51 ns, 86 B | 5.37 ns, 86 B |
| general clause | 9.17 ns, 102 B | 8.96 ns, 150 B | 7.73 ns, 118 B |
| answered across 1 / 4 / 16 handlers | 5.09 / 5.13 / 5.26 ns | 11.67 / 22.60 / 91.84 ns | 5.73 / 6.74 / 17.09 ns |
| general clause across 1 / 16 handlers | 13.28 / 42.03 ns | — | — |
| state (a cell) | 14.05 ns, 166 B | 15.53 ns, 214 B | 14.16 ns, 214 B |
| state, functional | 37.98 ns, 470 B | — | — |
| writer, throwing, choose | 18.82, 14.15, 28.49 ns | 20.49, 13.95, 29.15 ns | 18.30, 14.90, 33.94 ns |
| collect / generate | 7.81 / 17.68 ns | 13.13 / 14.55 ns | 10.45 / 14.09 ns |
| a shift under a reset: 16 maps / 1, `k` 8 times | 347 / 82 ns | 433 / 136 ns | 323 / 126 ns |
| the same under a handler | 348 / 81 ns | 410 / 122 ns | 363 / 139 ns |
| 256 maps, `k` 8 times / 1 map, `k` once | 4923 / 34 ns | 5862 / 38 ns | 4676 / 38 ns |
| 16 maps / 256 maps over a record (`captureRecord`, `captureDeepRecord`) | 329 / 4656 ns | — | — |

`MixedBenchmark`, one program of five effects with a throw and a choice handled per order: 131 ns an order, 1352 B
(the first machine, measured one JVM a benchmark beside it: 129 ns, 1292 B). With no type profiles
(`Megamorphic`, `-XX:-UseTypeProfile`, the CSV's same file): 158 ns.

The same program with the total a cell (`ordersCell`), as okay-core's `state` is, against okay-core's own
`MixedBenchmark` on master's std (its branch `mixed-bench`), all on JDK 26, JMH (`bench.d/mixed-jdk26.csv`):

| an order | Java `Eff` | okay-core `cont` | okay-core `zero` |
|---|---|---|---|
| time, allocation | 102 ns, 1025 B | 137 ns, 1472 B | 136 ns, 1370 B |
| with no type profiles | 153 ns | 149 ns | 141 ns |

What a step a node costs: a virtual call each node. Programs of binds and maps with no effect pay it most (right
maps 7.1 to 9.5 ns against the one big loop it replaced, a delay under a map since built in the map's step: 7.1
again); programs of effects gain more than it (the mixed program 195 to 131).

An answered operation costs the same in Java whatever the number of handlers it crosses. The capability is the
handler, so nothing is looked up, while okay-core walks its evidence one handler a step. Queens is left
out because the two programs differ: okay-core has since rewritten its `safe` as a loop (`638246f`).

How the machine got here, step by step and measured: `docs/architecture.md`.

Measured on the first machine (`EffLoop`, 2026-10-09): why its deep capture (256 maps) was 1.7× `zero`'s — not the
machine, and not the allocation, but `Integer.valueOf`. On the machine now the gap is gone, 4.9 µs autoboxed against
4.7 over a record (the table above). Same JDK (26), the same harness (`java` straight), 3 forks:

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
./gradlew test                                     # okay-java and okay-java-spring-boot
./gradlew :okay-java-spring-boot:test              # the Spring Boot module alone
./gradlew bench --args='MonadsBenchmark -prof gc'   # JMH
```
