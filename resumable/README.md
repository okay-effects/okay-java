# okay-java-resumable

Plain direct-style Java methods with effects. Each method lists its effects as checked exceptions. The module makes
these methods resumable, once or many times, and bridges them into okay-java's typed effects, `okay.rows`. Nothing is
replayed: when a continuation is resumed again, the saved frames are re-entered. The body is never re-run from its
start.

```java
static Quote quote(List<String> cart) throws Prices, Delivery {
  int total = 0;
  for (String item : cart) total += Direct.perform(new Prices.Price(item));
  String delivery = Direct.perform(new Delivery.Choose());
  return new Quote(delivery, delivery.equals("courier") ? total + 15 : total);
}

// a Comp<Quote, Eff<Delivery, Eff<Prices, Pure>>>
var program = Direct.row(Delivery.class, Prices.class).of(() -> quote(cart));
run(priced(Map.of("tea", 4, "cup", 11), everyDelivery(program)));
// [Quote[delivery=pickup, total=19], Quote[delivery=courier, total=34]]
```

`priced` is an `InPlace` handler and `everyDelivery` is a general `Handler` that resumes twice. Both are ordinary
`okay.rows` handlers (`samples/*/src/main/java/sample/Shop.java`). Once a program has crossed the boundary, everything
after it is `okay.rows` as it is: handlers, `intercept`, `observe`, `ensuring`, `up`/`under`.

Module `resumable/`, artifact `dev.okay:okay-java-resumable`, package `okay.resumable`. The package is named after
the family's word for this: okay-rust has `okay::resumable` and `#[resumable]`. It is not `okay.direct`, which is
the sibling library okay-direct's. okay-java does not depend on this module.

## Install

**Gradle**: one line.

```kotlin
plugins { id("dev.okay.resumable") }
```

Until the plugin is published, a build takes it from this repository as a composite build
(`samples/gradle/settings.gradle.kts`):

```kotlin
pluginManagement { includeBuild("<okay-java>/resumable/gradle-plugin") }
includeBuild("<okay-java>")
```

**Maven**: the library and the plugin (`samples/maven/pom.xml`):

```xml
<dependency>
  <groupId>dev.okay</groupId><artifactId>okay-java-resumable</artifactId><version>0.2.0-SNAPSHOT</version>
</dependency>
...
<plugin>
  <groupId>dev.okay</groupId><artifactId>okay-java-resumable-maven-plugin</artifactId><version>0.2.0-SNAPSHOT</version>
  <executions><execution><goals><goal>transform</goal><goal>transform-test</goal></goals></execution></executions>
</plugin>
```

For `java -jar`, also add the manifest entries `Add-Exports`, `Add-Opens` and `Launcher-Agent-Class` to the
`maven-jar-plugin`'s `<archive><manifestEntries>`, as the sample does. The transformation runs inside Maven's own
JVM, so Maven must run on JDK 24 or later (`java.lang.classfile`).

### What happens automatically

**Gradle plugin:**

- the library is added to `implementation`;
- every source set's Java classes are transformed in place when `compileJava`, `compileTestJava`, .. end. The
  transformer is an input of the task, so the task stays up to date and cacheable, and the configuration cache works;
- `Test` and `JavaExec` tasks are given the `--add-exports` and `--add-opens` arguments of the JVM's continuations;
- the `jar` task's manifest gets `Add-Exports`, `Add-Opens` and `Launcher-Agent-Class`.

**Maven plugin:**

- you add the library as a dependency;
- `transform` runs at `process-classes`, `transform-test` at `process-test-classes`;
- surefire's `argLine` property is set, as JaCoCo does;
- you add the manifest entries, as above.

Each launch mode:

- **`java -jar`**: the manifest gives the continuations. Its `Launcher-Agent-Class` transforms at load time any class
  that was not built with the plugin, such as a dependency. A jar built entirely without the plugin also works with no
  flags (`AgentTest`). The agent applies to `java -jar` only.
- **Classpath, an application server, a test runner with no plugin**: classes must have been transformed at build
  time. Without the exports, a handler declared `Once` takes saved frames instead of the continuations. This is said
  once, on the `okay.resumable` logger (`TiersTest`).
- **Gradle test/run, Maven surefire**: everything automatic. For `exec:java`, which runs in Maven's JVM, put the
  three arguments in `MAVEN_OPTS`.
- **An IDE that runs tests through the build**: the same as the build.
- **An IDE that compiles by itself**: nothing is transformed. The first `perform` refuses with a message that names
  the method and says to delegate the build and the tests to Gradle or Maven.

Kotlin, Scala and Groovy classes are not transformed by the plugins: only the Java compile tasks are followed.

