# The row notation: `okay.rows`

okay-java has two ways to write a program with effects. Both run on the same machine.

- **The capability notation** (`okay.core.Eff<L, A>`, `okay.std`, [the guide](guide.md)) passes each effect to the
  program as a capability at a level: `static <M> Eff<M, Integer> counter(State<M, Integer> st)`.
- **The row notation** (`okay.rows`, this page) passes nothing. The program's type lists its effects as a nested
  type, the ROW: `Comp<Integer, Eff<State<Integer>, Eff<Choose, ?>>>`. An operation is data, and a handler takes
  the program itself and removes the first effect from the row.

The row notation is the interface of the reference model okay-effects' Java kernel (`okay-effects/java`). The simple
names, the signatures and the meaning are the same: `Comp`, `Eff`, `Pure`, `Op`, `Handler`, `InPlace`, `Cell`, `K`,
`Step`, `Effects.{pure, perform, defer, up, under, fit, handle, intercept, observe, step, run, bracket}`, and the std
effects `State`, `Reader`, `Writer`, `Throws`, `Choose`, `Fresh`, `Clock`, `Emit`, `Async`, `Trail`. Underneath is
okay-java's own machine, not a copy of the reference's: a computation is a program of `okay.core`, and a handler is
a delimiter on the machine's stack.

The test of this is mechanical. okay-effects' `Properties.java` and `Tck.java`, and its programs that javac must
refuse (`java/negative/*.java`), are copied into this repository with only their package and imports changed. All 63
properties pass: the 52 of the model, the TCK digest among them, and 11 of control and audit (`Audits.java`) that
the reference added while this was written. Every negative program, its two of sensitive effects included, is
refused at exactly the lines it marks (`PropertiesTest`, `NegativeTest`).

## A program with two effects

An effect is a sealed interface. Its operations are records, each naming the effect, its answer type and itself:

```java
public sealed interface State<S> {
  record Get<S>() implements State<S>, Op<State<S>, S, Get<S>> {}
  record Put<S>(S value) implements State<S>, Op<State<S>, Void, Put<S>> {}

  static <S> Comp<S, Eff<State<S>, ?>> get() { return perform(new Get<>()); }
  static <S> Comp<Void, Eff<State<S>, ?>> put(S s) { return perform(new Put<>(s)); }
}
```

`Op`'s third parameter is the operation's own class. javac refuses an operation that is not of the effect it names
(`WrongOperation.java`).

A program's type is the row of its effects, in the order of the handlers it runs under. `up(c)` sends `c`'s
operations one place further out:

```java
static Comp<Integer, Eff<State<Integer>, Eff<Choose, ?>>> chosen() {
  return up(Choose.among(List.of(1, 2))).flatMap(x -> State.<Integer>get().flatMap(s ->
      State.put(s + x).map(done -> s + x)));
}
```

Handlers nest from the inside out. Each takes the first effect off the row, and `run` takes only a program with
nothing left, `Pure`:

```java
run(Choose.all(State.run(0, chosen())))   // [Final(1, 1), Final(2, 2)]: a state on each path
```

With the handlers in the other order the program has a different type. Running it under the wrong handlers does
not compile (`WrongRow.java`). Neither does running a program with an effect still unhandled (`Unhandled.java`).

A handler is general or in place:

```java
// general: the rest of the program, k, is a value — resumed with the operation as the witness of the answer's type
static <A, R> Comp<List<A>, R> all(Comp<A, ? super Eff<Choose, R>> p) {
  return handle(Choose.class, new Handler<Choose, A, List<A>, R>() {
    public Comp<List<A>, R> ret(A a) { return pure(List.of(a)); }
    public Comp<List<A>, R> on(Choose op, K<List<A>, R> k) {
      return switch (op) {
        case Among<?> among when among.values().isEmpty() -> k.unwind().map(left -> List.of());
        case Among<?> among -> every(among, 0, List.of(), k);   // k.resume(among, value), for each value
      };
    }
  }, p);
}

// in place: answered at once from a state of the handler's, per path
static <S, A, R> Comp<Final<S, A>, R> run(S s0, Comp<A, ? super Eff<State<S>, R>> p) {
  return handle(State.class, new InPlace<State<S>, S, A, Final<S, A>, R>() {
    public Comp<Final<S, A>, R> ret(S s, A a) { return pure(new Final<>(s, a)); }
    public Cell.Answered answer(Cell<S> state, State<S> op) {
      return switch (op) {
        case Get<S> get -> state.answer(get, state.value);
        case Put<S> put -> { state.value = put.value(); yield state.answer(put, null); }
      };
    }
  }, s0, p);
}
```

