# okay-java documentation

- [Guide](guide.md): programs at a level, the standard effects, effects of your own, what does not compile,
  delimited control, failures, generators, async. `GuideTest` compiles and runs every example.
- [Architecture](architecture.md): `Cont`, `Eff` and its machine, `okay.std`, the tests, the benchmarks.
- [okay-java and okay-core](vs-okay-core.md): what is done differently and why, and the speed side by side.
- [okay-kernel](kernel.md): okay-java as an implementation of okay-kernel's API — `Eff` a kernel program, the
  kernel's operations run by the machine; what it costs okay-java's own API.
- [Spring Boot](spring-boot.md): the optional module — a controller's method, in web MVC and WebFlux, and an `@Async`
  method returning an `Eff`; a program in a transaction; how each works and where it stops.
- [The row notation](rows.md): `okay.rows`, programs whose type is the row of their effects, okay-effects' Java
  interface on this machine; how it meets the capability notation; control and audit; what differs from the reference.
- [Direct style, resumable](../resumable/README.md): the optional module okay-java-resumable — plain Java methods whose
  effects are checked exceptions, made resumable many times by a build-time transformation, bridged into `okay.rows`;
  its Gradle and Maven plugins, the three ways an operation runs, the limits, measured.
