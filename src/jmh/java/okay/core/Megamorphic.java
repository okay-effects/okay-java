package okay.core;

import org.openjdk.jmh.annotations.Fork;

/** the benchmarks with no type profiles: no call site of the machine inlines the one function it saw, as in a program
 * whose call sites see many — the machine's own cost, not how lucky C2's inlining was for one benchmark */
public final class Megamorphic {
  private Megamorphic() {}

  @Fork(value = 2, jvmArgsAppend = "-XX:-UseTypeProfile")
  public static class Monads extends MonadsBenchmark {}

  @Fork(value = 2, jvmArgsAppend = "-XX:-UseTypeProfile")
  public static class Mixed extends MixedBenchmark {}
}