The `switch` is exhaustive over the sealed family, so a handler that misses an operation does not compile
(`MissingCase.java`). An answer of the wrong type does not compile either (`WrongAnswer.java`).

Other words, all as in the reference:
- `under(c)` adds a place under the first effect.
- `fit(c)` names a row.
- `intercept(Class<F>, h, p)` intercepts by class. It is only for an effect without type arguments
  (`WrongIntercept.java`); an effect with type arguments is intercepted at its place, with `handle(.., under(p))`.
- `c.ensuring(fin)` and `bracket` run a finalizer when the program is left, `k.unwind()` included.
- `step(p)` runs to the first operation that no handler answers, with the rest as a value.

## How the two notations meet

They are programs of one machine, so each can run inside the other, and a handler written in either can answer the
operations of a program written in the other. `okay.rows.Bridge` does it (`BridgeTest`):

```java
// a row program inside a capability program: handled whole, then a value there
Eff<M, List<Final<Integer, Integer>>> inside = Bridge.eff(Choose.all(State.run(0, chosen())));

// a capability handler answering a row program: okay.std's state answers the row's State operations
okay.std.State.state(10, new okay.std.State.Body<Top, Integer, List<Integer>>() {
  public <M extends Top> Eff<M, List<Integer>> in(okay.std.State<M, Integer> st) {
    return Bridge.state(st, Choose.all(stateOutside()));          // Final(13, [11, 13])
  }
});

// a row handler answering a capability program: the guide's counter, by method reference
run(State.run(0, Bridge.state(BridgeTest::counter)))              // Final(1, 1)

// a capability program at the top (it holds no capability) is a row program in any row
Bridge.<Integer, Eff<State<Integer>, Pure>>comp(plain)
```

The general forms are `Bridge.answering(effect, clauses, p)` and `Bridge.performing(body)`. The first answers a row
effect with capability-notation programs at a level `L`, each answer given with the operation as its witness. The
second gives a capability-notation body something that performs the row's first effect. The level guarantee of the
capability notation holds: a body is generic in its level, so nothing made in it can leave. Each notation's
operations ignore the other's handlers. A capability operation goes to its prompt whatever row handlers are between.
A row operation counts only row places, so capability handlers between are not counted.

## Control and audit

The model is okay-core's (its `docs/intercept.md`). Its names are okay-effects' Java kernel's:
`Sensitive`, `Audited`, `Interceptor`, `Pass`, `Audit`, `K.after`, `Effects.observe`, `std.Trail`. Their tests (`Audits.java`, two negative programs) and their `Bench.java`, `audited` lane included,
compile and run here with only their package changed.

A SENSITIVE effect is declared by its author: its operations are `Sensitive.Op`s, not `Op`s.

```java
sealed interface Pay {
  record Transfer(String to, int amount) implements Pay, Sensitive.Op<Pay, String, Transfer> {}
  record Balance() implements Pay, Sensitive.Op<Pay, Integer, Balance> {}
}
static Comp<String, Sensitive<Pay, ?>> transfer(String to, int amount) {
  return Audit.perform(new Pay.Transfer(to, amount));
}
```

The program's place for it is `Sensitive<Pay, R>`, a row type below `Eff<Pay, R>`. Neither `handle`, `under`, `fit`
to a plain place, nor `run` takes it. Only `Audit.audit(interceptor, body)` does. It gives each operation to the
interceptor, whose clause runs outside the audit, and leaves `Audited<Pay, R>` in the row. Only `Audit.handle`
takes that place:

