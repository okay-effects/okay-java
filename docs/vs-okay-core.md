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

Measured on the same JDK (26), the same machine and the same harness (`MonadsBenchmark`, the `bench.d` CSVs), in ns
an operation unless noted:

| | okay-java | okay-core `zero` |
|---|---|---|
| answered across 1 / 16 handlers | 4.4 / 4.5 | 5.7 / 17.1 |
| a general clause | 7.8 | 7.7 |
| state (a cell) / writer | 15.7 / 17.3 | 14.2 / 18.3 |
| throws / choose | 13.3 / 25.8 | 14.9 / 33.9 |
| tail calls by delay | 2.0 | 2.3 |
| a shift under a reset, 1 map / 16 maps, `k` 8 times (a run) | 130 / 519 ns; 339 ns over a record | 126 / 323 ns |
| a deep shift, 256 maps boxed by a record or a constructor (a run) | 4.95 µs | 5.15 µs |
| a deep shift, 256 maps autoboxed (a run) | 6.7–8.7 µs | 4.7 µs |

The last row is `Integer.valueOf`: escape analysis's box elimination works in `zero`'s loop and not in ours (see
the README).
