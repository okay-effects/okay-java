# okay-java as an implementation of okay-kernel

okay-java implements okay-kernel's API (`dev.okay:okay-kernel-api`, 0.2) directly. There is no wrapper and no
translation layer: a kernel program is an `Eff`, and okay-java's machine runs kernel operations itself. The kernel's
protocol and semantics are okay-kernel's (`docs/design.md` there; its reference machine `Steps`, its TCK).

## The design

- **A dependency.** The library depends on `okay-kernel-api`: a jar of interfaces and a few records, with no
  dependency of its own. `Eff<L, A>` extends `okay.kernel.Prog<A>`, so every program of okay-java is a kernel
  program as it is.
- **`okay.core.Kernel.EFFECTS`** implements `okay.kernel.Effects`. `pure`, `flatMap`, `map` and `delay` are `Eff`'s
  own nodes; a bind's kernel function is adapted to okay-java's `Function` by one closure. A kernel handler, a
  `dollar`, `recover`, `ensuring` and `uncancelable` are each okay-java's `Handle` node: a delimiter with a fresh
  prompt. The prompt carries what the delimiter is for (its *frame*: a handler and its effect, a dollar, a recover…);
  okay-java's own handlers have none.
- **Operations found where they run.** A kernel operation is a node of its own. The machine walks the delimiters in
  force — its own stack, `ms` — to the nearest handler of the operation's effect, by `Type` identity. That stack is
  where the program runs, so the lookup is dynamic by construction: a `k` resumed under other handlers puts its
  delimiters in front of those, and an operation in it finds the handler in force there. A `shift0`'s body runs
  outside its delimiter and looks from there.
- **The four shapes.** `Answering`: the value in place, no capture. `General`: a capture to the handler's prompt,
  `k` okay-java's continuation (it is a kernel `Fn` itself: no allocation), the clause in the handler's place.
  `Aborting`: an abort unwinding to the handler. `Returning`: the return a value.
- **`shift0` and `dollar`.** `shift0` captures to the nearest delimiter whose frame is a handler's or a dollar's; the
  delimiters of failures and finalizers it crosses, and `k` keeps them. `dollar(body, k)` is `Handle(body, k)`.
- **Failures in the machine.** The run loop catches an exception of any step and unwinds: it walks the delimiters
  to the nearest one the exception concerns, drops the rest, and goes on in that delimiter's place. `recover` answers
  a failure; `ensuring` runs its finalizer and throws on; an aborting handler takes its own abort; the export's root
  makes it a `Failed` step. If nothing concerns it, the exception leaves `Eff.run` as before. `failed(e)` is a node
  that unwinds. Aborts and cancellations are unwound the same way, and no `recover` catches them.
- **Cancellation.** A handed-out `k` is resumed with a signal: a value, `k.fail`'s failure (thrown where the
  operation was) or `k.cancel`'s cancellation (finalizers only). Inside `uncancelable`, `cancel` answers the same
  `Yield` again, pending. Resumed, the rest of the region is captured and run under a delimiter that cancels when the
  region ends, with a value or a failure.
- **The protocol.** `computation` runs the program under a root delimiter whose frame holds the host's `Answers`.
  An operation with no handler in force is answered by the host in place if it can, else captured to the root: the
  step `Yield(op, k)`, `k` okay-java's continuation (multi-shot, from any thread; each call a run of it). `foreign`
  starts another's computation with the host answering in place what the nearest handler here answers in place, and
  performs its yields here under a delimiter of its own. A failure goes to its `k.fail`; an abort or a cancellation
  crossing it cancels it, and its finalizers' operations are performed here.

## What it costs code that does not use it

Nothing on the paths okay-java's own API takes:

- A `Prompt` has a field more (`frame`, null): 16 bytes as before, the object padded to 8 bytes anyway.
- A `Handle` node has a field more (`frame`, null): 24 bytes as before.
- The run loop is inside a `try`. HotSpot compiles an exception handler as a table entry; the loop itself is the same.
- A resumption's first frame, and a clause, are applied inside a `try` that puts the stack back in order if they
  throw, so a kernel delimiter inside `k` sees the failure. This is the same code with no exception.

Measured (2026-10-10, JDK 25 — okay-java's toolchain — JMH, 2 forks unless said; another agent's JMH and sbt runs on the same machine, so
each A/B run back to back, waiting for the JMH lock; CSVs in `bench.d/2026-10-10_kernel-native-*`), against the
library before it (`b20ca8f`):

| | before | with the kernel |
|---|---|---|
| `MixedBenchmark.orders`, an order | 179 ± 8 ns, 1352 B | 184 ± 1 ns, 185 ± 1 ns (a second run), 1352 B |
| `MixedBenchmark.ordersCell`, an order | 102 ± 0.4 ns, 1025 B | 103 ± 0.5 ns, 104 ± 0.9 ns, 1025 B |
| `MonadsBenchmark`, 31 benchmarks | | within ±2% each, allocation the same |
| its captures, 3 forks, two rounds interleaved | | within ±1.5% (one fork of `captureDeepRecord` +5%) |

A first `MonadsBenchmark` run had the captures 20–45% slower, while another agent's build ran; interleaved, with three
forks, they are the same.

## The kernel's own program, on okay-java

okay-kernel's `bench/` `MixedBenchmark` is okay-java's mixed program written once over the kernel's `Effects` (the
standard handlers, a throw and a choice handled per order). On `Kernel.EFFECTS` (2026-10-10, the same machine, all on
JDK 26 — okay-kernel's sbt runs on it; okay-java's own with `-PbenchJdk=26`):

| an order | okay-java, the kernel's program | okay-java's own program (`orders`) | steps-java | steps |
|---|---|---|---|---|
| time | 221 ns or 271 ns, by fork (246 ± 40) | 182 ± 26 ns | 399 ns | 522 ns |
| allocation | 1767 B | 1352 B | 2520 B | 2414 B |

The code written once costs about 1.2–1.5× okay-java's own: a closure a bind (a kernel `Fn` adapted to a `Function`),
a map a bind (so its failure is the program's), a handler's objects of the API's making (its `Program`, its handler,
its frame), and a lookup a walk. The two forks differ by JIT decisions, as `MixedBenchmark` has before (the README).

## Not yet

- **okay-java's std effects as the kernel's vocabulary.** `okay.std`'s effects are capabilities: their operations
  are captures to a prompt, not operation objects, and their handlers know no `Type` (Java erases `S` in
  `State<L, S>`). For an operation to cross the border as it is, a std handler would be given its `Type`
  (`State.state(type, s0, body)`) and answer the kernel's operations of it beside its own. That is an API addition to
  `okay.std`, not a change of the machine, and is left for its own step.
- **A handler lookup in constant time.** An operation walks the delimiters in force, one a step, as `Steps` does. An
  array of handlers by `Type.id()`, kept by the machine as it enters and leaves delimiters and as `k` is resumed,
  would make it O(1). It is not needed for correctness, and the walk is short in the programs measured.