```java
static <A> Interceptor<Pay, A, Eff<Throws<String>, Eff<Trail, Pure>>> guarded(int limit) {
  return (op, pass, k) -> switch (op) {
    case Pay.Transfer t when t.amount() > limit -> {                 // refused: told, the rest left, failed
      String why = "refused: " + t.amount() + " to " + masked(t.to().trim().toUpperCase()) + " over the limit " + limit;
      yield up(up(Trail.record(why))).flatMap(told -> k.unwind()).flatMap(left -> up(Throws.<String, A>raise(why)));
    }
    case Pay.Transfer t -> {                                         // changed, passed on, told
      String to = t.to().trim().toUpperCase();
      yield k.after(t, pass.pass(new Pay.Transfer(to, t.amount())).flatMap(receipt ->
          up(up(Trail.record("transfer " + t.amount() + " to " + masked(to) + " -> " + receipt)))
              .map(told -> receipt)));
    }
    case Pay.Balance b -> k.after(b, pass.pass(b).flatMap(n -> up(up(Trail.record("balance -> " + n))).map(told -> n)));
  };
}

run(Trail.keep(Throws.run(Audit.handle(bank(), 1000, Audit.audit(guarded(1000), payments())))))
// Kept(["transfer 100 to ****1234 -> ok:ACCT-1234:100", "balance -> 900",
//       "refused: 5000 to ****T-77 over the limit 1000"], Err("refused: 5000 to ****T-77 over the limit 1000"))
```

`bank()` is an ordinary `InPlace` handler that answers with `Cell.answer(Sensitive.Op, x)`. It answers payments
only because `Audit.handle` installed it. `k.after(op, c)` runs `c` and resumes the rest with its value. If the
handler that answers `c` leaves, the rest is unwound too, so its finalizers run. A plain
`c.flatMap(x -> k.resume(op, x))` would leave the rest neither resumed nor unwound
(`anInterceptorGoesOnByAfterSoAHandlerThatLeavesUnwindsTheBody`).

**What javac refuses** (`SensitiveUnhandled.java`, `SensitiveAsked.java`, each at its marked line):
- `perform` of a sensitive operation;
- `run` of a program that asks one;
- `handle` of the sensitive place, or of the audited place;
- `Audit.handle` of a program that was not audited;
- `fit` of a sensitive place to a plain one;
- `under` of a sensitive place;
- making a `Pass` outside an audit.

**At run time**, an interceptor by class (`intercept`) and an observer are never given a sensitive operation, and
`step` does not hand one out (`NoHandler`).

**What can still defeat it**:
- An unchecked cast of a row, as the reference's `closed` does for an operation with no handler.
- A `Pass` kept past its clause and used later under an `Audit.handle`.
- Reflection, or a class planted in the package `okay.rows`: the machine's door (`okay.core.Rows.door`) is given
  once, to `okay.rows.Comp`, by its private lookup, and an unnamed module opens everything to reflection.

**Observing.** `Effects.observe(Class, see, p)` is the reference's. It shows each operation of one effect, at its
place, and then the operation goes on as it is. `Observer.observe(o, p)` goes beyond the reference: it is
okay-core's observer of every effect. Each operation on its way out of `p` stops at it, seen as data
(`effect()`, `describe()`). The observer can `proceed()` it to where it was going, `resume` the rest with that
answer or another of the same type, or refuse. It is no place, so the row is the same outside. It does not see a
sensitive operation, which only that operation's audit sees (`anObserverOfEveryEffectButTheSensitiveOnes`).
Through the bridge it observes a capability-notation program too (`aCapabilityProgramObservedThroughTheBridge`).

**In the capability notation** an operation is a method call on an interface, not data, so there is nothing to
show an observer. Intercepting a capability means wrapping its interface. Its programs get control and audit by
running as row programs (`Bridge.state`, `Bridge.performing`).