## Effects are checked exceptions

An effect is a sealed abstract subclass of `Suspend`. Its operations are its final subclasses, and each operation is
also an `okay.rows.Op`:

```java
public abstract sealed class Counter extends Suspend {
  public static final class Get extends Counter implements Op<Counter, Integer, Get> {}
  public static final class Put extends Counter implements Op<Counter, Void, Put> { final int value; ... }

  public static int get() throws Counter { return Direct.perform(new Get()); }
}
```

Why this mapping:

- `Op<E, X, Self extends E>` requires an operation to be an `E`. A record cannot extend an exception class.
- A sealed interface cannot extend `Exception`.
- Java refuses a generic subclass of `Throwable`.

So the exception class is the effect: it is both the row's `E` in `Eff<Counter, ..>` and the type in `throws`. Its
operations are its subclasses, and a handler's `switch` over the sealed class is exhaustive. The same operations
can also be performed with `okay.rows.Effects.perform`, so one handler answers both kinds of program.

Two limits follow. An effect cannot have type arguments: write `Counter`, not `State<Integer>`. And an operation is
an exception object. It is never thrown and never given a stack trace.

What javac then checks:

- A method must declare, or handle, every effect it performs.
- Code inside a JDK lambda cannot perform, because `forEach`, `mapToInt` and the like do not declare the effect.
- The row at the boundary must cover every effect of the body.

These refusals are tested against `src/test/negative/*.java`, with errors at exactly the marked lines.

## The boundary

`Direct.row(E1.class, ..).of(body)` names the row once, in the order of its handlers, and gives a
`Comp<A, Eff<E1, Eff<E2, .., Pure>>>`. Overloads go up to four effects.

The boundary takes two steps, `row(..)` then `of(..)`, because javac does not infer the row in one step. Every
effect a lambda throws becomes a lower bound of every type variable in the interface's `throws`, so no single class
fits them all. After `row(..)` the effects are fixed, and `of` only checks the body against them. The classes are
also what routes an operation to its place in the row at run time.

## Three ways an operation runs

`perform` asks the handlers in force on its own Java stack (`okay.rows.Here`, a probe of the machine) where the
operation goes. The probe keeps what it found for each place while the body runs on that stack. Then:

1. **The handler is in place** (`InPlace`: state, reader, a log, an in-place interceptor). The operation is answered
   at once, on the body's stack, by the same handler, cell and walk that an `okay.rows` operation would get. Nothing
   is thrown or captured. If anything that must see the operation stands nearer, the operation does not take this
   path: a general interceptor, an observer, an audit, or an unwinding's mark. A sensitive operation never takes it.
2. **The handler is general and declares `okay.rows.Once`, and the JVM's continuations are exported.** The body
   runs in a `jdk.internal.vm.Continuation`. `perform` calls the handler's clause from the body's own stack
   (`Here.resumeHere`): the handler takes the rest, and if its clause resumes it at once (`k.resume(op, x)`, bound or
   mapped or not), the machine puts the rest back as it was and `perform` returns `x`. Nothing yields and nothing is
   frozen; most handlers that resume once resume so. If the clause does something first (asks an effect of its own,
   defers, keeps `k`), the body yields with the clause's program, and the JVM freezes and thaws its frames.
   Untransformed frames survive too. If a handler declared `Once` resumes twice, it is refused with a clear message;
   nothing is replayed. A handler that resumes more than once must not declare `Once`. If a yield is pinned, the
   frames are saved as in path 3.
3. **Any other handler** (general, possibly multi-shot). The frames are saved as data and re-entered.

The module's tests count which way each operation went (`-Dokay.resumable.trace=true`; off, the count is compiled
away): `TiersTest`, `SensitiveTest`.

## Sensitive effects

`okay.rows`' control and audit holds in direct style. A sensitive effect is a subclass of `SensitiveSuspend`, not
of `Suspend`, and its operations are `okay.rows.Sensitive.Op`s:

```java
public abstract sealed class Pay extends SensitiveSuspend {
  public static final class Transfer extends Pay implements Sensitive.Op<Pay, String, Transfer> { ... }
  public static final class Balance extends Pay implements Sensitive.Op<Pay, Integer, Balance> {}

  public static String transfer(String to, int amount) throws Pay { return Direct.perform(new Transfer(to, amount)); }
}

static int payments(List<String> log) throws Pay {
  try {
    Pay.transfer("a", 100);
    Pay.transfer("b", 500);   // the policy makes it 200
    int b = Pay.balance();
    Pay.transfer("c", 5000);  // the policy refuses it: the program is left, this finally runs
    return b;
  } finally {
    log.add("finally");
  }
}

// the sensitive place first in the row: a Comp<Integer, Sensitive<Pay, ?>>
var program = Direct.row(Direct.sensitive(Pay.class)).of(() -> payments(log));
run(Trail.keep(Pay.bank(1000, Audit.audit(policy, program))));
```

