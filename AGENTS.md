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

### The model: start here

The whole of it, with every example compiled and run, is [docs/start.md](docs/start.md). In short:

```java
import static okay.rows.Sets.*;
import okay.effect.*;          // Operation, Eff, Pure
import okay.rows.*;            // Comp, Var

// 1. an effect: a sealed interface; each operation a record that names its answer and its effect
sealed interface Prices {
  record Price(String item) implements Prices, Operation<Long, Prices> {}
  record InStock(String item) implements Prices, Operation<Boolean, Prices> {}
  record Discount(long sum) implements Prices, Operation<Long, Prices> {}
}

// 2. a program: its type says what it gives and the effects it may ask — a type bounded by them, in any order
static <R extends Prices> Comp<String, R> quote(String item) {
  return together(t -> {                                        // a block: questions, each answer a name
    Var<Long> price = t.perform(new Prices.Price(item));
    Var<Boolean> stock = t.perform(new Prices.InStock(item));
    Var<Long> off = price.then(p -> t.perform(new Prices.Discount(p)));
    return t.yield(price, stock, off, (p, in, o) -> in ? item + " " + (p - o) : item + " is out");
  });
}

// 3. a handler: a function over a program whose row is a list that begins with its effect
static <A, R> Comp<A, R> priced(Map<String, Long> table, Comp<A, ? super Eff<Prices, R>> body) {
  return answering(op -> switch (op) {
    case Prices.Price p -> p.answer(table.getOrDefault(p.item(), 0L));
    case Prices.InStock s -> s.answer(table.containsKey(s.item()));
    case Prices.Discount d -> d.answer(d.sum() / 10);
  }, body);
}

// 4. where the handlers are put: the set written as a list, once; run at the entry point
var shop = row(of(Prices.class));
String q = run(priced(Map.of("kettle", 120L), shop.of(quote("kettle"))));       // "kettle 108"
```

**A program**, three ways, one model, freely mixed. Its row is `<R extends A & B>`; a program of fewer effects is
used in one of more as it is — no `up`, no `fit`, no name for the row (a class can name one for its methods:
`final class Till<R extends Prices & Ask> { Comp<String, R> receipt() {..} }`).

| Way | Words | Use it when |
|---|---|---|
| a block | `together(t -> ..)`: `t.perform(op)`, `a.map(f)`, `a.with(b, f)`, `a.then(x -> ..)`, `t.gather(g -> { for .. })`, `t.each(list, x -> ..)`, `t.when(..)`, `t.loop(..)`, `t.yield(..)` | several questions, some depending on others |
| a chain | `perform(op)`, `flatMap`, `map`, `pure`, `fold(xs, zero, (acc, x) -> ..)`, `forEach`, `collect`, `sequence`, `first` | a loop that goes by the answers, a recursion |
| plain Java | `Direct.body(d -> { long p = d.perform(new Prices.Price(item)); .. })` (okay-java-resumable; no plugin needed, nothing declared for an effect, no `throws`; a helper method takes `Asks<R> d`; the rest resumed once) | answers in locals, `for` and `if` as they are (resumable/README.md) |

A block asks in rounds — every question whose answers are known, then the next — and the questions of a round that
are of one effect are asked as a set. An answer is a value only inside the function given it (`p -> ..`).

**A handler**, by what it needs — the same for every kind of program:

| It needs | Write |
|---|---|
| only to answer | `answering(op -> switch (op) { case Price p -> p.answer(x); .. }, body)`; `answeringAtOnce` for a set at the same time |
| the rest of the program | `handle((op, k) -> switch (op) { .. k.resume(op, x) .. }, body)`: go on after it (`.map`), leave it (`k.unwind()`), resume it twice |
| a value of its own | `handle(a -> pure(Optional.of(a)), (op, k) -> .., body)` |
| a state | `handle(s0, (state, op, k) -> .. k.resume(op, x, next) .., body)` |
| a set whole | `handle(clause, (ops, k) -> { .. k.answer(op, x) ..; return k.resume(); }, body)`: one query for a round |

A clause that asks another effect: `ask(k, new Clock.Now())`, in a handler whose type says the list that stays has
it (`<A, R> Comp<A, Eff<Clock, R>> audited(Comp<A, ? super Eff<Mail, Eff<Clock, R>>> c)`). The library's effects
(`okay.rows.std`) are of the same form: `perform(new State.Get<Integer>())`, `State.run(0, ..)`, `Clock.at(..)`,
`Choose.all(..)`; in a list, `Sets.<State<Integer>>the(State.class)`. In front of a handler, the effect kept in
the list: `cached(p)`, `rounds(see, p)`, `observe(Effect.class, see, p)`.

