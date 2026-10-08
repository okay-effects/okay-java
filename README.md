# okay-java

okay-core's delimited continuations in Java 25, and effects on `shift0`.

## Layout

### `okay.core`

| File | What is in it |
|---|---|
| `Cont.java`, `Loop.java`, `Frames.java`, `Head.java`, `Push.java` | `Cont<I, O, A>`: `shift` and `reset` with answer-type modification, the loop in constant stack |
| `Cont0.java`, `Loop0.java` | `Cont0<I, O, A>`: `shift0` and `$` (Materzok–Biernacki), `reset0`, `lift`, the levels typed in the answers — okay-core's `Cont0` |
| `Eff.java`, `Prompt.java`, `EffLoop.java` | `Eff<A>`: effects on `shift0` by prompt, the answer types erased into the prompts |
| `Fx.java`, `Cap.java` | `Fx<L, A>`: `Eff` with its row in the type, a level `Under<Under<Top, E1>, E2>`; `Cap<L, E>` a capability at a level; `lift` |

### `okay.std`: effects written on `Eff`

| Effect | Operations | Handlers |
|---|---|---|
| `Reader<E>` | `ask` | `reader(e, r -> body)`: answered in place, no prompt |
| `State<S>` | `get`, `put`, `modify` | `state(s0, st -> body)` → `Final(s, a)`, functional: a k resumed twice resumes the state of its time; `cell(s0, st -> body)`: okay-core's, answered in place from a cell, one per run |
| `Writer<W>` | `tell` | `writer(w -> body)` → `Log(ws, a)` |
| `Throws<E>` | `raise` | `catching(t -> body, e -> recovery)`, `attempt(t -> body)` → `Result` (`Ok` / `Failed`) |
| `Choose` | `among`, `fail` (multi-shot) | `all(ch -> body)` → `List`, `first(ch -> body)` → `Optional` |
| `PState<S>` | `get`, `put` (a new type), `done` | `pstate(s0, st -> body)`: the type in the capability, a typestate |
| `PState0` | `get`, `put` (a new type) | `pstate(s0, body)` on `Cont0`: okay-core's, the type in the answer type |
| `Async` | `async`, `await` (callback or `CompletionStage`), `raise` | `runAsync(as -> body)` → `CompletableFuture`; `run(t, as -> body)` waits, its failure raised to `t` |
| `Emit<X>` | `emit` | `forEach(body, x -> eff)` (in place), `collect(body)`, `iterator(body)` → lazy `java.util.Iterator` |

## Effects on `shift0`

There are two primitives. `Eff.handle(p -> body, ret)` is `ret $ body` under a prompt that is fresh each time the
handler runs. `p.shift0(k -> clause)` captures the rest up to that prompt's delimiter, the delimiter included. The
clause runs outside the delimiter, in its place.

An effect is an interface. A handler is an implementation of that interface over a prompt, and the body receives
it as a capability:

```java
interface Ask { Eff<Integer> number(); }

static <A> Eff<A> asking(int n, Function<Ask, Eff<A>> body) {
  return Eff.handle((Prompt<A> p) -> body.apply(() -> p.shift0(k -> k.apply(n))));
}

static Eff<Integer> twoEffects(Ask a, Writer<String> w) {
  return a.number().flatMap(n -> w.tell(n.toString()).map(_ -> n + 1));
}

Eff.run(Writer.writer(w -> asking(41, a -> twoEffects(a, w))));   // Log([41], 42)
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

The trade-offs, and what each is answered with:

- **Effect safety comes from holding a capability, not from types.** A capability used after its handler is gone
  fails at `run` (`IllegalStateException`). `Fx` puts the row back in the type, see below.
- **No answer-type modification in `Eff`.** The handler's answer is erased into its prompt, so there is nothing to
  modify. A type that changes moves into the capability, see `PState` below. Full ATM is `Cont0`'s, see printf.
- **Crossing a handler costs a lift.** An operation allocates 96 B under its handler and 48 B more per handler
  crossed (okay-core before its Redelimit: 104 B and 96 B). An operation answered in place allocates 64 B however
  many handlers it crosses.

## Rows: levels

A row in Cont0 is the nesting of its answer types: each handler is a delimiter, one level more. `Fx<L, A>` keeps
that nesting and drops the answers. A level is `Top`, or `Under<L, E>` for a handler of `E` over `L`. A capability
is at its handler's body's level, `Cap<L, E>`, and only gives programs at that level. To use a program or a
capability under one more handler, you `lift` it.

```java
static <L> Fx<L, Integer> step(Cap<L, State<Integer>> st, Cap<L, Choose> ch) {   // the row: what it is given
  return ch.perform(c -> c.among(List.of(1, 2)))
      .flatMap(x -> st.perform(s -> s.modify(v -> v + x)))
      .then(st.perform(State::get));
}

Fx.run(Fx.handle(state(0), st -> Fx.handle(all(), ch -> step(st.lift(), ch))));   // Final(3, [1, 3])
```

Each of the following is a compile error (`FxTest` compiles them):
- a capability used outside its handler;
- a capability of an inner handler used at the level outside it;
- an outer capability that was not lifted;
- `run` of a program with a handler still missing.

At run time a lift does nothing, since an operation finds its handler by prompt. That is the difference from Cont0,
where a lift is a `shift0` per delimiter crossed: the type does Cont0's bookkeeping, and the prompt does the work.
The cost is a record around `Eff`: 112 B per operation against 96 B. What the types don't cover is two handlers of
the same effect at the same level, for example a capability leaked from one `state` and used in another. Those
stay apart only by prompt, at run time.

`Fx.scoped` closes that gap with a level that is fresh for each handler. This is runST's rank-2 trick: the body is a
generic method `<M> Fx<M, A> apply(Cap<M, E> cap, Lift<L, M> up)`, and `M` is a type variable nobody outside the
body can name. A capability leaked from one handler and used in another at the same place is then a type error
too. The cost is that the body has to be an anonymous class, because a lambda can't implement a generic method:

```java
Fx.scoped(state(0), new Fx.Body<>() {
  public <M> Fx<M, List<Integer>> apply(Cap<M, State<Integer>> st, Fx.Lift<Top, M> up) {
    return Fx.scoped(all(), new Fx.Body<M, Choose, Integer>() {
      public <N> Fx<N, Integer> apply(Cap<N, Choose> ch, Fx.Lift<M, N> in) { return step(in.cap(st), ch); }
    });
  }
});
```

Lifts are explicit in both styles, because a lift is a subtyping of rows and Java can't express that.

## Answer-type modification

- **On `Cont0`, in full.** A `shift0` modifies the answer of its delimiter. `Cont0Test`'s printf: `%d` turns the
  answer from `T` into `Integer -> T`, so `reset0("a=" + %d + ", b=" + %s)` is a
  `Function<Integer, Function<String, String>>`. `PState0` is okay-core's `PState`: the state's type is carried in
  the answer type, and a `get` of the wrong type doesn't compile.
- **On `Eff`, as a typestate.** What ATM is mostly used for is a state whose type changes (okay-core's `PState`).
  Here the type is in the capability: `put` of a `T` gives a `PState<T>`, and the old capability is stale. Java has
  no linear types, so a stale use is an `IllegalStateException`, checked by a generation that the state carries
  and that backtracks with it.

## Build

```sh
./gradlew test
./gradlew bench --args='EffBenchmark -prof gc'   # JMH
```
