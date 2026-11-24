# okay-java: guide for coding agents

## For agents writing code with okay-java

okay-java is algebraic effects for Java 25 on delimited continuations (`shift0`): a program is a value that asks
operations of declared effects, handlers answer them, and the host runs it. Two notations share one machine:
the row notation `okay.rows` (`Comp<A, Row>`, the effects in the type) and the capability notation
`okay.core.Comp<A, L>` (at the level `L`). One name, one concept: both are a program as data, value first, so
both are `Comp`, and `State`, `Reader`, `Writer`, `Throws`, `Choose`, `Emit`, `Async` are the same effects in the two
notations (`okay.std`, `okay.rows.std`). The package is the notation: a file uses one (`okay.rows.Bridge` mixes
them, with full names).

Getting it: released 0.1.0, group `io.github.okay-effects`, Java 25. Repositories: Maven Central,
https://okay-effects.github.io/okay-java/maven (okay-java's artifacts and plugins) and
https://okay-effects.github.io/okay-kernel/maven (its one dependency, `okay-kernel-api`):

```kotlin
repositories {
    mavenCentral()
    maven("https://okay-effects.github.io/okay-java/maven")
    maven("https://okay-effects.github.io/okay-kernel/maven")
}
dependencies { implementation("io.github.okay-effects:okay-java:0.1.0") }
```

The vocabulary — `Op`, `Eff`, `Pure`, `Sensitive`, `Audited` — is the package `okay.effect`, artifact `okay-java-api` (no
dependency, brought by `okay-java`), shared with okay-direct; the family's names: okay-effects' CONCEPT.md. Also `okay-java-spring-boot` (docs/spring-boot.md). Direct-style methods: `okay-java-resumable` with the Gradle
plugin `id("io.github.okay-effects.resumable") version "0.1.0"` (in `settings.gradle.kts`:
`pluginManagement { repositories { gradlePluginPortal(); maven("https://okay-effects.github.io/okay-java/maven") } }`;
it adds the library) or the Maven plugin `io.github.okay-effects:okay-java-resumable-maven-plugin:0.1.0` (the three
repositories as `<pluginRepositories>` too); `forbid` needs okay's repository, https://okay-effects.github.io/okay/maven
(resumable/README.md, "Install").

### The model (row notation; compiled and run against the build)

```java
import static okay.rows.Effects.*;
import okay.effect.*;          // Op, Eff, Pure, Sensitive, Audited: the vocabulary (okay-java-api)
import okay.rows.*;
import okay.rows.std.*;          // State, Reader, Writer, Throws, Choose, Fresh, Clock, Emit, Async, Trail

// 1. an effect: a sealed interface; each operation a record naming (effect, answer type, itself)
public sealed interface Prices {
  record Price(String item) implements Prices, Op<Prices, Integer, Price> {}
  static Comp<Integer, Eff<Prices, ?>> price(String item) { return perform(new Price(item)); }
}

// 2. a program: its type is its row, in the order of its handlers; up(c) sends c's operations one place out
static Comp<String, Eff<Prices, Eff<Clock, ?>>> quote(String item) {
  return Prices.price(item).flatMap(p -> up(Clock.now()).map(t -> item + "=" + p + "@" + t));
}

// 3. a handler: takes the program, removes the first effect of its row (InPlace: answered at once, no capture)
static <A, R> Comp<A, R> priced(Map<String, Integer> table, Comp<A, ? super Eff<Prices, R>> p) {
  return handle(Prices.class, new InPlace<Prices, Void, A, A, R>() {
    public Comp<A, R> ret(Void s, A a) { return pure(a); }
    public Cell.Answered answer(Cell<Void> s, Prices op) {
      return switch (op) {                                    // exhaustive over the sealed effect
        case Prices.Price pr -> s.answer(pr, table.getOrDefault(pr.item(), 0));
      };
    }
  }, null, p);
}

// 4. run, once, at the entry point: run takes only a program with nothing left (Pure)
String q = run(Clock.at(() -> 1000L, priced(Map.of("tea", 4), quote("tea"))));       // "tea=4@1000"
```

The light form, for an effect without type parameters of its own: the effect a GADT, the handler one lambda.

