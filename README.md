# okay-java

okay-core's delimited continuations in Java 25, and effects on `shift0`.

## Layout

### `okay.core`

| File | What is in it |
|---|---|
| `Cont.java`, `Loop.java`, `Frames.java`, `Head.java`, `Push.java` | `Cont<I, O, A>`: `shift` and `reset` with answer-type modification, the loop in constant stack |
| `Cont0.java`, `Loop0.java` | `Cont0<I, O, A>`: `shift0` and `$` (Materzok–Biernacki), `reset0`, `lift`, the levels typed in the answers — okay-core's `Cont0` |
| `Eff.java`, `Prompt.java`, `EffLoop.java` | `Eff<A>`: effects on `shift0` by prompt, the answer types erased into the prompts |

### `okay.std`: effects written on `Eff`

| Effect | Operations | Handlers |
|---|---|---|
| `Reader<E>` | `ask` | `reader(e, r -> body)`: answered in place, no prompt |
| `State<S>` | `get`, `put`, `modify` | `state(s0, st -> body)` → `Final(s, a)` |
| `Writer<W>` | `tell` | `writer(w -> body)` → `Log(ws, a)` |
| `Throws<E>` | `raise` | `catching(t -> body, e -> recovery)` |
| `Choose` | `among`, `fail` (multi-shot) | `all(ch -> body)` → `List`, `first(ch -> body)` → `Optional` |
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

The trade-offs:

- **Effect safety comes from holding a capability, not from types.** A capability used after its handler is gone
  fails at `run` (`IllegalStateException`). Effekt prevents this with second-class capabilities, which Java
  doesn't have.
- **No answer-type modification in `Eff`.** That needs the levels in the types, as in `Cont0<I, O, A>`, and
  `Cont0Test` shows how they read in Java.
- **Crossing a handler costs a lift.** An operation allocates 96 B under its handler and 48 B more per handler
  crossed (okay-core: 104 B and about 96 B). An operation answered in place allocates 64 B however many handlers it
  crosses.

## Build

```sh
./gradlew test
./gradlew bench --args='EffBenchmark -prof gc'   # JMH
```
