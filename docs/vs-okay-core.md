# okay-java and okay-core

okay-core is Scala 3 and okay-java is Java 25. Both are built on `shift0` and `$` — okay-core's one monad
`Cont[R, I, O, A]` (its kernel, `okay.one`), okay-java's `Eff<L, A>` — and both run a program by a machine over an
explicit stack of delimiters, `k` data, in constant stack. They make different choices where the languages differ,
and they meet at okay-kernel: each implements the protocol natively (okay-core's `OnCore`, okay-java's `Kernel`),
and a program of either runs under the other's handlers through it.

| | okay-core | okay-java |
|---|---|---|
| A program | `A ! E1 + E2`: a row of effects in the type, the facade over `Cont[R, I, O, A]` | `Eff<L, A>`: a level in the type, the handlers in force where it runs |
| The effects a program needs | the row, found by the compiler (`In`, a path to the effect) | capabilities as parameters, `State<L, S> st` |
| An operation finds its handler | by its path in the row: `Here`, `There`, …; a `Deep` handler relocates the paths crossing it (`Removed`, `Again`) | by its prompt, the handler's identity; the handlers crossed are not looked at |
| An effect inside a handler of the same effect | the nearest of its type, by path | the one its capability came from: two `State<Integer>` at once |
| A capability out of its handler | no capability to take out: a path is not a value | a compile error, the body at a fresh level (rank-2) |
| A tail-resumptive handler | `Answering`, the machine answers in place — a step per handler crossed | the capability itself, no prompt: the same cost across any handlers |
| Answer-type modification | `Atm`, the answer moved with no row named; `PState` (the kernel's, lowered) | in `Cont` (`ContPState`, printf); `Eff`'s answers erased into prompts, a type that changes in a typestate (`PState`) |
| `shift` under a handler | `Shift % S`, control as an effect of the row: `shift0`'s body outside the `reset`, `shift`'s under it | `p.shift0`, `p.shift`: to the prompt, any delimiter, not the nearest |
| Interception | `Intercept.at`/`by`: a handler of an effect handled further out, over `Removed.again` | a capability wrapped: forwarding is a plain program (no row to relocate) |
| Failures | `Guard` in the kernel: `ensuring`, `recover`, `uncancelable`; a `Raise` of the machine | `Eff.recover`, `Eff.ensuring`, `Eff.uncancelable`, `Eff.failed`: the machine unwinds a throw of any function of the program's to the nearest guard |
| A handler's body | a context function, `(using c) ?=> …` | an anonymous class with a generic method: Java has no generic lambda |
| The machine | one `eval` over `Meta`, the frames and delimiters data; JIT budget watched (the method's bytecode size) | a step a node, each kind's step a method of its own; the frames `Then`/`Mapped`, the delimiters `Seg`/`Take` |
| okay-kernel | `OnCore`: every delimiter of the bridge a `Deep` handler of one request effect, the lookup by key a walk over them | `Kernel`: the delimiters' prompts carry a frame, the lookup by key a walk over the machine's own stack |
| References | `TestReference`, the meaning in CPS; every suite on the machine and on it | `Ref` and `ContRef`, textbook readings of the nodes; every suite on two, and 1 000 random programs (`AgreeTest`) |

## Speed

The comparison that holds is okay-bench's (`../okay-bench`): the same mixed program — five effects, a throw and a
choice handled per order, 1 000 orders — written once on each implementation's own API, and once over okay-kernel's
`Effects` run by each implementation; JMH, 2 forks, `-prof gc`, one run after another on one machine, one table
(`results/table.md` there). Its lanes for these two: `java` (okay-java), `one` (okay-core's kernel alone, its std),
`core` (okay-core's facade), `kernel` (the protocol's program on each).

The latest run (2026-10-14, JDK 25, okay-java at this commit, okay-core at its master of the day), µs per 1 000
orders:

| program | okay-java | okay-core |
|---|---|---|
| its own, the total a cell | 103 | `one` 169, `core` 223 |
| its own, the total a functional state | 183 (190 ± 33 in that run, 172 ± 1 the day before: the forks differ) | `core` 223 |
| the protocol's | 237–253 | `core` 440 |

(The numbers are okay-bench's `results/table.md` of the run; see the table there for the error bars and allocation,
and the reference machines beside — `steps-java` 426, `steps` 535. okay-java's own `MixedBenchmark`, 2 forks, gave
183 / 103 at this commit and 185 / 106 at the commit before the machine's guards: the `try` costs nothing.)

What the numbers say:

- **A step a node against one `eval`.** okay-java's loop is a virtual call a node, each kind's step compiled apart;
  okay-core's one `eval` is one method the JIT has to fit in its budget. On the mixed program the small steps win.
- **A handler by prompt against a path.** An operation crossing handlers costs okay-java nothing a handler crossed; in
  okay-core a `Deep` handler crossed relocates the path (`Removed`), a step each.
- **The protocol's program costs each about 1.2–2× its own.** A closure a bind (a kernel `Fn` adapted), a handler's
  objects of the API's making, and a lookup by key that walks the delimiters: okay-java walks its own stack, okay-core
  the `Deep` handlers its bridge is made of — the larger gap, and the one open on okay-core's side (its `docs/kernel.md`).

Earlier comparisons, microbenchmark by microbenchmark against okay-core's machines of the time (`zero`, `cont`), are
in `bench.d/` with their dates; the machines they measured are gone, okay-core being on the one monad since
2026-10-13.
