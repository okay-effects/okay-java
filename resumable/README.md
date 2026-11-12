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
operation goes. Then:

1. **The handler is in place** (`InPlace`: state, reader, a log, an in-place interceptor). The operation is answered
   at once, on the body's stack, by the same handler, cell and walk that an `okay.rows` operation would get. Nothing
   is thrown or captured. If anything that must see the operation stands nearer, the operation does not take this
   path: a general interceptor, an observer, an audit, or an unwinding's mark.
2. **The handler is general and declares `okay.rows.Once`, and the JVM's continuations are exported.** The body
   runs in a `jdk.internal.vm.Continuation`, `perform` yields, and the JVM freezes and thaws the frames. Untransformed
   frames survive too. If a handler declared `Once` resumes twice, it is refused with a clear message; nothing is
   replayed. A handler that resumes more than once must not declare `Once`. If a yield is pinned, the operation falls
   back to path 3.
3. **Any other handler** (general, possibly multi-shot). The frames are saved as data and re-entered.

## The transformation

A method is rewritten when it has a call that may suspend. That is a call to a method whose `throws` names a
subclass of `Suspend`, resolved up the callee's classes and interfaces. This covers the methods that declare
effects, lambda bodies, and methods that catch an effect with `catch (Exception e)`.

The technique is "continuations from generalized stack inspection" (Pettyjohn, Clements, Marshall, Krishnamurthi,
Felleisen, ICFP 2005), with the exception-handler capture of Sekiguchi, Sakamoto and Yonezawa (2001). It uses
`java.lang.classfile` and has no dependency.

- **Types.** A first pass puts a branch target before each such call. The JDK's own stack-map generator then reports
  the types of the locals and of the operand stack there.
- **The operand stack.** If values lie under the call's arguments, the arguments and those values are stored in
  fresh locals and loaded back. That is the ANF step: at the call, the stack under the arguments is in locals.
- **Capture.** Each such call is covered by a handler of `Capture`, placed first in the exception table so that none
  of the method's own handlers (catch, finally) sees it. The handler boxes the typed locals and the spilled stack into
  an `Object[]`. It adds a `Frame` (the method, the call's index, the values) and rethrows.
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
- **The mark.** A transformed class is marked `@Transformed`, so it is never rewritten twice.

**Refused at build time**, with the method, the line and what to change:

- a call that may suspend in a constructor or a static initializer;
- a call that may suspend in a `synchronized` method or block, because a monitor cannot be saved.

The agent prints these refusals on stderr and loads the class as it is.

**Refused at run time**, never a silent loss:

- `Direct.perform` called by an untransformed method. The message names the method and says how to fix the build.
- A saved frame whose call did not call the next saved frame, directly or through a lambda: a frame in between was
  not transformed. The message names both frames.

One hole remains. An untransformed method with the same name and descriptor as the transformed method it calls is
not detected.

## Multi-shot: what is shared and what is copied

On each resumption the locals are copied back into a fresh Java frame, so locals are per path. The objects those
locals refer to are not copied, so they are shared between paths. `multiShotCopiesLocalsAndSharesTheObjectsTheyReferTo`
shows both: an `int` sum is per path, while one `ArrayList` grows along every path in turn. okay-direct documents the
same limit for Espresso. Handler state in place is per path, as in `okay.rows`.

## Measured

JMH (`./gradlew :okay-java-resumable:bench`). Two forks of 5×1 s, JDK 25, this machine, ns per operation
(`bench.d/2026-11-12_resumable.txt`):

| program | in place (1) | continuation (2) | saved frames (3) | okay.rows | okay-direct (Loom) |
|---|---|---|---|---|---|
| state loop | 10.7 | | | 23.9 | 7.3 |
| general handler resumed once, 1 frame deep | | 281 | 271 | 30.5 | 145 |
| the same, 10 frames deep | | 321 | 947 | | |
| choice, every path of 1000 | | | 111 | 54.8 | |

- **A call that never suspends** costs nothing measurable: 1.51 ns for a transformed method against 1.53 ns for the
  same method untransformed.
- **A suspension with saved frames** costs about 270 ns at one frame, plus about 75 ns for each further frame
  (capture and resume together).
- **Path 2 against path 3.** One frame deep, the continuation does not beat saved frames (281 against 271). Ten
  frames deep it is three times faster (321 against 947). Both cost about twice okay-direct's Loom, because each
  suspension goes through `okay.rows`' machinery: the ask as a program, the probe, and the resumption's node.

## Limits

- Effects without type arguments.
- No sensitive (audited) operations in direct style: `Direct.perform` takes an `Op`, not a `Sensitive.Op`.
- A `finally` block that performs while its frames are unwound is refused.
- Handlers are written in `okay.rows`, not in direct style.
- Java classes only.
- Locals are copied per path; the objects they refer to are shared.
- Constructors, static initializers and `synchronized` cannot suspend.
- Under path 2, the scoped-value bindings are switched with the stack, as okay-direct does it.
