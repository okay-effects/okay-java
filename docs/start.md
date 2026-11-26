# Start here

Effects in Java, the light way: an effect is a small sealed family of records, a program asks its operations, a
handler answers them, and the program's type says what it may ask. Nothing is passed around, no plugin is needed,
and everything runs on one machine — a loop, so a program takes no Java stack however long it is.

`StartTest` compiles and runs every example on this page; the plain-Java body is `SetBodyTest`'s, in
`okay-java-resumable`.

```java
import static okay.rows.Sets.*;
import okay.effect.*;      // Operation, Eff, Pure
import okay.rows.*;        // Comp, Var
```

1. [An effect](#1-an-effect)
2. [A program](#2-a-program): a block, a chain, or plain Java
3. [A handler](#3-a-handler): by what it needs
4. [Where the handlers are put](#4-where-the-handlers-are-put)
5. [Between a program and its handler](#5-between-a-program-and-its-handler)
6. [What is refused, and when](#6-what-is-refused-and-when)
7. [What it costs](#7-what-it-costs)
8. [What to know](#8-what-to-know)
9. [The other forms](#9-the-other-forms)

## 1. An effect

A sealed interface, and a record for each operation. A record says the type of its answer and its effect.

```java
sealed interface Prices {
  record Price(String item) implements Prices, Operation<Long, Prices> {}
  record InStock(String item) implements Prices, Operation<Boolean, Prices> {}
  record Discount(long sum) implements Prices, Operation<Long, Prices> {}
}
```

An operation is data: it can be compared, printed, kept, sent. Nothing happens when one is made.

## 2. A program

A program is a value, `Comp<A, R>`: `A` is what it gives, `R` the effects it may ask — its ROW. A method says its
row as a type bounded by those effects, in any order:

```java
static <R extends Prices & Ask> Comp<String, R> checkout(List<String> cart) { .. }
```

There are three ways to write a program. They are the same thing underneath and mix freely.

| Way | Reads as | Use it when |
|---|---|---|
| a block, `together` | questions one after another, each answer a name | several questions, some depending on others |
| a chain, `flatMap` / `fold` | each step a function of the answer before | a loop that goes by the answers, a recursion |
| plain Java, `Direct.body` | a method with `for` and `if`, answers in locals | you want exactly that |

### A block

```java
static <R extends Prices> Comp<String, R> quote(String item) {
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
are independent, and a handler may answer those of its effect together ([below](#3-a-handler)).

A plain loop of questions is `t.gather`; a loop over an answer that is a list is `t.each`; a branch by an answer is
`t.when`; a loop by a state is `t.loop`:

```java
static <R extends Prices> Comp<Long, R> total(List<String> cart) {
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
| `t.each(list, x -> ..)` | for every element of an answer that is a list, the questions its function writes |
| `t.when(cond, () -> .., () -> ..)` | the questions of one branch, chosen by an answer |
| `t.loop(start, more, next)` | turn after turn while the state says so: a read page after page |
| `t.ask(comp)` | any program of the row as a question: a chain, another block |
| `t.value(v)` | a name for a value |

An answer is a value only inside the function that is given it — `p -> ..`, `(s, o) -> ..`. That is the one thing
a block does not give: an answer in a plain local, a line below the question.

### A chain

```java
static <R extends Prices> Comp<Long, R> inStockTotal(List<String> cart) {
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

### Plain Java

With `okay-java-resumable` on the class path (no plugin needed), a body is a method's own code:

```java
static <R extends Prices & Ask> Comp<String, R> checkout(List<String> cart) {
  return Direct.body(d -> {
    long sum = 0;
    for (String item : cart) if (d.perform(new Prices.InStock(item))) sum += d.perform(new Prices.Price(item));
    if (sum > 100 && !d.perform(new Ask.Confirm("pay " + sum + "?"))) return "declined";
    return "paid " + sum;
  });
}
```

Answers in locals, `for` and `if` as they are; a method the body calls takes `d` as a parameter. See
[a body of a row that is a set](../resumable/README.md#a-body-of-a-row-that-is-a-set); the rest of such a body is
resumed once.

### Several effects

The row is the set of what a program asks, and a program of fewer effects is used in one of more as it is —
nothing is written for it:

```java
static <R extends Prices & Ask> Comp<String, R> checkout(List<String> cart) {
  return total(cart).flatMap(sum ->                                   // total asks Prices only
      sum <= 100 ? pure("paid " + sum)
          : perform(new Ask.Confirm("pay " + sum + "?")).flatMap(yes ->
              yes ? pure("paid " + sum) : perform(new Ask.Fail<String>("declined"))));
}
```

`perform` is `<T, F, R extends F> Comp<T, R> perform(Op<F, T, ?> op)`: that the row has the effect is its one
bound, `R extends F`. A class can name a row once for its methods:

```java
final class Till<R extends Prices & Ask & Counter> {
  Comp<String, R> receipt(List<String> cart) { .. }
}
```

## 3. A handler

A handler is a function over a program: it takes one whose row is a LIST beginning with its effect — `Eff<Prices,
R>`, "Prices, and the rest" — and gives one of the rest. Which kind to write depends on what it needs:

| It needs | Write | |
|---|---|---|
| only to answer | `answering(op -> .. op.answer(x) ..)` | the usual one: a lookup, a clock, a stub in a test |
| the rest of the program | `handle((op, k) -> ..)` | to go on after it, leave it, resume it twice |
| a value of its own | `handle(a -> .., (op, k) -> ..)` | the program's value made another: an `Optional`, a list |
| a state | `handle(s0, (state, op, k) -> ..)` | a counter, a log, a cache |
| a set of operations whole | `handle(clause, (ops, k) -> ..)` | one query for a round |

### One that only answers

```java
static <A, R> Comp<A, R> priced(Comp<A, ? super Eff<Prices, R>> body) {
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
static <R> Comp<String, R> asking(boolean yes, Comp<String, ? super Eff<Ask, R>> body) {
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

With a first function the handler's value is another than the program's:

```java
static <A, R> Comp<Optional<A>, R> attempt(Comp<A, ? super Eff<Ask, R>> body) {
  return handle(a -> pure(Optional.of(a)), (op, k) -> switch (op) {
    case Ask.Confirm c -> k.resume(c, true);
    case Ask.Fail<?> f -> k.unwind().map(left -> Optional.empty());
  }, body);
}
```

### One with a state

```java
static <A, R> Comp<A, R> counting(int from, Comp<A, ? super Eff<Counter, R>> body) {
  return handle(from, (n, op, k) -> switch (op) {
    case Counter.Next next -> k.resume(next, n, n + 1);
  }, body);
}
```

The state is given to each clause, and `k.resume(op, x, next)` goes on with the next one. It is a value, never
changed in place: a rest resumed twice goes on twice from the state it was asked in.
`handle(s0, clause, (state, a) -> b, body)` makes the handler's value of the last state and the program's value.

### One that answers a set at once

The questions of a round of a block, or a `gather`, that are of one effect are asked as a SET: all of them before
any is answered. A handler with a second clause is given the set whole:

```java
static <A, R> Comp<A, R> pricedByOneQuery(List<String> queries, Comp<A, ? super Eff<Prices, R>> body) {
  return handle((op, k) -> {
    queries.add("one: " + op);
    return switch (op) {
      case Prices.Price p -> k.resume(p, TABLE.getOrDefault(p.item(), 0L));
      case Prices.InStock s -> k.resume(s, TABLE.containsKey(s.item()));
      case Prices.Discount d -> k.resume(d, d.sum() / 10);
    };
  }, (ops, k) -> {
    TreeSet<String> items = new TreeSet<>();
    for (Prices op : ops) if (op instanceof Prices.Price p) items.add(p.item());
    queries.add("all of: " + items);                                    // one query for the whole set
    for (Prices op : ops) switch (op) {
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

### A clause that asks another effect

It asks through its continuation, which knows the list that stays — in a handler whose type says that list has the
effect:

```java
<A, R> Comp<A, Eff<Clock, R>> audited(Comp<A, ? super Eff<Mail, Eff<Clock, R>>> c) {
  return handle((op, k) -> switch (op) {
    case Mail.Send s -> ask(k, new Clock.Now()).flatMap(now -> {
      sent.add(s.to() + " at " + now);
      return k.resume(s, null);
    });
  }, c);
}
```

### The library's effects

`okay.rows.std` — `State`, `Reader`, `Writer`, `Throws`, `Choose`, `Fresh`, `Clock`, `Emit`, `Async`, `Trail` — are
effects of the same form, asked the same way, each with its own handler:

```java
static <R extends State<Integer> & Prices> Comp<Long, R> spend(String item) {
  return perform(new Prices.Price(item)).flatMap(price ->
      perform(new State.Get<Integer>()).flatMap(left ->
          perform(new State.Put<>(left - price.intValue())).map(done -> price)));
}

var wallet = row(of(Prices.class, Sets.<State<Integer>>the(State.class)));
State.Final<Integer, Long> end = run(State.run(100, priced(wallet.of(spend("cup")))));   // 89 left, 11 spent
```

An effect with type arguments is named for the list by `Sets.<State<Integer>>the(State.class)`: a class says
nothing of them.

## 4. Where the handlers are put

A handler takes a program of a list; a program has a set. Where the handlers are put, the set is written as a
list, once — `row(of(..))`, the effects in the order of the handlers, the nearest first:

```java
var till = row(of(Prices.class, Ask.class));
String a = run(asking(true, priced(till.of(checkout(List.of("tea", "cup"))))));      // "paid 14"
```

`till.of(p)` takes a program that asks nothing but the effects named — fewer is well — and gives it as a program
of their list. `run` takes only a program with nothing left to ask; it is called once, at the entry point — `main`,
a test, a request's handler — never inside a program.

The order of the handlers is the order of the list, and it is meaning: a state inside a choice is one state for
each way, outside it one state through them all. The program does not say it: `row(of(Ask.class, Prices.class))`
puts the same program under `priced(asking(..))`.

**The boundary of an application, kept.** The value `row(..)` gives has a type that cannot be written — an
intersection javac inferred — so it lives in a `var` or in the expression. What can be a field, a bean, a
parameter is a class whose type parameter is bounded by the effects:

```java
final class Shop<V extends Prices & Ask> {
  String checkout(boolean yes, Comp<String, ? super V> p) {
    var row = Sets.<V>exactly(Shop.class).row(of(Prices.class, Ask.class));
    return run(asking(yes, priced(row.of(p))));
  }
}

static final Shop<?> SHOP = new Shop<>();
String b = SHOP.checkout(true, checkout(List.of("tea", "cup")));
```

javac refuses `SHOP.checkout(.., p)` for a program that asks more than `V` is bounded by, and a list that names an
effect `V` is not bounded by. The third way to be wrong it cannot see — `V` bounded by an effect the list does not
name, one no handler would answer — so `exactly(Shop.class).row(..)` checks that when it is called, by the class's
own declaration, and refuses there.

**A handler inside a program** — around a part of it — leaves the rest in the program's own row:

```java
static <R extends Prices> Comp<Optional<String>, R> tried(List<String> cart) {
  return attempt(Sets.<R>keep().row(of(Ask.class)).of(checkout(cart)));      // Ask answered here; Prices stays
}
```

## 5. Between a program and its handler

These stand in front of a handler, keep the effect in the list, and change nothing of the program:

```java
run(pricedByOneQuery(queries, rounds(ops -> seen.add(ops.size()), cached(shop.of(total(cart))))));
```

| | |
|---|---|
| `cached(p)` | each operation asked once in a run: one equal to one asked before — records are equal by their fields — is answered by what that one was. For answers that do not change within a run |
| `rounds(see, p)` | shows what is really asked, as it is asked: every operation alone and every set whole |
| `observe(Effect.class, see, p)` | shows every operation, one by one; a program run for each |

With a cart of four items, two of them different, `cached` lets two prices through, in one set.

## 6. What is refused, and when

By javac, each a program in `src/test/negative/rows`:

- an operation of an effect the row is not bounded by — in a chain, a block, a body;
- an answer of another type — `p.answer("four")`, `k.resume(p, "four")`;
- a handler that does not answer every operation of its effect: the `switch` is whole;
- a program that asks more than the list names; a handler missing at `run`; handlers out of the list's order;
- a handler given a program whose set was not written as a list;
- a handler inside a program that leaves an effect the row has not;
- `ask(k, ..)` of an effect the list that stays may not have.

javac's words for them: `inference variable R has incompatible bounds` — an operation the row does not have, or a
program that asks more than the list; `method run .. cannot be applied` — an effect left unhandled; a handler's name
`.. cannot be applied` — the wrong order, or no list.

When the program runs, at once and by name:

- the answer of another operation than the one asked;
- an operation of a set left without an answer;
- a `Var` read by `get()` outside its block's `then` or `yield`, or of another block;
- a rest whose clause only answered, resumed again;
- a named set bounded by an effect its list does not name (`exactly`).

## 7. What it costs

Measured on this repository's benchmarks (`./gradlew setsBench`, `rowsBench`), nanoseconds an operation, the same
program under four handlers:

| | ns |
|---|---|
| a chain under `answering`, the outermost of four | 17 |
| a chain under a clause that only resumes | 15 |
| a chain under a clause with a state | 22 |
| a block of two questions of two effects | 88 a question |
| plain Java (`Direct.body`) under `answering` / under a clause that only resumes | 6 / 12 |

A block costs more than a chain for each question: it keeps the names and works out the rounds; a large one — a
hundred questions of one effect — costs about one and a half times a chain (34 against 23). It pays back where a
round saves a query: one call to a database is a million of these.

## 8. What to know

- An operation goes to no place: on its way out, the nearest handler whose effect it is of answers. Two handlers
  of one effect are two scopes; an effect with type arguments is one effect whatever they are.
- A handler knows the class of its effect from where it is made — `answering(.., c)` in a method whose parameter
  says `Eff<Prices, R>`, `handle(State.class, ..)`. Write a handler where its effect is a named type: in a helper
  generic in the effect (`<F> .. Eff<F, R>`) it learns none, and an operation finds no handler when it is asked.
- `of` and `row` exist for one to twelve effects.
- An effect is declared with no supertype of its own that names it, each record saying
  `Operation<Answer, Effect>`. Java refuses a type that inherits one generic interface with two different
  arguments, so effects that each extended a generic `Operation<.., Self>` could never bound the same row.

## 9. The other forms

Needed for a reason each; all work together with the above.

- **Rows of places** ([places.md](places.md)): each effect has a place in the row, `Eff<Prices<?>, Eff<Ask<?>,
  ?>>`, written by the program too; the second effect is asked with `up(perform(op))`; an effect is a sealed
  interface indexed by its answers, `sealed interface Prices<T> extends Operation<T, Prices<?>>`. It tells two
  handlers of one effect apart by their places, and an effect with type arguments by its arguments. The handlers
  are the same ones, and both kinds of program go under them.
- The handlers as classes — `Handler<F, A, B, R>` (`ret`, `on(op, k)`) and `InPlace<F, S, A, B, R>` (`ret`,
  `answer(cell, op)`), put by `handle(Effect.class, h, p)` — and the other words of the row notation: `under`,
  `fit`, `defer`, `intercept`, `ensuring`, `bracket`, `step`, `k.after(op, c)`: [rows.md](rows.md).
- Sensitive operations under audit, the capability notation `okay.core.Comp<A, L>`, direct style with the plugin
  (a rest resumed more than once in plain Java): [rows.md](rows.md), [guide.md](guide.md),
  [../resumable/README.md](../resumable/README.md).