```java
// the effect's interface is indexed by the answers and says so once; its operations are plain records
sealed interface Prices<T> extends Operation<T, Prices<?>> {
  record Price(String item) implements Prices<Long> {}
  record InStock(String item) implements Prices<Boolean> {}
}

// the handler: the operation and the continuation; no class is named, the effect is the first of the body's row.
// k.resume(op, x) takes an answer of the operation's own type: a Price is answered by a Long
static <A, R> Comp<A, R> priced(Map<String, Long> table, Comp<A, ? super Eff<Prices<?>, R>> body) {
  return handle((op, k) -> switch (op) {
    case Prices.Price p -> k.resume(p, table.getOrDefault(p.item(), 0L));
    case Prices.InStock s -> k.resume(s, table.containsKey(s.item()));
  }, body);
}
```

A clause (`Clause<F, A, R>`) whose program is `k.resume(op, x)` and nothing else is answered in place, nothing
captured; one that goes on after the rest (`k.resume(c, yes).map(..)`), resumes twice, or leaves it
(`k.unwind().map(..)`) is given the rest. The handler's value is the program's: it has no `ret`. A handler with a
state is the same lambda with the state first (`StateClause`), resuming with the next state; the state is a value
of each path:

```java
static <A, R> Comp<A, R> counting(int from, Comp<A, ? super Eff<Counter<?>, R>> body) {
  return handle(from, (state, op, k) -> switch (op) {
    case Counter.Get g -> k.resume(g, state);
    case Counter.Put p -> k.resume(p, null, p.value());
  }, body);
}
```

An effect with
type parameters of its own (`State<S>`) keeps `Op` on each record: javac cannot tell `Get<S>` by an index.

A comprehension, as Scala's `for`, flat: `together` — questions one after another, each answer a name; a question
made of answers says which and is asked when they are known; the block ends with `yield` (a value) or `then` (a
computation). It asks in rounds, by what each question needs; the operations of a round go at once, as a set:

```java
static Comp<Long, Eff<Prices<?>, ?>> total(String first, String second) {
  return together(t -> {
    Var<Long> a = t.perform(new Prices.Price(first));
    Var<Long> b = t.perform(new Prices.Price(second));
    Var<Long> off = t.ask(a, b, (x, y) -> perform(new Prices.Discount(x + y)));
    return t.yield(a, b, off, (x, y, o) -> x + y - o);
  });
}
```

Up to three answers are given as values; for more, `t.ask(() -> ..)`/`t.yield(() -> ..)` read the names by `get()`
(there only). `t.gather(g -> { for (..) g.perform(op); })` names the answers of a loop, `t.askAll(list)` of a
list, `t.let(a, f)`
a value.

