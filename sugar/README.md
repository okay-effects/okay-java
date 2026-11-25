# okay-java-sugar

Sugar by annotations for `okay.rows`. An annotation processor that only GENERATES source: nothing is transformed,
nothing of javac's own is touched, nothing of this module is needed at run time. What it generates is ordinary
`okay.rows` code, in `build/generated/sources`, readable and steppable in a debugger.

A prototype (branch `sugar`): not released.

## An effect's functions and its handler: `@Effect`

The effect is written by hand, as `okay.rows` has it, and stays what it is — its records are the names a handler
switches over:

```java
@Effect
sealed interface Prices<T> extends Operation<T, Prices<?>> {
  record Price(String item) implements Prices<Long> {}
  record InStock(String item) implements Prices<Boolean> {}
  record Seen(String item) implements Prices<Void> {}
}
```

The processor generates `PricesOps` beside it:

| generated | what it is |
|---|---|
| `PricesOps.price(item)`, .. | the program that asks an operation: a `Comp<Long, Eff<Prices<?>, ?>>` |
| `interface PricesOps.Handler { long price(String item); boolean inStock(String item); void seen(String item); }` | the handler that only answers, a method for each operation: a box is its primitive, `Void` is `void` |
| `PricesOps.handledBy(impl, program)` | the program under an implementation of it, answered in place |

```java
final class Table implements PricesOps.Handler {
  public long price(String item) { return table.get(item); }
  public boolean inStock(String item) { return table.containsKey(item); }
  public void seen(String item) { seen.add(item); }
}
run(PricesOps.handledBy(new Table(..), program));
```

A handler that needs the rest of the program is written as ever, over the same records:
`handle((op, k) -> switch (op) { case Prices.Price p -> k.resume(p, 1L); .. }, program)`.

An operation of any answer — `record Fail<T>(String why) implements Ask<T>` — is a generic function,
`<T> Comp<T, ..> fail(String why)`, and a generic method of `Handler`, which no implementation can answer but by
throwing: such an operation wants a handler that leaves the rest (`k.unwind()`).

Refused, each with its reason (`RefusedTest`): a type that is not a sealed interface of records; an effect with a
type parameter of its own (`State<S>`).

## The row of a class, named once: `@Uses`

```java
@Uses({Prices.class, Ask.class})
final class Checkout {
  static Comp<String, Eff<Prices<?>, Eff<Ask<?>, ?>>> checkout(List<String> cart) {
    return total(cart).flatMap(sum ->
        sum <= 100 ? pure("paid " + sum)
            : confirm("pay " + sum + "?").map(yes -> yes ? "paid " + sum : "declined"));
  }
}
```

generates `CheckoutOps`: a function for every operation of every effect listed, each already at its place in the
row and all of the row's one type. With `import static ..CheckoutOps.*` a program has no `up` and no
`perform(new ..)`. The effects listed need not be marked `@Effect`.

What it does not remove: the row is still written in each program's type — Java has no name for a type.

## Using it

```kotlin
dependencies {
    compileOnly(project(":okay-java-sugar"))
    annotationProcessor(project(":okay-java-sugar"))   // javac runs no processor by default since JDK 23
}
```