`Pay.bank` is `Audit.handle` of an `InPlace`, and `policy` an `okay.rows.Interceptor` that records each operation to
the trail, passes it on, rewrites or refuses it (`SensitiveTest`, the scenario of `okay.rows`' `Audits`). A
sensitive operation is never answered in place, even by that bank: it always passes its auditor. It goes by path 3,
or by path 2 when the interceptor declares `Once` (`Audit.audit` then declares it). The sensitive place's row is
open after its effects, `Sensitive<Pay, ? extends Eff<Log, ?>>`, because an interceptor asks effects of its own
outside the program: the trail, a failure. A plain row stays closed (`Pure`).

What javac refuses (`src/test/negative/SensitiveDirect.java`, `SensitiveUndeclared.java`):

- a sensitive effect at a plain place: `Direct.row(Pay.class)`, or after the sensitive one;
- a plain effect as a sensitive one: `Direct.sensitive(Note.class)`;
- a sensitive operation of a plain effect: `Direct.perform` takes a `Sensitive.Op` of a `SensitiveSuspend` only;
- a method that performs `Pay`, or calls one that does, without declaring it;
- the program run (`run`), handled (`handle`, `Audit.handle` without the audit), moved (`under`) or retyped to a plain
  place (`fit`) anywhere but under its audit — the rules of `okay.rows`' `Sensitive`.

What could still defeat it: an unchecked cast or a raw type, as in `okay.rows` itself. A raw `Comp` can be run or
handled plainly, and the machine then gives the operation to whatever handler stands at its place. Two such holes
are closed at run time: a raw `Class` of a sensitive effect in `Direct.row(..)` is refused, and an operation that is
both an `Op` and a `Sensitive.Op` is taken as sensitive. An effect's author can still add a plain `Op` to a
sensitive family: `Direct.perform` refuses to perform it, but `okay.rows`' `Effects.perform` does not.

## Handlers in direct style

A handler can be plain Java too. A clause is an ordinary method: it is given the operation and the rest `k`, and
returns the handler's result. `k.resume(op, x)` runs the rest with the answer `x` to the handler's result and returns
it, deep as `okay.rows`' `K`; it may be called once, many times, or never (`k.unwind()` runs the rest's finalizers).
The effects a clause performs, outside its handler, are its `throws`, and `Direct.Resumes` if it resumes:

```java
// a state written generally: every clause resumes in tail position
final class State<A> implements Direct.Handler0<Counter, A, Final<Integer, A>> {
  int s;

  public Final<Integer, A> ret(A a) { return new Final<>(s, a); }

  public Final<Integer, A> on(Counter op, Direct.K<Final<Integer, A>> k) throws Direct.Resumes {
    return switch (op) {
      case Counter.Get get -> k.resume(get, s);
      case Counter.Put put -> {
        s = put.value;
        yield k.resume(put, null);
      }
    };
  }
}
run(handle(Counter.class, Direct.handler(new State<>()), Direct.row(Counter.class).of(() -> count(1_000_000))));

// every path of a choice: the rest resumed once for each value
public List<A> on(Pick op, Direct.K<List<A>> k) throws Direct.Resumes {
  return switch (op) {
    case Pick.Among among -> {
      List<A> found = new ArrayList<>();
      for (Integer v : among.values) found.addAll(k.resume(among, v));
      if (among.values.isEmpty()) k.unwind();
      yield found;
    }
  };
}

// an interceptor whose clause tells the log and asks the counter, both outside it
intercept(Counter.class, Direct.row(Counter.class, Log.class).handler(
    new Direct.Handler2<Counter, A, A, Counter, Log>() {
      public A ret(A a) { return a; }
      public A on(Counter op, Direct.K<A> k) throws Counter, Log, Direct.Resumes {
        Log.tell("saw " + op);
        return switch (op) {
          case Counter.Get get -> k.resume(get, Counter.get());
          case Counter.Put put -> {
            Counter.put(put.value);
            yield k.resume(put, null);
          }
        };
      }
    }), program);
```

- **Installed as any handler.** `Direct.handler(h)` (no effect outside) and `Direct.row(E1.class, ..).handler(h)`
  (`Handler1`..`Handler4`) give an `okay.rows.Handler`, for `handle`, `intercept` or `Audit.handle`, in any order with
  `okay.rows`' handlers. Its row outside is the named effects, then any. `Direct.inPlace(h)` gives an `InPlace` from a
  `Direct.InPlace`, whose `answer` gets the cell and whose `ret` returns a value: its operations take path 1. A
  handler that implements `okay.rows.Once` declares it.
