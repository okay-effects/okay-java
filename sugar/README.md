# okay-java-sugar

Sugar by annotations for `okay.rows`. An annotation processor that only GENERATES source: nothing is transformed,
nothing of javac's own is touched, nothing of this module is needed at run time. What it generates is ordinary
`okay.rows` code, in `build/generated/sources`, readable and steppable in a debugger.

A prototype (branch `sugar`): not released.

## An effect as a plain interface: `@Effect`

```java
@Effect
interface Prices {
  long price(String item);
  boolean inStock(String item);
  void seen(String item);
}
```

generates `PricesOp<T>` beside it — the effect as `okay.rows` has it:

| generated | what it is |
|---|---|
| `record PricesOp.Price(String item) implements PricesOp<Long>`, .. | an operation as data, one for each method |
| `PricesOp.price(item)`, .. | the program that asks it: a `Comp<Long, Eff<PricesOp<?>, ?>>` |
| `PricesOp.handledBy(impl, program)` | the handler an implementation of `Prices` is: it answers in place |

A handler that only answers is then a class that implements the interface. A handler that needs the rest of the
program is written as ever, a `switch` over the generated records:

```java
var counted = handle(0L, (n, op, k) -> switch (op) {
  case PricesOp.Price p -> k.resume(p, 1L, n + 1);
  case PricesOp.InStock s -> k.resume(s, true, n + 1);
  case PricesOp.Seen s -> k.resume(s, null, n + 1);
}, (n, sum) -> n, program);
```

Refused, each with its reason: an interface with type parameters; a generic method; two methods of one name; a
method with a `throws` (`RefusedTest`).

## The row of a class, named once: `@Uses`

```java
@Uses({Prices.class, Ask.class})
final class Checkout {
  static Comp<String, Eff<PricesOp<?>, Eff<Ask<?>, ?>>> checkout(List<String> cart) {
    return total(cart).flatMap(sum ->
        sum <= 100 ? pure("paid " + sum)
            : confirm("pay " + sum + "?").map(yes -> yes ? "paid " + sum : "declined"));
  }
}
```

generates `CheckoutOps`: a function for every operation of every effect listed, each already at its place in the
row and all of the row's one type. With `import static ..CheckoutOps.*` a program has no `up` and no
`perform(new ..)`. An effect listed is an interface marked `@Effect` or an effect written by hand (a sealed
interface whose operations are records, generic records too).

What it does not remove: the row is still written in each program's type — Java has no name for a type.

## Using it

```kotlin
dependencies {
    compileOnly(project(":okay-java-sugar"))
    annotationProcessor(project(":okay-java-sugar"))   // javac runs no processor by default since JDK 23
}
```