## On the machine

`okay.core.Rows` is the machine's side. A row handler is a delimiter on the machine's stack whose prompt carries
a `Place`: the handler and, for one in place, its cell. A place is made each time the handler is entered.
- **Finding the handler.** An operation (`Rows.Ask`) walks the delimiters from the innermost and counts row places.
  A marker of `up` or `under` adds to the count. An interceptor takes the operations of its class, an observer
  takes all. In place: answered there, nothing captured. General: a capture to the handler's prompt, with the clause
  in its place.
- **State per path.** A capture saves the states of the in-place handlers it takes into its rest. A resumption puts
  them back before it goes on.
- **Unwinding.** A rest is resumed as a failure of its own, thrown where the operation was, under a delimiter that
  ends it. The finalizers in the rest run as it passes them. `Comp.ensuring` is the guard of `okay.core.Eff.ensuring`.
  Where the handler that unwinds was stands a marker: gone, kept, or nothing. A value that comes out of a level of
  the rest goes on unwinding.
- **What changed in `okay.core`.** Two node classes, a method on the run (`enter`), and the end of an unwinding in
  `Kernel.unwind`. Nothing on the existing paths changed. The existing benchmarks allocate exactly what they did
  (`bench.d/2026-11-11_rows-*.csv`).
- **For direct style** (okay-java-resumable): `Door.here` gives a program, when it runs, a `Rows.Probe` of the
  delimiters in force. The probe walks them as an operation would and answers only where a handler in place would
  answer. `okay.rows.Here` is its public face, and `okay.rows.Once` lets a general handler declare that it resumes at
  most once. One node class (`Rows.Here`) and a method of `Spec` (`once`) were added; no existing path changed.

## Differences from the reference

Every property of the reference's `Properties.java` holds, and every negative program is refused, unchanged.
Measured or found beyond them:

- **A Java exception runs the finalizers.** A function of the program that throws is an okay-java failure. It
  unwinds through `ensuring`'s guards, running them, and leaves `run`/`step` thrown. The reference runs no finalizer
  when the host throws.
- **State per path, for the row notation's own captures.** The states are saved by a capture the row notation makes
  (a handler, an interceptor, an observer, `step`'s root). A capability handler's `shift0`, or a kernel program's
  capture, across a row handler in place does not save them, so its paths share that handler's cell.
- **The audit is an interceptor at its place.** The types make the sensitive place and the audited place one place.
  At run time they are two handlers, so the body runs under the audit as `under` puts it, and its other operations
  pass both. The reference's `Audit.audit` of the time did not do this: there, an operation of the body for a place
  further out was answered by the handler of the audited place (`anAuditedBodyAsksPastItsSensitivePlace`; run on
  the reference's classes, the same program sends the trail's operation to the bank).
- **`Observer.observe` is an addition** (okay-core's), beyond the reference's per-effect `Effects.observe`.
- **`Comp` is a final class** holding an `okay.core` program, where the reference's is a sealed class of nodes. The
  API is the same. A bind costs a `Comp`, a node and an adapter.
- **A finalizer is a delimiter** on the machine's stack, where the reference has a frame. It is not a place, and
  counting places ignores it.

## Measured

okay-effects' `Bench` with only the package changed (`./gradlew rowsBench`), against the reference's own Java on the
same machine, three runs interleaved, the best of each lane, ns an operation (`bench.d/2026-11-11_rows-lanes.txt`):

| | state | reader3 | choose | binds | audited |
|---|---|---|---|---|---|
| `okay.rows` | 15.2 | 19.5 | 116.1 | 7.7 | 63.7 |
| okay-effects' Java | 11.2 | 15.1 | 96.5 | 6.9 | 35.9 |

The row notation pays for its wrapper and for walking delimiters, not levels:
- every `Comp` holds an `okay.core` program;
- an operation finds its handler by walking the machine's delimiters;
- a handler's entry makes a place, a prompt and a delimiter;
- `k.after` is a guard delimiter.

The capability notation is unchanged.
