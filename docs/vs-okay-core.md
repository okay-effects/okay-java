# okay-java and okay-core

okay-core is Scala 3 and okay-java is Java 25. Both are built on `shift0` and `$` (okay-core's `Cont0`, okay-java's
`Eff`), and they make different choices where the languages differ.

| | okay-core | okay-java |
|---|---|---|
| A program | `c.Prog[A]` in a context `c`, written once over an interface, run on `Cont` or `Cont0` | `Eff<L, A>`, one monad, the machine on prompts |
| The effects a program needs | a row in the type, `Reader % Int :? c.Effs`, found by the compiler | capabilities as parameters, `State<L, S> st` |
| An operation finds its handler | by its path in the row (`In`): Here, There, … | by its prompt, the handler's identity |
| An effect inside a handler of the same effect | the nearest of its type | the one its capability came from: two `State<Integer>` at once |
| A capability out of its handler | no capability to take out: a path is not a value | a compile error, the body at a fresh level (rank-2) |
| A tail-resumptive handler | `Answering`, found by the machine's evidence, a step per handler crossed | the capability itself, no prompt: the same cost across any handlers |
| Answer-type modification | in the interface (`PState`, a shift that moves the answer) | in `Cont0` (`PState0`, printf); `Eff`'s answers erased into prompts, a type that changes in a typestate (`PState`) |
| `shift` under a handler | `k` with the handler (Danvy–Filinski), as `Cont0`'s machine | the same; a shift goes to its prompt, any delimiter, not the nearest |
| A handler's body | a context function, `(using c) ?=> …` | an anonymous class with a generic method: Java has no generic lambda |
| References | `Ref` (the kernel alone) and `Cps0` (textbook CPS), every suite on four | `Ref` and `Ref0` (textbook readings of the nodes), every suite on two, and 1 000 random programs |

## Speed

Measured on the same JDK (26) and the same machine, JMH: okay-java's machine a step a node
(`bench.d/monads-steps-jdk26.csv`, `bench.d/mixed-jdk26.csv`, 2026-10-09 and 10, a load about 4) and okay-core's
`zero` (`bb01100`, 2026-10-09; the mixed program on its branch `mixed-bench`). In ns an operation unless noted:

| | okay-java | okay-core `zero` |
|---|---|---|
| **a program of five effects, a throw and a choice handled per order (an order)** | **102** | **136** |
| the same with no type profiles (`-XX:-UseTypeProfile`) | 153 | 141 |
| answered across 1 / 16 handlers | 5.1 / 5.3 | 5.7 / 17.1 |
| a general clause | 9.2 | 7.7 |
| state (a cell) / writer | 14.0 / 18.8 | 14.2 / 18.3 |
| throws / choose | 14.2 / 28.5 | 14.9 / 33.9 |
| tail calls by delay | 2.2 | 2.3 |
| a shift under a reset, 1 map / 16 maps, `k` 8 times (a run) | 82 / 347 ns; 329 ns over a record | 126 / 323 ns |
| a deep shift, 256 maps boxed by a record or a constructor (a run) | 4.7 µs | 5.15 µs |
| a deep shift, 256 maps autoboxed (a run) | 4.9 µs | 4.7 µs |

The mixed program is the one to go by: each microbenchmark shows one function at each call site, which C2 inlines,
and a program does not. With no type profiles, where nothing is inlined by luck, `zero` is ahead: its hybrid runs a
delimiter's body in a Java frame up to 32 deep, where this machine makes a virtual call a node.
