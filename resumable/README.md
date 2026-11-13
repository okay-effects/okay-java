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
2. **The handler is general and declares `okay.rows.Once`, or is written in direct style.** `perform` calls the
   handler's clause from the body's own stack (`Here.resumeHere`): the handler takes the rest, and if its clause
   resumes it at once (`k.resume(op, x)`, bound or mapped or not), the machine puts the rest back as it was and
   `perform` returns `x`. Nothing yields and nothing is frozen; most handlers that resume once resume so. A clause
   written in direct style is made there too, on the body's stack, and its answer taken at once when it resumes in
   tail position (`return k.resume(..)`): nothing of the clause is left that could resume the rest again, so this
   holds for a handler that may resume many times. If the clause does something first (asks an effect of its own
   that is not answered at once, defers, keeps `k`, resumes not in tail position):
   - a body running in a `jdk.internal.vm.Continuation` (the JVM's continuations exported) under a handler declared
     `Once` yields with the clause's program, and the JVM freezes and thaws its frames. Untransformed frames survive
     too. If a handler declared `Once` resumes twice, it is refused with a clear message; nothing is replayed. A
     handler that resumes more than once must not declare `Once`. If a yield is pinned, the frames are saved;
   - otherwise (a direct-style handler, a direct-style clause asking a `Once` handler outside it, no exports) the
     frames are saved as in path 3, and the clause's answer resumes them, as many times as it is given. If the rest
     is unwound instead, their `finally` blocks run first.

   A direct-style handler whose clauses resume otherwise more often than in tail position (a choice, a log written
   after the rest) is no longer asked so: its operations take path 3 at once, as they would gain nothing here.
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

**Any place, more than one.** A sensitive place may stand anywhere in a row, and a row may have several:

```java
// Eff<Log, Sensitive<Pay, ?>>: the log handled first, then the payments under their audit
var p = Direct.row(Log.class, Direct.sensitive(Pay.class)).of(SensitiveTest::logThenPay);
run(Pay.bank(100, Audit.audit(payAudit, Log.run(p))));

// Sensitive<Pay, ? extends Eff<Log, Sensitive<Mail, ?>>>: each sensitive place under its own audit
var q = Direct.row(Direct.sensitive(Pay.class), Log.class, Direct.sensitive(Mail.class)).of(..);
run(Mail.outbox(sent, Audit.audit(mailAudit, Log.run(Pay.bank(100, Audit.audit(payAudit, q))))));
```

Each sensitive place is `Sensitive<S, ..>` in the row's type, taken by its audit once the places before it are
handled, the row open after it; its operations are never answered in place, at any place (`SensitiveTest`). The
overloads of `Direct.row` cover every mix of plain and sensitive places up to four.

What javac refuses (`src/test/negative/SensitiveDirect.java`, `SensitivePlaces.java`, `SensitiveUndeclared.java`):

- a sensitive effect at a plain place, any place: `Direct.row(Pay.class)`, `Direct.row(Log.class, Pay.class)`;
- a plain effect as a sensitive one: `Direct.sensitive(Note.class)`;
- a sensitive operation of a plain effect: `Direct.perform` takes a `Sensitive.Op` of a `SensitiveSuspend` only;
- a method that performs `Pay`, or calls one that does, without declaring it;
- the program run (`run`), handled (`handle`, `Audit.handle` without the audit), moved (`under`) or retyped to a plain
  place (`fit`) anywhere but under its audit — the rules of `okay.rows`' `Sensitive`; at a later place, the same
  once the places before it are handled; with two, each needs its own audit.

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
- **Asked from the body's stack.** A direct-style handler is asked as a handler declared `Once` is (path 2): its
  clause is made on the body's stack, and when it resumes in tail position its answer is the operation's, the body
  going on where it is. A state written generally costs about what the machine's own operation does, not a
  suspension of the body (measured below). The clause's own operations of a handler declared `Once` outside it are
  asked from the clause's stack the same way. A clause does not run on a continuation of its own: one made for each
  clause, run inside the body's, measured 20 times slower than its saved frames (8.2 µs against 0.4 µs an operation,
  `bench.d/2026-11-13_resumable-3.txt`).
- **Tail position.** The transformation marks a call whose value is only returned: followed on the stack or into one
  local — a cast, an unboxing or a boxing, a jump, a store to a local and that local's load, then a return. So
  `return k.resume(..)`, `var r = k.resume(..); return r;`, a ternary's arms, a switch's value kept in a local and
  returned all are; a local read for anything else first, a test, any other instruction is not (`TailTest`). When
  every frame saved at `k.resume` is so, the clause's program is the resumption itself, and nothing waits for it:
  nothing is kept for each operation. A general handler that resumes in tail position (the state above, a stream's
  consumer) runs a million operations at a constant Java stack, in each of these shapes
  (`tailPositionThroughALocalACastATernaryASwitchsLocal`). A clause that does something after `k.resume` holds its
  frames until the rest has run, as any deep handler holds its stack.
