# Start here

Effects in Java, the light way: an effect is a small sealed family of records, a program asks its operations, a
handler answers them, and the program's type says what it may ask. Nothing is passed around, no plugin is needed,
and everything runs on one machine — a loop, so a program takes no Java stack however long it is.

`StartTest` compiles and runs every example on this page.

```java
import static okay.rows.Effects.*;
import okay.effect.*;      // Operation, Eff, Pure
import okay.rows.*;        // Comp, Var
```

1. [An effect](#1-an-effect)
2. [A program](#2-a-program): a block, a chain, or plain Java
3. [A handler](#3-a-handler): four kinds, by what it needs
4. [Running, and the row](#4-running-and-the-row)
5. [Between a program and its handler](#5-between-a-program-and-its-handler)
6. [What is refused, and when](#6-what-is-refused-and-when)
7. [What it costs](#7-what-it-costs)
8. [The other forms](#8-the-other-forms)

## 1. An effect

A sealed interface indexed by its operations' answers, and a record for each operation. The interface says once
that it is an effect; a record says the type of its answer.

```java
sealed interface Prices<T> extends Operation<T, Prices<?>> {
  record Price(String item) implements Prices<Long> {}
  record InStock(String item) implements Prices<Boolean> {}
  record Discount(long sum) implements Prices<Long> {}
}
```

An operation is data: it can be compared, printed, kept, sent. Nothing happens when one is made.

## 2. A program

A program is a value, `Comp<A, R>`: `A` is what it gives, `R` the effects it may ask — its ROW. There are three
ways to write one. They are the same thing underneath and mix freely.

| Way | Reads as | Use it when |
|---|---|---|
| a block, `together` | questions one after another, each answer a name | several questions, some depending on others: most code |
| a chain, `flatMap` / `fold` | each step a function of the answer before | a loop that goes by the answers, a recursion |
| plain Java, `Direct` | a method with `for` and `if`, answers in locals | you want exactly that: see [direct style](../resumable/README.md) |

### A block

```java
static Comp<String, Eff<Prices<?>, ?>> quote(String item) {
  return together(t -> {
    Var<Long> price = t.perform(new Prices.Price(item));
    Var<Boolean> stock = t.perform(new Prices.InStock(item));
    Var<Long> off = price.then(p -> t.perform(new Prices.Discount(p)));
    return t.yield(price, stock, off, (p, in, o) -> in ? item + " " + (p - o) : item + " is out");
  });
}
```

`t.perform(op)` asks, and names the answer: a `Var<T>` of the operation's own type. A name goes on by itself:

| | |
|---|---|
| `a.map(f)` | a value made of the answer; no question |
| `a.with(b, f)`, `a.with(b, c, f)` | a value made of two or three answers |
| `a.then(x -> t.perform(..))` | the questions that need this answer |
| `t.yield(a, b, c, f)`, `t.yield(a)`, `t.done()` | the block's value, its last word; `done` for a block asked for what it does |
| `t.then(a, x -> comp)` | the block goes on with another program |

The block knows what depends on what, so it asks in ROUNDS: first every question that needs nothing, then those
that needed only these, and so on. Here `price` and `stock` are one round, `off` the next. The questions of a round
are independent, and a handler may answer them together ([below](#3-a-handler)).

A plain loop of questions is `t.gather`; a loop over an answer that is a list is `t.each`; a branch by an answer is
`t.when`; a loop by a state is `t.loop`:

```java
static Comp<Long, Eff<Prices<?>, ?>> total(List<String> cart) {
  return together(t -> {
    Var<List<Long>> prices = t.gather(g -> {
      for (String item : cart) g.perform(new Prices.Price(item));
    });
    Var<Long> sum = prices.map(ps -> ps.stream().mapToLong(Long::longValue).sum());
    Var<Long> off = sum.then(s -> t.perform(new Prices.Discount(s)));
    return t.yield(sum, off, (s, o) -> s - o);
  });
}
```

| | |
|---|---|
| `t.gather(g -> { for (..) g.perform(op); })` | the answers of a plain loop, as one list |
| `t.each(list, x -> ..)` | for every element of an answer that is a list, the questions its function writes; all the elements' questions asked together, however deep lists nest |
| `t.when(cond, () -> .., () -> ..)` | the questions of one branch, chosen by an answer |
| `t.loop(start, more, next)` | turn after turn while the state says so: a read page after page |
| `t.ask(comp)` | any program as a question: another effect's with `up`, another block |
| `t.value(v)` | a name for a value |

An answer is a value only inside the function that is given it — `p -> ..`, `(s, o) -> ..`. That is the one thing
a block does not give: an answer in a plain local, a line below the question.

### A chain

```java
static Comp<Long, Eff<Prices<?>, ?>> inStockTotal(List<String> cart) {
  return fold(cart, 0L, (sum, item) ->
      perform(new Prices.InStock(item)).flatMap(in ->
          in ? perform(new Prices.Price(item)).map(p -> sum + p) : pure(sum)));
}
```

`perform(op)` is the program that asks one operation; `c.flatMap(x -> next)` goes on with a program made of the
answer, `c.map(f)` with a value; `pure(v)` asks nothing. Loops take an `Iterable`, and the machine is the loop:

| | |
|---|---|
| `fold(xs, zero, (acc, x) -> comp)` | a loop that carries a value |
| `forEach(xs, x -> comp)`, `collect(xs, x -> comp)` | a loop for its effects; for the list of its values |
| `sequence(comps)`, `reduce(comps, zero, f)` | a list of programs, run in order |
| `first(comps, wanted)`, `any`, `all` | one after another, no further than the answer that decides |
| `gather(g -> { for (..) g.perform(op); })` | a block that is one plain loop: the list of its answers |

A function that ends by calling itself — a recursion written with `flatMap` — runs in constant stack: a call in
the last step takes none.

### Two effects

The row lists a program's effects in the order of their handlers, the nearest first. An operation of the first
effect is `perform(op)`; of the second, `up(perform(op))`.

```java
static Comp<String, Eff<Prices<?>, Eff<Ask<?>, ?>>> checkout(List<String> cart) {
  return total(cart).flatMap(sum ->
      sum <= 100 ? pure("paid " + sum)
          : up(perform(new Ask.Confirm("pay " + sum + "?"))).flatMap(yes ->
              yes ? pure("paid " + sum) : up(perform(new Ask.Fail<String>("declined")))));
}
```

`total` asks only `Prices`, and is used here as it is: a program of ONE effect fits any row that has that effect
first. With more effects than two or three, name the row.

### Many effects: name the row

A row can be given a name: an interface that extends it, its tail a parameter.

```java
interface App<R> extends Eff<Store<?>, Eff<Mail<?>, Eff<Ids<?>, Eff<Clock<?>, R>>>> {}
```

Every program of the application is then a `Comp<X, App<?>>`. Each effect is asked by a word that says its place —
once, and javac checks it: a word with the wrong number of `up` does not compile.

```java
static <T> Comp<T, App<?>> store(Store<T> op) { return fit(perform(op)); }
static <T> Comp<T, App<?>> mail(Mail<T> op)   { return fit(up(perform(op))); }
static <T> Comp<T, App<?>> ids(Ids<T> op)     { return fit(up(up(perform(op)))); }
static <T> Comp<T, App<?>> clock(Clock<T> op) { return fit(up(up(up(perform(op))))); }

static Comp<String, App<?>> reserve(String name) {
  return together(t -> {
    Var<String> ref = t.ask(ids(new Ids.Ref("B")));
    Var<String> key = t.ask(ids(new Ids.Secret()));
    Var<Long> now = t.ask(clock(new Clock.Now()));
    Var<Booked> booked = ref.with(key, now, (r, k, at) -> new Booked(r, k, at, name));
    Var<Boolean> made = booked.then(b -> t.ask(store(new Store.Book(b))));
    Var<Boolean> told = booked.then(b -> t.ask(mail(new Mail.Send(b.name(), "booked " + b.ref()))));
    return t.yield(ref, made, told, (r, m, s) -> m ? r : "taken");
  });
}
```

No `up` in a program, no long type, and a program used inside another as it is: they are all of the one row. The
effects stay what they are — of any package, each its own place and its own handler, their order in the type. Where
the handlers are put, the name is opened into the row it stands for, by a function written once beside it:

```java
static <A, R> Comp<A, Eff<Store<?>, Eff<Mail<?>, Eff<Ids<?>, Eff<Clock<?>, R>>>>> open(Comp<A, ? super App<R>> c) { .. }

run(clocked(identified(mailed(stored(open(reserve("Ann")))))));          // each a handler of one effect
```

`BookingAlias` in the tests is a real flow written so, with its `open`: the taking of a booking of okay-shop, seven
effects of the same handlers as the flow written with bare places (`Booking`), the same scenarios on both — a
hundred and nine lines against a hundred and sixty-five, its only `up`s the seven words'.

### An application's effects as one family

An application's OWN effects — a store, mail, ids, a clock, all of one package — whose order between
handlers means nothing can be ONE place of the row instead: one sealed family, each effect a sealed part of it.

```java
sealed interface Shop<T> extends Operation<T, Shop<?>> permits Store, Mail, Ids, Clock {}

sealed interface Store<T> extends Shop<T> {
  record Allow(String key, int max, long window, long now) implements Store<Boolean> {}
  record Book(Booked booking, long now) implements Store<Or<Booked>> {}
}
sealed interface Ids<T> extends Shop<T> {
  record Ref(String prefix) implements Ids<String> {}
  record Secret() implements Ids<String> {}
}
```

Every program of the application is then a `Comp<X, Eff<Shop<?>, ?>>`, whatever it asks: no `up`, no place to
count, a sub-program used as it is, every question of a block `t.perform(op)`. The handler is one `switch` over the
family, each part answered by its own function:

```java
answering(op -> switch (op) {
  case Store<?> s -> stored(world, s);       // static Answer<?> stored(World w, Store<?> op): its own switch
  case Ids<?> i -> identified(world, i);
  case Clock.Now n -> n.answer(world.now);
  ..
}, program);
```

What is given up: the type no longer says which of the family a function asks, and the family is handled together.
Keep separate places for effects whose order matters or that come from elsewhere — a `State` inside a choice, a
library's effect.

A sealed family is of one package (of one module, with `module-info`): an effect of another jar cannot be a part
of it, and a named row is the way for those. `BookingOne` in the tests is the same real flow written so.


## 3. A handler

A handler takes a program and removes the first effect of its row. Which kind to write depends on what it needs:

| It needs | Write | |
|---|---|---|
| only to answer | `answering(op -> .. op.answer(x) ..)` | the usual one: a lookup, a clock, a stub in a test |
| the rest of the program | `handle((op, k) -> ..)` | to go on after it, leave it, resume it twice |
| a state | `handle(s0, (state, op, k) -> ..)` | a counter, a log, a cache |
| a set of operations whole | `handle(clause, (ops, k) -> ..)` | one query for a round |

### One that only answers

```java
static <A, R> Comp<A, R> priced(Comp<A, ? super Eff<Prices<?>, R>> body) {
  return answering(op -> switch (op) {
    case Prices.Price p -> p.answer(TABLE.getOrDefault(p.item(), 0L));
    case Prices.InStock s -> s.answer(TABLE.containsKey(s.item()));
    case Prices.Discount d -> d.answer(d.sum() / 10);
  }, body);
}
```

One function from the operation to its answer. `op.answer(x)` takes a value of the operation's own type. The same
function answers an operation asked alone and each operation of a set; `answeringAtOnce(..)` answers the
operations of a set at the same time, each on a virtual thread — for answers that wait, a call or a query.

### One that needs the rest

```java
static <R> Comp<String, R> asking(boolean yes, Comp<String, ? super Eff<Ask<?>, R>> body) {
  return handle((op, k) -> switch (op) {
    case Ask.Confirm c -> k.resume(c, yes).map(result -> result + " (asked: " + c.question() + ")");
    case Ask.Fail<?> f -> k.unwind().map(left -> "failed: " + f.why());
  }, body);
}
```

`k` is the rest of the program, as a value:

| | |
|---|---|
| `k.resume(op, x)` | the rest goes on with the answer; what it gives is this clause's to go on with |
| `k.unwind()` | the rest is left: its finalizers run, nothing else of it |
| `k.resume(op, x)` twice | the rest runs twice, each time from here: a choice, a search |

A clause whose program is `k.resume(op, x)` and nothing else is answered in place, as `answering` is.

### One with a state

```java
static <A, R> Comp<A, R> counting(int from, Comp<A, ? super Eff<Counter<?>, R>> body) {
  return handle(from, (n, op, k) -> switch (op) {
    case Counter.Next next -> k.resume(next, n, n + 1);
  }, body);
}
```

The state is given to each clause, and `k.resume(op, x, next)` goes on with the next one. It is a value, never
changed in place: a rest resumed twice goes on twice from the state it was asked in.
`handle(s0, clause, (state, a) -> b, body)` makes the handler's value of the last state and the program's value.

### One that answers a set at once

The questions of a round of a block, a `gather`, a `performAll(ops)` are asked as a SET: all of them before any is
answered. A handler with a second clause is given the set whole:

```java
static <A, R> Comp<A, R> pricedByOneQuery(List<String> queries, Comp<A, ? super Eff<Prices<?>, R>> body) {
  return handle((op, k) -> {
    queries.add("one: " + op);
    return switch (op) {
      case Prices.Price p -> k.resume(p, TABLE.getOrDefault(p.item(), 0L));
      case Prices.InStock s -> k.resume(s, TABLE.containsKey(s.item()));
      case Prices.Discount d -> k.resume(d, d.sum() / 10);
    };
  }, (ops, k) -> {
    TreeSet<String> items = new TreeSet<>();
    for (Prices<?> op : ops) if (op instanceof Prices.Price p) items.add(p.item());
    queries.add("all of: " + items);                                    // one query for the whole set
    for (Prices<?> op : ops) switch (op) {
      case Prices.Price p -> k.answer(p, TABLE.getOrDefault(p.item(), 0L));
      case Prices.InStock s -> k.answer(s, TABLE.containsKey(s.item()));
      case Prices.Discount d -> k.answer(d, d.sum() / 10);
    }
    return k.resume();
  }, body);
}
```

The program does not change. `total(List.of("tea", "cup", "kettle"))` asks three prices and a discount: four
questions under `priced`, two queries under `pricedByOneQuery`. A handler without the second clause is asked the
operations of a set one by one, so every program runs under every handler of its effect.

## 4. Running, and the row

```java
String a = run(priced(quote("kettle")));                                    // "kettle 108"
String b = run(asking(true, priced(checkout(List.of("tea", "cup")))));      // "paid 14"
```

`run` takes only a program with nothing left to ask. It is called once, at the entry point — `main`, a test, a
request's handler — never inside a program.

The order of the handlers is the order of the row, the nearest first, and it is meaning: a state inside a choice is
one state for each way, outside it one state through them all. Three things about rows are worth knowing:

- A program's type ends with `?`, not `Pure`: `Eff<Prices<?>, ?>` reads "Prices, then whatever the caller has".
  Only what `run` takes is closed.
- A handler's parameter is `Comp<A, ? super Eff<Prices<?>, R>>` and its result `Comp<A, R>`: copy that line.
- `up(c)` moves a program one place out; `fit(c)` gives it a declared row when javac cannot find one.

When a row does not fit, javac says so in its own words. The three you will meet:

| javac says | It means |
|---|---|
| `method run .. cannot be applied` | an effect is left unhandled |
| `method asking .. cannot be applied` (a handler's name) | the handlers are in the wrong order, or this effect is not the first of the row |
| `inference variable Q has incompatible bounds` | an operation of an effect the row does not have, or at the wrong place: an `up` is missing or one too many |

## 5. Between a program and its handler

These stand in front of a handler, keep the effect in the row, and change nothing of the program:

```java
run(pricedByOneQuery(queries, rounds(ops -> seen.add(ops.size()), cached(total(cart)))));
```

| | |
|---|---|
| `cached(p)` | each operation asked once in a run: one equal to one asked before — records are equal by their fields — is answered by what that one was. For answers that do not change within a run |
| `rounds(see, p)` | shows what is really asked, as it is asked: every operation alone and every set whole |
| `observe(Effect.class, see, p)` | shows every operation, one by one; a program run for each |

With a cart of four items, two of them different, `cached` lets two prices through, in one set.

## 6. What is refused, and when

By javac:

- an answer of another type — `p.answer("four")`, `k.resume(p, "four")`;
- a handler that does not answer every operation of its effect: the `switch` is whole;
- an operation of an effect the row does not have, or of another effect than the block's first;
- an effect left unhandled, handlers in the wrong order.

When the program runs, at once and by name:

- the answer of another operation than the one asked;
- an operation of a set left without an answer;
- a `Var` read by `get()` outside its block's `then` or `yield`, or of another block;
- a rest whose clause only answered, resumed again.

## 7. What it costs

Measured on this repository's benchmarks, one run each, nanoseconds an operation:

| | ns |
|---|---|
| a chain under `answering` | 13 |
| a chain under a clause that only resumes | 19 |
| a chain under a clause with a state | 20 |
| a chain under a handler that takes the rest | 28 |
| plain Java ([direct style](../resumable/README.md)) under a handler in place | 5 to 11 |

A block costs more than a chain for each question: it keeps the names and works out the rounds. Under `answering`,
the same questions as a chain and as a block, two runs:

| | a chain | a block |
|---|---|---|
| two questions that need nothing | 15 | 51 |
| two questions, the second needing the first | — | 70 |
| a hundred questions | 23 | 34 as a `gather`, 38 as an `each` |

A small block pays for being a block; a large one costs about one and a half times a chain. It pays back where a
round saves a query: one call to a database is a million of these.

## 8. Rows as sets

Everything above gives each effect a place in the row. `okay.rows.Sets` is the same model with no places: a
program's row is a type bounded by the effects it asks, in any order. `SetsTest` compiles and runs the examples of
this section; `BookingSets` in the tests is a real flow of seven effects written so.

```java
import static okay.rows.Sets.*;
import okay.rows.Sets.And;

// an effect: each operation names its answer and its effect
sealed interface Ids { record Ref(String prefix) implements Ids, Operation<String, Ids> {} }
sealed interface Clock { record Now() implements Clock, Operation<Long, Clock> {} }

// a program: its row is the set of what it asks
static <R extends Ids & Clock> Comp<String, R> stamp(String prefix) {
  return perform(new Ids.Ref(prefix)).flatMap(ref -> perform(new Clock.Now()).map(now -> ref + "@" + now));
}

// a program of more effects uses it as it is
static <R extends Clock & Mail & Store & Ids> Comp<String, R> reserve(String who) {
  return stamp("B").flatMap(ref ->
      perform(new Store.Put(ref, who)).flatMap(ok -> perform(new Mail.Send(who)).map(sent -> ref)));
}
```

`perform` is `<T, F, R extends F> Comp<T, R> perform(Op<F, T, ?> op)`: that the row has the effect is the one bound
`R extends F`. There is no `up`, no `fit` and no name for the row; a class can name it once for its methods:
`final class Booking<R extends Store & Mail & Ids & Clock> { Comp<String, R> told(..) {..} }`.

A block is `Sets.together`, with the words of section 2; `t.perform` takes an operation of any effect the row has:

```java
static <R extends Ids & Clock & Store> Comp<String, R> quote(String who) {
  return together(t -> {
    Var<String> ref = t.perform(new Ids.Ref("Q"));
    Var<Long> now = t.perform(new Clock.Now());
    Var<Boolean> kept = ref.then(r -> t.perform(new Store.Put(r, who)));
    return t.yield(ref.with(now, (r, n) -> r + "@" + n), kept, (s, k) -> s + " " + k);
  });
}
```

The effect is declared differently from section 1, and has to be. Java refuses a type that inherits one generic
interface with two different arguments, so two effects that each extend `Operation<T, Self>` can never bound the
same row. Here the interface has no supertype, and each record says `Operation<Answer, Effect>`.

A handler is a function over a program, given the continuation, as in section 3 — of a row that is a LIST,
`And<F, R>`: "this effect, and the rest" can be said of a list only.

```java
static <A, R> Comp<A, R> identified(Comp<A, ? super And<Ids, R>> c) {
  return answering(op -> switch (op) { case Ids.Ref r -> r.answer(r.prefix() + "-1"); }, c);
}
<A, R> Comp<A, R> stored(Comp<A, ? super And<Store, R>> c) {
  return handle((op, k) -> switch (op) {
    case Store.Put p -> { db.put(p.key(), p.value()); yield k.resume(p, true); }
    case Store.Get g -> k.resume(g, Optional.ofNullable(db.get(g.key())));
  }, c);
}
static <A, R> Comp<A, R> numbered(Comp<A, ? super And<Ids, R>> c) {                 // a state
  return handle(1, (n, op, k) -> switch (op) { case Ids.Ref r -> k.resume(r, r.prefix() + "-" + n, n + 1); }, c);
}
static <A, R> Comp<Optional<A>, R> attempt(Comp<A, ? super And<Abort, R>> c) {       // another value; the rest left
  return handle(a -> pure(Optional.of(a)), (op, k) -> pure(Optional.empty()), c);
}
```

Where the handlers are put, the set is written as a list, once:

```java
var app = row(of(Store.class, Mail.class, Ids.class, Clock.class));
String ref = run(clocked(42, identified(mailed(stored(app.of(reserve("Ann")))))));
```

`app.of(p)` takes a program that asks nothing but the effects named, and gives it as a program of their list, in
the order of the handlers. Inside a program, a handler around a part of it leaves the rest in the program's own row:

```java
static <R extends Ids & Clock> Comp<Optional<String>, R> careful(boolean fail) {
  return attempt(Sets.<R>keep().row(of(Abort.class)).of(orFail(fail)));
}
```

A handler of a row of places — the library's (`State.run`, `Clock.at`, `Choose.all`), or one written before —
goes around a program of a set with `place`, which makes the list's first effect the first place of a row:

```java
static <R extends State<Integer> & Clock & Ids> Comp<String, R> counted() {        // okay.rows.std's State, Clock
  return perform(new State.Get<Integer>()).flatMap(n -> perform(new State.Put<>(n + 1)).flatMap(put ->
      perform(new Clock.Now()).flatMap(at -> perform(new Ids.Ref("C")).map(ref -> ref + ":" + n + "@" + at))));
}

var app = row(of(Ids.class, Sets.<State<Integer>>the(State.class), Clock.class));
State.Final<Integer, String> end =
    run(Clock.at(() -> 5L, place(State.run(10, place(identified(app.of(counted())))))));
```

An operation asked by its class passes every handler of a place on its way; inside `place` the operations of that
one effect go to the nearest place instead. An effect with type arguments is named for the list with
`Sets.<State<Integer>>the(State.class)`: a class says nothing of them.

A clause that asks another effect asks it through its continuation, which knows the list that stays:
`ask(k, new Clock.Now()).flatMap(now -> .. k.resume(s, null))`, in a handler whose type says that list has it —
`<A, R> Comp<A, And<Clock, R>> audited(Comp<A, ? super And<Mail, And<Clock, R>>> c)`.

What javac refuses, each a program in `src/test/negative/rows`: an operation of an effect the row is not bounded
by; a program that asks more than the list names; a handler missing at `run`; handlers out of the list's order; a
handler given a program whose set was not written as a list; a handler inside a program that leaves an effect the
row has not; `ask(k, ..)` of an effect the list that stays may not have.

What to know:

- An operation finds its handler by the CLASS of its effect: the nearest handler of that effect answers. Two
  handlers of one effect are two scopes, not two places; an effect with type arguments of its own is one effect
  whatever they are.
- The value `row(..)` gives has a type that cannot be written — an intersection javac inferred — so it lives in a
  `var` or in the expression, not in a field. The handlers' nest can be a function of its own: its parameter is a
  program of the list, and that type can be written.
- `of` and `row` exist for one to eight effects.
- The two kinds of row are different types: `Effects`' handlers take a program of a set only through `place`, and
  `Sets`' none of places. A set of operations asked at once is asked one by one; plain Java (`okay.resumable`) is
  of rows of places only, for now.
- Measured roughly (two runs, a million questions under four handlers, ns a question): `answering` 16, a clause
  with `k` 19, a state 24 — the same programs on rows of places: 14, 16, 21.

## 9. The other forms

The page above is the whole of what most code needs. The rest is there for a reason each:

- **An effect with type parameters of its own**, as `State<S>`: its records name their answers themselves,
  `record Get<S>() implements State<S>, Op<State<S>, S, Get<S>> {}`. Javac cannot tell `Get<S>` by an index.
  Everything on this page works with it.
- **The library's effects** — `State`, `Reader`, `Writer`, `Throws`, `Choose`, `Fresh`, `Clock`, `Emit`, `Async`,
  `Trail` in `okay.rows.std` — are written in that form, with `Handler` and `InPlace`: handlers as classes, with a
  `ret` for the result. [The row notation](rows.md) is their reference.
- **Control and audit**: `intercept`, `observe`, sensitive effects that only an audit may handle —
  [the row notation](rows.md#control-and-audit).
- **Finalizers**: `c.ensuring(fin)`, `bracket(acquire, use, release)` — [the row notation](rows.md).
- **Plain Java**: methods with their effects in `throws`, with or without the build plugin —
  [direct style](../resumable/README.md).
- **The capability notation**, `okay.core`: effects passed as values at a level — [the guide](guide.md).
