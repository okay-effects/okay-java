# okay-java in Spring Boot

`io.github.okay-effects:okay-java-spring-boot`, the `spring-boot/` module, is optional: okay-java itself depends on
nothing of Spring's. On the classpath of an application of Spring Boot 4.x it configures itself, each part only
where its part of Spring is:

| Part | On where | What it gives |
|---|---|---|
| `OkayWebMvcAutoConfiguration` | a servlet application with web MVC | a controller's method returns a `Comp` |
| `OkayWebFluxAutoConfiguration` | a reactive application with WebFlux | the same |
| `OkayAsyncAutoConfiguration` | any application; in force where `@EnableAsync` is | an `@Async` method returns a `Comp` |
| `OkayTransactionAutoConfiguration` | where there is one `PlatformTransactionManager` | `EffTransactions`: a program in a transaction |

Any of them is off by `spring.autoconfigure.exclude=okay.spring.<name>`.

## Setup

```kotlin
repositories {
    mavenCentral()
    maven("https://okay-effects.github.io/okay-java/maven")
    maven("https://okay-effects.github.io/okay-kernel/maven")
}

dependencies {
    implementation("io.github.okay-effects:okay-java-spring-boot:0.1.0")   // okay-java comes with it
}
```

The module's Spring is `compileOnly`: its POM has one dependency, okay-java, and the application's own Spring Boot
is the one used. It is built and tested on Spring Boot 4.1.1 (Spring Framework 7.0). Released with okay-java
(the README's Install, `RELEASING.md`).

Nothing else is needed outside these four parts. okay-java has no state to be a bean: no runtime, no pool, no
configuration. `Comp.run` and `Async.runAsync` are called wherever a program is to run, in a service, a
`CommandLineRunner`, a `@Scheduled` method, a listener.

## Controllers

A method may return a program at the top, `Comp<A, Comp.Top>`. Its value is the method's, as if the method had
returned it, so `A` decides how it is written: a body (JSON, a string), a `ResponseEntity` with its status and
headers, a view's name. A failure no guard took is the method's throw and goes to the `@ExceptionHandler`s.

```java
@RestController
class Orders {
  @GetMapping("/sum/{n}")
  Comp<Total, Comp.Top> sum(@PathVariable("n") int n) {
    return State.state(0, new State.Body<Comp.Top, Integer, String>() {
      public <M extends Comp.Top> Comp<String, M> in(State<M, Integer> st) { … }
    }).map(f -> new Total(f.value(), f.state()));
  }

  @GetMapping("/created")
  Comp<ResponseEntity<String>, Comp.Top> created() {
    return Comp.<Comp.Top, String>pure("made").map(s -> ResponseEntity.status(HttpStatus.CREATED).body(s));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<String> bad(IllegalArgumentException e) { return ResponseEntity.badRequest().body(e.getMessage()); }
}
```

A program that waits needs nothing of the module. `Async.runAsync` gives a `CompletableFuture`, which web MVC and
WebFlux both take as they are:

```java
@GetMapping("/async")
CompletableFuture<String> async() {
  return Async.runAsync(new Async.Body<String>() {
    public <M extends Comp.Top> Comp<String, M> in(Async<M> as) { return as.blocking(() -> load()); }
  });
}
```

**Web MVC** (`EffReturnValueHandler`). The program runs on the request's thread. Its value is handed on as a
completed `CompletableFuture`'s is, through an async dispatch, so its own type decides how it is written. The
handler goes first among the adapter's. One added the usual way, by a `WebMvcConfigurer`, would come after
`@ResponseBody`'s, which would write the program object itself as JSON.

**WebFlux** (`EffResultHandler`). The program runs when the response is subscribed to, on the thread that
subscribes, an event loop's. Its value is handed to the other result handlers as of the type `A`. It is not a
`ReactiveAdapter`: an adapted type's value is its first type argument, and a `Comp`'s first is its level. A program
that blocks, a database or a file, belongs on `Async`'s `blocking`, not on the event loop.

## `@Async`

With `@EnableAsync`, a method annotated `@Async` may return a `Comp<A, Comp.Top>`. The method and its program run on
the `@Async` executor: the one `@Async("name")` names, an `AsyncConfigurer`'s, or the application's `TaskExecutor`,
as Spring chooses. The call returns at once with a program that is their result.

```java
@Service
class Reports {
  @Async
  public Comp<Report, Comp.Top> build(Month m) { return … ; }
}

Comp<Report, Comp.Top> report = reports.build(m);   // started, not waited for
…
Report r = Comp.run(report);                         // waits for it
```

The result run waits for the value, this thread blocked: on a virtual thread that costs no thread. It fails with the
program's failure or the method's throw, so `Comp.recover` takes either. Run again, it gives the same value; the method
does not run twice.

Spring's own `@Async` interceptor takes nothing but `void` and a `Future`, refusing any other type before the call,
and it goes before every other advisor, so no advisor in front of it could take a `Comp`. The module puts an
interceptor of its own in its place in each proxy (`EffAsyncPostProcessor`, `EffAsyncInterceptor`). Every method
that does not return a `Comp` is handed to Spring's interceptor, as it was. A frozen proxy is left as it is.

## Transactions

`EffTransactions` wraps a program in a transaction of Spring's:

```java
@Service
class Checkout {
  private final EffTransactions txs;
  Checkout(EffTransactions txs) { this.txs = txs; }

  Comp<Order, Comp.Top> save(Cart cart) { return Comp.sync(() -> repository.save(cart.order())); }

  <M extends Comp.Top> Comp<Order, M> place(State<M, Cart> st) {
    return st.get().flatMap(cart -> txs.transactional(save(cart)));
  }
}
```

`transactional(body)` begins the transaction when the run gets to it, not when the program is built. It commits
after the body's value and rolls back however else the body is left: a failure, an abort or a cancellation of the
kernel's. The body runs in the caller's run, not a nested one. `transactional(definition, body)` takes the
propagation, isolation and timeout; a `TransactionTemplate` is a definition. Without the auto-configuration,
`new EffTransactions(transactionManager)` is the same.

**The body is a program at the top.** Its handlers are its own, and a capability of a handler outside it does not
compile there. A handler outside could drop the rest of the body: a `Throws.raise` to a `catching` outside the
transaction. A dropped rest runs no finalizer, `Comp.ensuring`'s included, so the transaction would be left open on
the thread, and the next one there would join it. A handler inside the body drops nothing past it, and its raise
caught inside commits the recovery. The transaction's value goes on at any level: a program at the top fits a
bind's function anywhere, as in `place` above.

**The transaction is the thread's**, as Spring's are. A program of okay-java's own runs on one thread from the begin
to the end. One that goes on in another thread, an `Async` callback's, leaves the transaction behind.

**`@Transactional` on a method that returns a `Comp`** commits when the method returns, before the program runs. The
program's transaction is `EffTransactions`'.

## Tests

`./gradlew :okay-java-spring-boot:test`, or `./gradlew test` with okay-java's own.

| Test | What it runs |
|---|---|
| `EffControllerTest` | one controller on Tomcat, over HTTP: a value, a handler's value as JSON, a `ResponseEntity`, a failure to the `@ExceptionHandler`, a recovered failure, an `Async` future |
| `WebFluxTest` | the same controller and checks on Netty |
| `AsyncEffTest` | `@Async` programs: the call returning before the method runs, another thread, the program's failure and the method's throw, Spring's `CompletableFuture` as before |
| `TransactionsTest` | a manager that writes down what it is asked: commit, rollback on a failure, nothing begun before the run, a raise caught inside, a transaction under a handler |
| `TransactionLeakTest` | a capability from outside in a transaction's body does not compile; the body with its handler inside does |
| `AutoConfigurationTest` | each part on where it should be and off where not |