- **`k` is its clause's.** It is valid in its own clause, while that clause runs (resumed by a choice outside, as
  many times as it is), on the thread that runs it. Used anywhere else it is refused at run time, saying what was
  done: by a program's body, by a later clause of its handler (a `k` kept in a field), by a clause of another
  handler, on another thread, after its program ended (`MisuseTest`). No program can make one.

## A handler around a part of a body

A body installs a handler around a part of itself with `Direct.row(..).embed(sub)`: `sub` is an `okay.rows`
program, its handlers inside it; it runs as a part of the program that calls `embed` — not a nested run: its
operations that leave it go to the program's handlers, and the body goes on with its value.

```java
static List<Integer> scoped() throws Counter, Log {
  Counter.put(10);
  Final<Integer, Integer> local = Direct.row(Log.class).embed(Counter.run(100,
      Direct.row(Counter.class, Log.class).of(() -> {
        Log.tell("inside, the local counter at " + Counter.get());   // 100; the log is the program's
        Counter.put(Counter.get() + 1);
        return Counter.get() * 2;
      })));
  return List.of(local.state(), local.value(), Counter.get());       // [101, 202, 10]
}
```

- **How it runs.** The body's frames are saved at `embed`, as at a `k.resume` (path 3), the sub-program runs in
  their place on the machine, and they are resumed with its value — once for each value a choice outside gives,
  re-entered, not replayed. In tail position (`return Direct.row(..).embed(..)`) nothing waits: the body's program
  is the sub-program. Overloads for rows of one to four plain effects.
- **The row.** The row named at `embed` is the sub-program's after its handlers: the program's places, or some of
  them in the same order. A place is put for each of the program's other places (`Here.lifted`), so each operation
  goes to its own effect's place: `Direct.row(Input.class).embed(..)` called by a program of `Log, Counter, Input`.
  Effects out of order, or not the program's, are refused at run time, naming both rows; `embed` where no program
  runs is refused too (`EmbedTest`).
- **A handler around the rest at every step.** 100 000 steps, each a counter of its own around the rest of the
  steps, the program's own effect asked through all of them at the end: no stack overflow (the Java stack holds one
  restored frame at a time), about 0.25 s and 95 MB of heap, against 7 ms for the same steps under one counter
  outside them (`aHandlerReinstalledAroundTheRestAtEveryStep`). What it holds is a deep handler's: each step's
  handler and the frame waiting for its value, until the rest has run.