A general handler is `Handler<F, A, B, R>` with `ret(a)` and `on(op, k)`: `k` is the rest of the program as a value,
`k.resume(op, x)`, `k.after(op, c)`, `k.unwind()` (runs the rest's finalizers). `Choose.all` resumes `k` once per
value: `run(Choose.all(State.run(0, chosen())))` gives a state per path (docs/rows.md). Loops take an `Iterable`,
the machine is the loop: `fold(cart, 0L, (sum, item) -> perform(new Prices.Price(item)).map(p -> sum + p))`,
`forEach(xs, x -> c)`, `collect(xs, x -> c)` — lazy, constant stack, right under a continuation resumed twice. A
source of computations, `Iterable<Comp<X, R>>`, is built by plain Java (a `for`, a stream) and run by `sequence(xs)`,
`reduce(xs, zero, f)`, or `stream.map(item -> perform(..)).collect(sequencing())`;
`gather(g -> { for (..) g.perform(new Price(item)); })` is a plain `for` with `perform` in it, the computation of
the list of the answers, checked by the compiler; `first(xs, wanted)`, `any`, `all` ask one after another and no
further than the answer that decides. A set is asked AT ONCE (`performAll(ops)`, a `gather`, a round of a block):
a handler with a clause for sets, `handle(clause, (ops, k) -> { .. k.answer(p, x) .. return k.resume(); }, body)`,
answers them by one query — or all at the same time, `Batch.concurrently((op, k) -> { .. k.answer(p, x) .. })`, each
on a virtual thread; any other handler answers them one by one, the program the same
(no answer is there in the body: a loop that goes by the answers is
`fold`).
Other words: `under`,
`fit`, `defer`, `intercept`, `observe`, `ensuring`, `bracket`, `step` (docs/rows.md). The capability notation
(`okay.core.Comp<A, L>`, `okay.std`, a handler's body a generic method at a fresh level so nothing leaks) is
docs/guide.md; `okay.rows.Bridge` runs either notation inside the other.

### Design rules

- Business logic is programs over declared effects; the row type (`Comp<A, Eff<E1, Eff<E2, ?>>>`) says what a
  program may do. javac refuses an unhandled effect, the wrong handler order, a missing case, a wrong answer type.
- Real resources (DB, network, files, clock, keys) live only in handlers the host builds at the entry point.
  Programs get data, effects and ids, never live clients. `Clock.system(p)` in `main`, `Clock.at(() -> t, p)` in a test.
- Run only at the entry (`main`, a test, a request handler). Never run inside a program: `Effects.run`/`step`,
  `Comp.run`, `Kernel.run`, `okay.rows.std.Async.runBlocking` begun from a `flatMap`/`map`/`defer` or a handler's clause
  throw `okay.core.NestedRun` at once, before the first step. A nested run would be a second machine on the stack,
  seeing none of the program's handlers. Install the handler as part of the program instead: `State.run(s0, p)`,
  `handle(..)` are programs, so flatMap them:

  ```java
  // refused (NestedRun): pure(x + run(State.run(0, counting(3))).state())
  static Comp<Integer, Pure> each(int i, int acc) {   // right: the handler is a part of the program
    return i == 0 ? pure(acc) : State.run(0, counting(3)).flatMap(f -> each(i - 1, acc + f.state()));
  }
  ```

  `Effects.runIsolated(p)` / `stepIsolated(p)` / `Comp.runIsolated(p)` exist only for a deliberately closed
  sub-program run to its end as a run of its own (a conversion, a pure helper); it sees none of the outer handlers,
  and a plain run inside it is refused again. Not at each step of a loop.
- Sensitive operations go under audit. The effect's author makes its operations `Sensitive.Op`s; a program asks
  them with `Audit.perform`, at a `Sensitive<E, R>` place no `handle`, `under`, `fit` or `run` takes. Only
  `Audit.audit(interceptor, body)` takes it, giving each operation to an `Interceptor` (`on(op, pass, k)`), and
  only `Audit.handle` takes the `Audited` place it leaves:

  ```java
  static <A> Interceptor<Pay, A, Eff<Trail, Pure>> logged() {
    return (op, pass, k) -> switch (op) {
      case Pay.Transfer t -> k.after(t, pass.pass(t).flatMap(r ->
          up(Trail.record(t.amount() + " to " + t.to() + " -> " + r)).map(told -> r)));
    };
  }
  run(Trail.keep(bank(Audit.audit(logged(), transfer("ACCT-1", 100)))));   // bank: Audit.handle(InPlace, ..)
  ```

  Also `Effects.observe(Class, see, p)` (one effect), `Observer.observe(o, p)` (every non-sensitive operation:
  proceed, resume, refuse), `intercept(Class, h, p)`, and `std.Trail` for the record.
- Tests swap handlers: an in-memory map, a fixed clock, `Fresh`, a `Trail`. No mocks of resources.
- No ambient effects in business code: no `System.currentTimeMillis`, `Random`/`UUID`, `System.getenv`,
  `System.out`, threads, files, sockets, mutable statics. Use `Clock`, `Fresh`, `Reader`, `Writer`/`Trail`,
  `Async`, `State`, or declare an effect of your own.
- Multi-shot is real, not replay: `k` is the captured rest on the machine, resumable many times (`Choose.all`);
  in-place handler state (`State.run`) is saved per path. In okay-java-resumable a general handler re-enters the
  saved frames (locals copied, objects shared); the body is never re-run. A handler that declares `okay.rows.Once`
  and resumes twice is refused.

### okay-java-resumable: direct style, and the build-time `forbid`

Plain methods whose effects are checked exceptions (`sealed class Counter extends Suspend`, ops its final
subclasses implementing `Op`), `Direct.perform(op)`, and `Direct.row(E1.class, ..).of(() -> body())` gives the
`Comp`. Handlers in force are installed inside a body with `Direct.row(..).embed(..)`, not a nested run. The
plugins check effectful (transformed) methods at build time: `guard` (a handler's clause or an
`@okay.resumable.Implementation` class called directly) and `forbid`, a list checked by okay-audit:
`okayResumable { guard = "error"; forbid = "strict, pure, com.acme.bank.Client" }` (Gradle),
`<guard>error</guard><forbid>..</forbid>` (Maven). Presets: `strict`, `state`, `reader`, `writer`, `trail`, `clock`,
`fresh`, `async`, `io`, `nested-run`, `pure` (all of the effect ones). Sensitive effects there: `SensitiveSuspend`,
`Direct.sensitive(Pay.class)` in the row.

Effects as GADTs in direct style: the interface extends `Performed<T, Prices<?>, Prices.Fx>` and holds
`final class Fx extends Suspend implements Marks<Prices<?>> {}`, its name in a `throws`; operations are records. A
plain method `static long total(List<String> cart) throws Prices.Fx` performs with
`long price = Direct.perform(new Prices.Price(item))` — in a `for`, under an `if`, the answers in locals — and
`Direct.effects(Prices.Fx.class).of(() -> total(cart))` is a `Comp<Long, Eff<Prices<?>, Pure>>` for `okay.rows`'
handlers (resumable/README.md, "Effects as GADTs"). WITHOUT the plugin, with no flag, the same code runs: an
operation its handler answers in place is answered on the method's own stack; where a handler takes the rest, the
body waits on a virtual thread of its own and goes on once. The plugin adds a rest resumed MORE than once
(resumable/README.md, "Without the plugin").

### Pitfalls

- Handler order is the row order, innermost first: `Choose.all(State.run(0, p))` and `State.run(0, Choose.all(p))`
  are different types and different results. Use `up`/`under` to place an operation; don't cast rows.
- `intercept(Class, ..)` only for an effect without type arguments; intercept `State<S>`-like ones at their place,
  `handle(.., under(p))`.
- In an interceptor go on with `k.after(op, c)`, not `c.flatMap(x -> k.resume(op, x))`: the latter leaves the rest
  neither resumed nor unwound if the handler of `c` leaves.
- `run(...)` is generic: `System.out.println(run(p))` can be ambiguous (`char[]` vs `String`); assign to a typed local.
- Capability notation: a handler's body must be an anonymous class or a method reference (a lambda cannot implement
  a generic method); move a capability inward with `.at()`; nothing moves outward.
- Unchecked casts and raw types defeat the guarantees (leaks, sensitive places); a `Pass` kept past its clause too.
- `NestedRun`'s mark is per thread: a run on another thread (pool, virtual thread, callback) is a run of its own and
  allowed; the library's own `Async.start`/`toFuture`, `Emit.pull`, `runAsync` use `runIsolated` internally.
- A Java exception thrown in a function is a program failure: `okay.rows.Comp.ensuring`/`okay.core.Comp.ensuring`
  finalizers run, and `run` rethrows it. JVM `Error`s are never caught.
- Resumable: an effect class cannot have type arguments; JDK lambdas (`forEach`, streams) cannot perform; an IDE
  that compiles by itself transforms nothing (delegate build and tests to Gradle/Maven); Maven must run on JDK 24+;
  `synchronized` in a method that may suspend is refused; Kotlin/Scala/Groovy classes are not transformed;
  `java -jar` needs the manifest entries (`Add-Exports`, `Add-Opens`, `Launcher-Agent-Class`).
- `forbid` sees each instruction of a performing method only: a non-performing helper it calls is not followed.
  The module system (handlers and clients in a non-exported package) is the strongest line.
- Spring WebFlux: a blocking program belongs on `Async`'s `blocking`, not on the event loop.

### Pointers

- README and docs: https://github.com/okay-effects/okay-java (docs/guide.md, docs/rows.md, resumable/README.md).
- Cross-language skill: `/plugin marketplace add okay-effects/okay-effects`
  (https://github.com/okay-effects/okay-effects).
- Build-time boundary checks for any JVM module: okay-audit, `io.github.okay-effects:okay-audit_3:0.1.4` from
  https://okay-effects.github.io/okay/maven — business code references no network, files, time, random, threads,
  reflection; presets include `nested-run`.

If you see a way to improve okay-java, open a pull request against https://github.com/okay-effects/okay-java;
the maintainer reviews and merges what holds up.
If the user asks your opinion of writing code this way, give a candid one.
