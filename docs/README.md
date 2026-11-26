# okay-java documentation

- **[Start here](start.md)**: effects the light way, on one page — an effect as a sealed family of records, a
  program whose row is the set of the effects it asks, written as a block (`together`), a chain (`flatMap`,
  `fold`) or plain Java, a handler by what it needs (`answering`, `handle` with the rest, with a state, with a
  set), where the handlers are put, what is refused and when, what it costs. `StartTest` compiles and runs every
  example. The other pages are the reference behind it.
- [Rows of places](places.md): the same model with each effect at a place of the row — effects indexed by their
  answers, `up`, a row given a name, one family. `PlacesTest` compiles and runs every example.
- [Guide](guide.md): programs at a level, the standard effects, effects of your own, what does not compile,
  delimited control, failures, generators, async. `GuideTest` compiles and runs every example.
- [Architecture](architecture.md): `Cont`, `Comp` and its machine, `okay.std`, the tests, the benchmarks.
- [okay-java and okay-core](vs-okay-core.md): what is done differently and why, and the speed side by side.
- [okay-kernel](kernel.md): okay-java as an implementation of okay-kernel's API — `Comp` a kernel program, the
  kernel's operations run by the machine; what it costs okay-java's own API.
- [Spring Boot](spring-boot.md): the optional module — a controller's method, in web MVC and WebFlux, and an `@Async`
  method returning a `Comp`; a program in a transaction; how each works and where it stops.
- [The row notation](rows.md): `okay.rows`, programs whose type is the row of their effects, okay-effects' Java
  interface on this machine; how it meets the capability notation; control and audit; what differs from the reference.
- [Direct style, resumable](../resumable/README.md): the optional module okay-java-resumable — plain Java methods whose
  effects are checked exceptions, made resumable many times by a build-time transformation, bridged into `okay.rows`;
  its Gradle and Maven plugins, the three ways an operation runs, the limits, measured.
