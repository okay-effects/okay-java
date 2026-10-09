# Architecture

okay-java has three layers:
- the monad of delimited continuations, `Cont0`, with `shift0`/`$` (the first port's `Cont`, `shift`/`reset`, is
  gone; `Eff`'s `p.shift` is Danvy–Filinski's `shift`, and `Cont0Test` compares the two with it);
- `Eff`, effects at a level, run by a machine with prompts;
- `okay.std`, the effects written on `Eff`.

## `Cont0` (`Cont0.java`, `Loop0.java`)

`Cont0<I, O, A>` is Materzok–Biernacki's `shift0` and `$`, the levels typed in the answers. A `shift0`'s body runs
outside the delimiter, so its answers are programs of the level outside, `Cont0` again. `reset0`, `lift` and
`value` are built on the two. Each delimiter runs its body in a loop of its own, to where it leaves (`Loop0`). It is
open: usable as a monad with raw `shift0` and `$`, as in `Cont0Test`, the printf, and `PState0`.

## `Eff` (`Eff.java`, `Prompt.java`, `Nodes.java`, `Machine.java`)

`Eff<L, A>` keeps `Cont0`'s semantics and moves the answer types into the delimiters' prompts. The level `L` stays
in the type as a phantom.

- **Two primitives.**
  - `Eff.handle(body, ret)` is `ret $ body` under a prompt made fresh each time the handler runs. Its body is an
    `Eff.Body`, a generic method `<M extends L> Eff<M, A> in(Prompt<L, M, R> p)`, at a level as fresh as its prompt.
  - `p.shift0(clause)` is an operation at `M`: the rest up to `p`'s delimiter, the delimiter included, its clause
    run at `L`.
  - `p.shift` and `p.reset` are built on them, and `Eff.reset` is a handler that answers nothing.
- **Nodes** (`Nodes`).
  - `Return`, `Bind`, `Map`, `Delay` and `Sync` are a value, a bind, a map, a tail call and a value computed in
    place.
  - `Shift0` is a capture. `Suspend` is a capture already bound.
  - `Handle` and `Reset` are delimiters.
  - `Push` and `Machine.Resumption` exist only inside the machine: a program with its rest as a stack, and a
    resumption.
- **The machine** (`Machine`). One loop over a program, the frames of the innermost segment, and an explicit stack
  of delimiters on the heap: each delimiter with its prompt, its return and the frames outside it.
  - **No Java frame per delimiter.** Handlers nest as deep as the heap allows: 100 000 nested, an operation across
    them all, at `-Xss512k`.
  - **Entering a handler** pushes a delimiter. A value that reaches the end of a segment goes to the delimiter's
    return, outside it. A reset's return is the value itself, which goes on at once.
  - **A capture** walks the delimiters to its prompt, with no allocation per delimiter crossed. Its clause runs in
    the delimiter's place. The continuation `Kont` keeps the frames, the delimiters crossed (the first `n` of the
    stack) and the delimiter itself.
  - **A resumption** puts the crossed delimiters back in front of the current ones by one shared `Take` ("the first
    `n` of that stack, then this"), which is never copied. It puts the delimiter back with the frames of the place
    it was resumed from. A clause's `k(v)` resumed where it was captured keeps the very delimiter.
- **Fast for the JIT.** One loop runs every program, so what C2 makes of that loop is most of the speed. What
  counted, each measured one JVM a benchmark against the code before:
  - **Dispatch by `instanceof`, not a pattern `switch`.** The nodes are final records, so each test is one compare,
    the most run first. A `switch` on the sealed type goes through a `typeSwitch` bootstrap and an index. State,
    functional: 57 to 34 ns.
  - **A resumption's frames at call sites of their own.** Its first frame (the operation's own `map`) is applied
    in place, the deeper ones in `feed`. Each call site's profile then holds one or two functions, which C2
    inlines. Through the loop's one call site they were all mixed, and none was inlined. A capture with 16 maps:
    720 to 450 ns.
  - **No delimiter made till a bind needs it.** A resumed rest that is only maps ends in the delimiter's return,
    applied right there, with the frames and delimiters of the place it was resumed from. Choose: 292 to 260 B.
  - **A resumption does not keep the continuation.** When it crossed no delimiter (the usual), the node holds that
    delimiter, not the `Kont`. A clause that applies `k` once then leaves the `Kont` to escape analysis, and it is
    never made. Writer: 285 to 261 B, 22 to 19.5 ns.
  - **No loop on the hot path that does not run.** Putting the delimiters back is straight code when none was
    crossed. The `take` for the others is a call on a cold branch. A loop compiled into the big one, even one that
    never runs, costs the hot path registers. Queens: 162 to 137 µs.
  - **Where C2 still loses.** A deep chain of maps that allocate (`captureRecord`, 16 maps over a record) runs
    slower than in a loop of its own. Inlined into the big loop, the loop's values live on the stack, six loads
    and stores a map. And the same benchmark can come out 30–80% apart from a change to an unrelated branch, by
    what C2 inlines. With type profiles off (`-XX:-UseTypeProfile`), as in a program whose call sites see many
    functions, this machine and the first one are even, or this one is faster.