**Where the handlers are put.** `var app = row(of(Store.class, Ids.class))` — the effects in the order of the
handlers — and `run(identified(stored(app.of(program))))`. The boundary kept in a field or a bean is a class that
names the set: `final class Shop<V extends Store & Ids> { <A> A run(Comp<A, ? super V> p) { var row =
Sets.<V>exactly(Shop.class).row(of(Store.class, Ids.class)); return Sets.run(identified(stored(row.of(p)))); } }`,
`static final Shop<?> SHOP = new Shop<>()`. A handler inside a program, around a part of it:
`attempt(Sets.<R>keep().row(of(Abort.class)).of(part))`. `BookingSets` (chains and blocks) and `BookingBodyTest`
(plain Java) in the tests are a real flow of seven effects.

javac's words: `inference variable R has incompatible bounds` — an operation the row does not have, or a program
that asks more than the list names; `method run .. cannot be applied` — an effect left unhandled; a handler's name
`.. cannot be applied` — the wrong order, or the set not written as a list.

How an operation finds its handler: by no place — the nearest handler whose effect it is of answers. A handler
knows its effect's class from where it is made (`Comp<A, ? super Eff<Prices, R>>`, `handle(State.class, ..)`); two
handlers of one effect are two scopes.

**Rows of places** ([docs/places.md](docs/places.md)) are the earlier form, under the same handlers: each effect
has a place the program writes too — `Comp<String, Eff<Prices<?>, Eff<Ask<?>, ?>>>`, an effect indexed by its
answers (`sealed interface Prices<T> extends Operation<T, Prices<?>>`, `record Price(..) implements Prices<Long>`),
`import static okay.rows.Effects.*`, the second effect asked with `up(perform(op))`, a row given a name
(`interface App<R> extends Eff<Store<?>, .. R>`, `BookingAlias`), an application's effects as one family
(`BookingOne`). Use it where two handlers of one effect must be told apart by place, or an effect with type
arguments by its arguments.

### The other forms

Needed for a reason each; all work together with the above.

- An effect with type parameters of its own (`State<S>`) names the answer on each record:
  `record Get<S>() implements State<S>, Op<State<S>, S, Get<S>> {}`.
- The library's effects (`okay.rows.std`: `State`, `Reader`, `Writer`, `Throws`, `Choose`, `Fresh`, `Clock`,
  `Emit`, `Async`, `Trail`) are in that form, with handlers as classes — `Handler<F, A, B, R>` (`ret`, `on(op, k)`)
  and `InPlace<F, S, A, B, R>` (`ret`, `answer(cell, op)`), put by `handle(Effect.class, h, p)`. `Choose.all`
  resumes `k` once per value: `run(Choose.all(State.run(0, chosen())))` gives a state per path. The same model in
  that form:

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

- Other words of the row notation: `under`, `fit`, `defer`, `intercept`, `observe`, `ensuring`, `bracket`, `step`,
  `k.after(op, c)` (docs/rows.md).
- The capability notation (`okay.core.Comp<A, L>`, `okay.std`, a handler's body a generic method at a fresh level
  so nothing leaks) is docs/guide.md; `okay.rows.Bridge` runs either notation inside the other.

### Design rules

- Business logic is programs over declared effects; the row type (`<R extends E1 & E2> Comp<A, R>`) says what a
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
- Rows as sets: write a handler where its effect is a named type (`Comp<A, ? super Eff<Ids, R>>`). In a helper
  generic in the effect (`<F> .. Eff<F, R>`) `answering`/`handle` cannot learn the effect's class: such a handler
  answers by its place only, and an operation asked by `Sets.perform` passes it — "an operation with no handler in
  force" when it is asked. With a bound (`<F extends Shop>`) the class learnt is the bound's, and the handler takes
  every operation of the bound asked so.
- Rows as sets have no places: a handler put around a program GIVEN to a function generic in its row is that
  program's nearest too and answers its operations of that effect; with a generic effect of other type arguments
  (`State<String>` inside, `State<Integer>` given) that is a `ClassCastException`. Give such a function an effect
  of its own, or a row of places for it.
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