- **What javac checks.** A clause that performs an effect it does not declare, or declares one that is not the next
  outside its handler, does not compile; nor does `k.resume` inside a JDK lambda, which does not declare `Resumes`;
  nor a handler whose row outside does not match the program's (`HandlerUndeclared.java`, `HandlerWrongRow.java`).
- **How it runs.** Each clause runs as a program of the row outside the handler, transformed as a body is.
  `k.resume` hands the boundary the rest of the handled program, as `okay.rows` gives it: the clause's frames are
  saved as data, the rest runs on the machine, and the frames are resumed with its result. So a clause performs and
  resumes with the Java stack of one frame at a time, and resuming twice (a choice) re-enters the clause's frames
  twice, with locals copied and objects shared, as path 3. Nothing is replayed.
- **Tail position.** The transformation marks a call whose method only returns its value: a cast, an unboxing, a
  jump, then a return. When every frame saved at `k.resume` is so, the clause's program is the resumption itself, and
  nothing waits for it: nothing is kept for each operation. A general handler that resumes in tail position (the
  state above, a stream's consumer) runs a million operations at a constant Java stack
  (`constantStackAMillionOperationsResumedInTailPosition`). A clause that does something after `k.resume` holds its
  frames until the rest has run, as any deep handler holds its stack.
- `k` belongs to its handler's clauses: called anywhere else, it is refused at run time.

## The transformation

A method is rewritten when it has a call that may suspend. That is a call to a method whose `throws` names a
subclass of `Suspend` or `SensitiveSuspend` (`Direct.Resumes` among them), resolved up the callee's classes and
interfaces. This covers the methods that declare effects, a handler's clauses, lambda bodies, and methods that catch
an effect with `catch (Exception e)`.

The technique is "continuations from generalized stack inspection" (Pettyjohn, Clements, Marshall, Krishnamurthi,
Felleisen, ICFP 2005), with the exception-handler capture of Sekiguchi, Sakamoto and Yonezawa (2001). It uses
`java.lang.classfile` and has no dependency.

- **Types.** A first pass puts a branch target before each such call. The JDK's own stack-map generator then reports
  the types of the locals and of the operand stack there.
- **The operand stack.** If values lie under the call's arguments, the arguments and those values are stored in
  fresh locals and loaded back. That is the ANF step: at the call, the stack under the arguments is in locals.
- **Capture.** Each such call is covered by a handler of `Capture`, placed first in the exception table so that none
  of the method's own handlers (catch, finally) sees it. The handler saves the typed locals and the spilled stack:
  references in an `Object[]`, primitives as bits in a `long[]`, nothing boxed. It adds a `Frame` (the method, the
  call's index, the values) and rethrows; a resumed copy returns it to the boundary instead, which alone calls it.
- **Resume.** A private static copy of the method, `$okay$r$N(Frame, Object answer, Throwable thrown)`, restores a
  frame's values, puts back the stack under the call, and jumps to just after the call with the answer as its value.
  With `thrown` set, it jumps to the call instead and throws there, as if the callee had thrown. The copy's own calls
  are covered the same way. The original method only gains spills, exception-table entries and handlers at its end.
- **One frame at a time.** The boundary re-enters the innermost frame. That frame's return value, or what it threw,
  is the result of the call in the frame above. So the Java stack holds one restored frame at a time, and a capture
  inside a resumed frame saves only the new frames, sharing the rest as an immutable chain. A million operations in a
  loop, or a recursion 1000 deep resumed level by level, run at a constant Java depth (`DirectTest`).
- **`finally`.** If the rest is left unanswered (`k.unwind()`), the frames that have a handler at their call are
  re-entered with an internal `Unwound` thrown at the call. Their `finally` blocks run, innermost first, and nothing
  else of them runs.
- **Exceptions.** A Java exception thrown by a resumed frame reaches the `catch` of the frame above.
- **`new X(.., call(), ..)`.** A `new X; dup` before the arguments is moved after them, so nothing unconstructed is
  on the stack at the call. This means `X`'s class is initialized after the arguments.
- **`perform` and lambdas.** A transformed caller calls `Resume.perform`. A lambda whose body may suspend is linked
  through `Resume.lambda`, which remembers the interface method that enters it.
- **Twins.** Each static method rewritten gets a twin, `name$okay$t`, which transformed callers call instead. A
  capture leaves a twin by a return, not a throw: a throw costs the VM a search for the handler in every frame, the
  most of what a deeper suspension cost. After each call to a twin the caller reads one count, of captures on their
  way out on any thread, and only if it is not 0 asks whether one is its own. A call within a class goes to the twin
  directly; across classes through `Resume.twin`'s call site, which falls back to the method itself where its class
  has no twin. The method itself still throws, for any other caller, so an untransformed caller still loses no frame
  silently.
- **Tail position.** A call is marked when its method only returns its value (a cast, an unboxing, a jump, then a
  return). A handler's clause whose saved frames are all so adds nothing to its `k.resume`.
- **The mark.** A transformed class is marked `@Transformed`, so it is never rewritten twice.

**Refused at build time**, with the method, the line and what to change:

- a call that may suspend in a constructor or a static initializer;
- a call that may suspend in a `synchronized` method or block, because a monitor cannot be saved.

The agent prints these refusals on stderr and loads the class as it is.

**Refused at run time**, never a silent loss:

- `Direct.perform` called by an untransformed method. The message names the method and says how to fix the build.
- A saved frame whose call did not call the next saved frame, directly or through a lambda: a frame in between was
  not transformed. The message names both frames.
- A direct-style `k.resume` called outside its handler's clauses.
- A sensitive effect's class at a plain place of a row, passed as a raw `Class`.

One hole remains. An untransformed method with the same name and descriptor as the transformed method it calls is
not detected.

## Multi-shot: what is shared and what is copied

On each resumption the locals are copied back into a fresh Java frame, so locals are per path. The objects those
locals refer to are not copied, so they are shared between paths. `multiShotCopiesLocalsAndSharesTheObjectsTheyReferTo`
shows both: an `int` sum is per path, while one `ArrayList` grows along every path in turn. okay-direct documents the
same limit for Espresso. Handler state in place is per path, as in `okay.rows`.

## Measured

JMH (`./gradlew :okay-java-resumable:bench`). Two forks of 5×1 s, JDK 25, this machine, ns per operation; the
median of three runs, each beside a run of the module before these changes (`bench.d/2026-11-12_resumable-2.txt`;
the first measurements: `bench.d/2026-11-12_resumable.txt`):

| program | in place (1) | clause on the stack (2) | yield (2) | saved frames (3) | okay.rows | okay-direct (Loom) |
|---|---|---|---|---|---|---|
| state loop | 6.8 | | | | 15.9 | 5.2 |
| general handler resumed once, 1 frame deep | | 22.8 | 187 | 57.1 | 20.1 | 110 |
| the same, 10 frames deep | | 25.7 | 212 | 145 | | |
| choice, every path of 1000 | | | | 78.0 | 38.8 | |
| a handler in direct style: state in place / general / every path | 7.0 | | | 109 / 133 | | |

Before these changes: path 2 cost 226 at one frame and 277 at ten, path 3 249 and 858, the choice 95.9, the state
loop 8.9.

- **A call that never suspends** costs nothing measurable: 1.25 ns for a transformed method, 1.25 ns for the same
  method untransformed.
- **Path 2** costs about okay.rows' own operation when the clause resumes at once: nothing yields, at any depth. When
  the body yields, most of the cost is the JVM's freezing and thawing, which enter the VM; on this machine (macOS,
  arm64) the profile finds much of it switching the JIT's write protection (`pthread_jit_write_protect_np`).
- **Path 3** costs about 57 ns at one frame, plus about 10 ns for each further frame. A capture leaves the twins by
  returns; one that leaves a method by a throw costs the VM a search for its handler in every frame.
- **Where the time went before**, by a sampling profile: in path 2, a delimiter made for each operation and crossed by
  its capture, and the operation asked as a program, came to 737 bytes an operation besides the JVM's continuation;
  in path 3, a thrown capture's handler search in each frame, and boxing, came to 627 bytes at one frame and 87 more
  for each further frame.

## Limits

- Effects without type arguments.
- A sensitive effect is the first place of its row, and one only: `Direct.row(Direct.sensitive(Pay.class), ..)`.
- A `finally` block that performs while its frames are unwound is refused.
- A direct-style clause runs on saved frames (path 3): an effect it performs of a handler declared `Once` is not
  answered on its stack, as a body's would be. An operation of a direct-style general handler suspends the body.
- A direct-style handler's clause is recognized as resuming in tail position only by `return k.resume(..)` (or
  `yield` in a switch that is returned), not through a local.
- Java classes only.
- Locals are copied per path; the objects they refer to are shared.
- Constructors, static initializers and `synchronized` cannot suspend.
- Under path 2, the scoped-value bindings are switched with the stack, as okay-direct does it.
