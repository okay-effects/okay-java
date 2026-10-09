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
- **`feed`.** A value goes through the maps on top of the stack in a small method of its own, which the JIT compiles
  apart from the loop.
- **The levels.** A handler's body is at a fresh type variable, so a capability, a prompt or a program does not
  leave it. A level further in is a subtype, so a program of a level further out runs inside: `flatMap` takes
  `Eff<? super L, B>`, and `.at()` moves a program in at the head of a chain. A lift is nothing at run time.
- **The first machine.** Before this one, each delimiter's body ran in a Java frame of its own (`EffLoop`, up to
  commit `fcd3868`). It was faster by 5–15% where a handler sits right over its operations, and by 60% on a `shift`
  resumed in a chain, because the Java frame is a delimiter for free. But it overflowed the stack at about 1 000
  nested handlers, and each handler crossed cost about 6 ns. The explicit stack took its place: 22 ns across 16
  handlers where that was 110.

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