**A nested run is not this.** A body that runs a program itself, by a top-level runner (`Effects.run`, `step`, a std
effect's runner), runs a separate machine: it sees only the handlers inside that run, none of the program's. A
nested run whose program has all its handlers inside it is an ordinary function of the body: its captures, on
every path, stay inside it, the runner's frames under them, each of three ways inside each of three outside
(`aNestedRunWithItsHandlersInsideItIsAFunctionOfTheBody`). One whose program asks an effect only the body's program
handles is refused, with the operation and what to do instead, whichever way the outer handler answers — from the
body's stack, after a yield, on saved frames — and the body's `finally` blocks run; no frame is lost
silently (`aNestedRunOfTheProgramsEffectIsRefusedOnEveryTier`). The `nested-run` preset refuses such calls at build
time.

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
- **Tail position.** A call is marked when its value is only returned (a cast, an unboxing or a boxing, a jump, a
  local stored and loaded, then a return). A handler's clause whose saved frames are all so adds nothing to its
  `k.resume`.
- **The guard** (off by default): see Misuse.
- **The mark.** A transformed class is marked `@Transformed`, so it is never rewritten twice.

**Refused at build time**, with the method, the line and what to change:

- a call that may suspend in a constructor or a static initializer;
- a call that may suspend in a `synchronized` method or block, because a monitor cannot be saved.

The agent prints these refusals on stderr and loads the class as it is.

**Refused at run time**, never a silent loss:

- `Direct.perform` called by an untransformed method. The message names the method and says how to fix the build.
- A saved frame whose call did not call the next saved frame, directly or through a lambda: a frame in between was
  not transformed. The message names both frames.
- A direct-style `k.resume` or `k.unwind` called anywhere but in its own clause while that clause runs.
- A sensitive effect's class at a plain place of a row, passed as a raw `Class`.
- An operation that reached no handler in a program a body ran by a top-level runner (a nested run); a
  `Direct.row(..).embed` whose effects are not the program's in order, or called where no program runs.

One hole remains. An untransformed method with the same name and descriptor as the transformed method it calls is
not detected.

## Misuse: a program that reaches a handler, or the world, directly

A program is meant to reach the world by performing effects, so that its handlers, interceptors and audits see
every operation. Java does not make it: any code can call any object it can reach, and a static field is reachable
from anywhere. What happens when a program goes around its effects (`MisuseTest`), and what guards it:

- **It calls a handler's clause itself** (`handler.on(op, k)`, the handler in a static field). A program cannot make
  a `Direct.K` (its constructor is the boundary's), so it can only pass `null` — the clause fails on it — or a `k`
  it found. **A `k` used outside its own clause is refused at run time**, saying how: by a program's body, by a
  later clause of its handler (a `k` kept in a field), by a clause of another handler, on another thread, after its
  program ended. Multi-shot from a live clause stays allowed: a choice outside that resumes the clause twice
  resumes the same `k` in each path. Before this check, a `k` kept from an earlier clause was resumed again,
  silently, by a later one; and one resumed by a body built from the same `Direct.row(..)` object as its
  interceptor re-entered the body without end, until the heap ran out. An `okay.rows` handler's `k` (an `okay.rows.K`)
  has no such check: it is a value, resumable from anywhere, as `okay.rows` defines it.
- **It changes a handler's state itself** (`handler.s = 42`). Nothing stops it at run time: no operation is
  performed, so nothing on the way sees it — an interceptor, an observer, an audit — and a handler's fields are
  shared by every path of a choice outside, so each path sees the others' changes. (A handler in place keeps its
  state in its cell, per path; a cell kept in a field can be changed the same way.)
- **It calls the effect's real implementation itself** (the bank behind the payments, reached statically). The
  audit never sees it: its trail shows the transfers performed, and the money moved by the direct call is not on it.

**At build time, the guard** (off by default) looks at every method the transformation rewrites — effectful code —
for such calls: a handler's clause called (`on`, `ret`, `answer`, `mine` of `Direct.Handler0..4`, `Direct.InPlace`,
okay.rows' `Handler`, `InPlace`, `Interceptor`, `Observer`), and any call to, or field of, a class that implements
one of them or is marked `@okay.resumable.Implementation` (mark the bank so), other than to construct it — to
install it. `warn` says each, by method and line; `error` refuses the build, as a method that cannot suspend is
refused (`GuardTest`, over the module's own tests: only `MisuseTest`'s programs are found).

**Forbidden APIs.** The same check takes a list of APIs effectful code may not use: a package and those under it
(`java.net.*`), a class (`java.lang.ProcessBuilder`, or the bank's client), a member (`java.lang.System.exit`,
`java.lang.Runtime.exec`). `strict` is a preset: the network, files (`java.nio.file.*`, `java.io.File*`), databases
(`java.sql.*`), processes, reflection (`java.lang.reflect.*`, `MethodHandles`, `VarHandle`, `Class.forName`,
`sun.misc.Unsafe`, `jdk.internal.*`), the JVM's exit and native loading, threads. Each use is refused with the
method, the line and the entry it matched; said instead, if the guard is `warn`. Methods that perform nothing are
not looked at: a handler and the clients it calls may use anything.

**Presets named after the effects of `okay.rows.std`**, each forbidding the ambient way of doing what the effect
does, and naming the effect to use instead in its message (`... calls nanoTime of java.lang.System, forbidden here by
java.lang.System.nanoTime — use okay.rows.std.Clock instead`):

- `state`: writing a static field that is not final (a read of one is said, not refused) — use `State`, or a
  handler in place;
- `reader`: `System.getenv`, `System.getProperty`/`getProperties`/`setProperty`, `Integer.getInteger` and the
  like — use `Reader`;
- `writer`, `trail`: `System.out`, `System.err`, `System.getLogger`, `printStackTrace`, `java.util.logging`, SLF4J,
  Log4j — use `Writer` or `Trail`;
- `clock`: `System.currentTimeMillis`, `nanoTime`, `Instant.now` and the other `now`s, `Clock.system*` — use
  `Clock`;
- `fresh`: `UUID.randomUUID`, `Random`, `SplittableRandom`, `ThreadLocalRandom`, `SecureRandom`, `Math.random` —
  use `Fresh`;
- `async`: `Thread`, `ExecutorService`, `Executors`, `ForkJoinPool`, `CompletableFuture`, `Timer`, `Object.wait`,
  `notify` — use `Async`;
- `io`: `java.io`'s file streams, `java.nio.file`, `.channels`, `java.net`, `java.sql` — an effect of your own;
- `nested-run`: running a program to its end — `Effects.run`, `Effects.step`, `Async.runBlocking`/`toFuture`/
  `start`, `Emit.pull`, okay.core's `run`, okay-direct's `Effects` — use `Direct.row(..).embed(..)` (below);
- `pure`: all of them.

A class or a member is matched up the owner's classes and interfaces: a subclass of `Thread` is a `Thread`,
`ThreadLocalRandom` is a `Random`, a `wait` on any object is `Object.wait` (`GuardTest`, positive and negative for
each preset: a method that installs a handler, a clean one, one that performs nothing are not refused). Installing a
handler (`handle`, `intercept`, `Counter.run(..)`) stays allowed under `nested-run`. A monitor (`synchronized`) needs
no preset: a method that may suspend is refused one by the transformation, as a monitor cannot be saved. Each
instruction is looked at alone, in the method that performs: a method that performs nothing, called by one that
does, is not followed. A check over the call graph would take the same entries; it is not built.


```kotlin
okayResumable { guard = "error"; forbid = "strict, pure, com.acme.bank.Client" }   // Gradle
```
```xml
<configuration><guard>error</guard><forbid>strict, pure, com.acme.bank.Client</forbid></configuration>  <!-- Maven -->
```

(`-Dokay.resumable.guard` and `-Dokay.resumable.forbid` for the agent of `java -jar`.) The samples build so.

**The strongest prevention is the module system.** Put the effect, its operations and the way to install its
handler in an exported package, and the handler and the real client in one that is neither exported nor opened to
the modules of programs (`src/test/jpms`):

```java
module payments {
  requires transitive okay.java;
  requires transitive okay.java.resumable;
  exports payments;            // Pay, its operations, Pay.banked(..) that installs the bank's handler
}                              // payments.bank — Bank, Banking — not exported, not opened
module shop { requires payments; }
```

A program in `shop` that names `payments.bank.Bank` does not compile ("package payments.bank is not visible"), and
reflection from outside cannot reach it either: no call, no field, no `setAccessible`, no private lookup
(`JpmsTest`).

**What Java cannot prevent.** Java has ambient authority: any code may call any static method, read any static
field, open a socket or a file, start a thread, unless something outside the code forbids it. The run-time check
covers what this library hands out (`k`); the build-time guard is a check of the bytecode of effectful methods, not a
proof: a method that performs nothing may do the forbidden thing for it, as may a lambda run by a JDK method, an
object handed to the program as a plain interface, reflection by a name built at run time, or a class built without
the plugin. A program can also simply be given a reference to the bank. Only the module system draws a line javac
and the JVM both hold — and then only around what is not exported or opened, and not for code on the class path,
where everything is one unnamed module. The Security Manager, which once could, is gone from the JDK.

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
- Every operation is an object of a Throwable subclass: about 40 bytes (47 a state loop's operation, its boxed
  answers included; okay-direct's records 23). C2 never inlines a method of a Throwable subclass into a caller that
  is no Throwable ("exception method": the effect's own `Counter.get()`, the operation's constructor), and
  `Throwable`'s constructor is too large for its inlining limits, so the operation always escapes and escape analysis
  cannot remove it — not with larger limits (`-XX:MaxInlineSize=200`), not with the operation made in the program
  itself (`stateDirectInPlacePerformedHere`), not with okay.rows' cell keeping no reference to it (measured,
  `bench.d/2026-11-13_resumable-3.txt`). Only an operation that is no Throwable would avoid it, and that is the
  `throws` checking the API is built on. A transformation that shared one object for each operation without fields
  would save a part of it, at the cost of every perform of `new Get()` being the same object; it is not done.
- A `finally` block that performs while its frames are unwound is refused.
- A direct-style clause runs on saved frames, never on a continuation of its own (measured 20 times slower): an
  effect of a handler declared `Once` it performs is answered on its stack when that handler resumes at once, and
  its frames are saved when it does not.
- A yield on the JVM's continuation is mostly the JVM's freezing and thawing; on this machine about half of it is
  `pthread_jit_write_protect_np`. Beside okay-direct's lane, ours also runs the okay.rows machine for a clause that
  defers, which okay-direct's handler does not do.
- The guards: see Misuse — what Java cannot prevent there.
- Java classes only.
- Locals are copied per path; the objects they refer to are shared.
- Constructors, static initializers and `synchronized` cannot suspend.
- Under path 2, the scoped-value bindings are switched with the stack, as okay-direct does it.
