# Architecture

okay-java has three layers:
- the monads of delimited continuations: `Cont` with `shift`/`reset`, and `Cont0` with `shift0`/`$`;
- `Eff`, effects at a level, run by a machine with prompts;
- `okay.std`, the effects written on `Eff`.

## `Cont` (`Cont.java`, `Loop.java`, `Frames.java`, `Head.java`, `Push.java`)

`Cont<I, O, A>` is Danvy–Filinski's `shift` and `reset`, with the answer type modified (Asai–Kameyama). A program is
a tree of nodes. The loop reads it to its head in constant stack, with a stack of frames of its own.

## `Cont0` (`Cont0.java`, `Loop0.java`)

`Cont0<I, O, A>` is Materzok–Biernacki's `shift0` and `$`, the levels typed in the answers. A `shift0`'s body runs
outside the delimiter, so its answers are programs of the level outside, `Cont0` again. `reset0`, `lift` and
`value` are built on the two. Each delimiter runs its body in a loop of its own, to where it leaves (`Loop0`). It is
open: usable as a monad with raw `shift0` and `$`, as in `Cont0Test`, the printf, and `PState0`.

## `Eff` (`Eff.java`, `Prompt.java`, `EffLoop.java`)

`Eff<L, A>` keeps `Cont0`'s machine and moves the answer types into the delimiters' prompts. The level `L` stays in
the type as a phantom.

- **Two primitives.**
  - `Eff.handle(body, ret)` is `ret $ body` under a prompt made fresh each time the handler runs. Its body is an
    `Eff.Body`, a generic method `<M extends L> Eff<M, A> in(Prompt<L, M, R> p)`, at a level as fresh as its prompt.
  - `p.shift0(clause)` is an operation at `M`: the rest up to `p`'s delimiter, the delimiter included, its clause
    run at `L`.
  - `p.shift` and `p.reset` are built on them, and `Eff.reset` is a handler that answers nothing.
- **Nodes.**
  - `Return`, `Bind`, `Map`, `Delay` and `Sync` are a value, a bind, a map, a tail call and a value computed in
    place.
  - `Shift0` is a capture. `Suspend` is a capture already bound.
  - `Handle` and `Reset` are delimiters.
  - `Push` and `Resume` exist only inside the machine: a program with its rest as a stack, and a resumption.
- **The loop, `delimited(c, k, prompt, ret, depth)`.** It is a delimiter's body run to where it leaves:
  - A value goes to `ret`.
  - A capture to this prompt runs its clause in the delimiter's place. `k` is the rest under the delimiter again,
    `Resumed`, applied lazily.
  - A capture to another prompt goes on outside as `Suspend`, with the rest re-delimited at once when answered
    (`Redelimit`).
- **`feed`.** A value goes through the maps on top of the stack in a small method of its own, which the JIT compiles
  apart from the loop.
- **Nesting.** A reset or a resumption under a delimiter of its own prompt runs nested up to 64 deep. Past that it
  runs out and back (`Rejoin`), so shifts' bodies nest in constant stack.
- **Bound delimiters.** A handler that is bound is entered at once, and its value goes to the function with no
  frame. A value computed in place, `Sync`, goes through the frames with no `Return` made for it.
- **The levels.** A handler's body is at a fresh type variable, so a capability, a prompt or a program does not
  leave it. A level further in is a subtype, so a program of a level further out runs inside: `flatMap` takes
  `Eff<? super L, B>`, and `.at()` moves a program in at the head of a chain. A lift is nothing at run time.

## `okay.std`

Each effect is an interface at a level with an `at()`. Each handler takes a `Body` of its own, a generic method at a
fresh level, and is written with `Eff.handle` and `p.shift0`. A clause of the form `k(v)` is answered in place by
the capability itself, with no prompt: `Reader`, `Emit.forEach`, `State.cell`. `Writer` and `Choose.all` build their
answers in a `Rope`, joined in constant time and turned into a list once at the end. `Async` resumes the run in the
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