- **The levels.** A handler's body is at a fresh type variable, so a capability, a prompt or a program does not
  leave it. A level further in is a subtype, so a program of a level further out runs inside: `flatMap` takes
  `Eff<? super L, B>`, and `.at()` moves a program in at the head of a chain. A lift is nothing at run time.
- **The first machine.** Before this one, each delimiter's body ran in a Java frame of its own (`EffLoop`, up to
  commit `fcd3868`). It overflowed the stack at about 1 000 nested handlers, and each handler crossed cost about
  6 ns. Measured on JDK 26 against this machine after its tuning (JMH, `bench.d/monads-machine-jdk26.csv`, the
  first machine's numbers from the README before): this one is faster on a general clause across 16 handlers (68
  against 110 ns), generate (11.4 against 14.3), a general clause (6.9 against 7.8) and left binds (6.2 against
  6.6); even on choose, state, functional state and a capture with 16 maps; slower on writer (20.2 against 17.3),
  a general clause across one handler (34 against 14), a capture with one map (169 against 130 ns) and captures
  over a record (569 against 339). Queens, measured one JVM a benchmark side by side: 137–155 against 110–145 µs.

## `okay.std`

Each effect is an interface at a level with an `at()`. Each handler takes a `Body` of its own, a generic method at a
fresh level, and is written with `Eff.handle` and `p.shift0`. A clause of the form `k(v)` is answered in place by
the capability itself, with no prompt: `Reader`, `Emit.forEach`, `State.cell`. `Writer` and `Choose.all` build their
answers in a `Rope`, joined in constant time and turned into a list once at the end. `Emit.generate` gives each element with the rest, a program at its level, so a generator runs under effects still to be handled. `iterator` and `stream` are built on it at the top. `Async` resumes the run in the
callback's thread, and the handlers inside go on with it, since `k` holds their delimiters by prompt.

## Tests

- **Written once, run on two backends** (`Backend`, `Backend0`): the machine and a reference.
  - `Ref` reads `Eff`'s nodes by the textbook. A body goes to its value or to a capture, and a delimiter takes its
    own and passes another's on.
  - `Ref0` is the same for `Cont0`.
  - `EffectsTest`, `ControlTest` and `Cont0Test` run on both.
- **`AgreeTest`.** It makes 1 000 programs at random, with handlers nested in any order, resets and shifts, and
  checks that the machine and the reference agree.
- **`LeakTest`.** It compiles each way out of a handler beside the right program. The right one compiles and the
  leak does not.
- **`GuideTest`.** It compiles and runs `docs/guide.md`'s examples, and checks that what the guide says does not
  compile does not.
- **`AsyncTest`.** Callbacks from other threads, constant stack, failures.

## Benchmarks (`src/jmh`)

- `MonadsBenchmark` has okay-core master's programs, under its names, plus:
  - the general clause across 1 and 16 handlers;
  - the functional state;
  - the deep capture with its boxes made by `new Integer` and by a record.
- `EffBenchmark` and `EffectsBenchmark` are okay-core's earlier ones.
- `./gradlew bench --args='… -prof gc' [-PbenchJdk=26]` runs them. `-PbenchJdk` picks the JDK, for example the one
  okay-core runs on. The runs are kept in `bench.d/`.
