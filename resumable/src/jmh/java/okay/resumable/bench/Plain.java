package okay.resumable.bench;

import okay.resumable.Counter;

/** {@link Programs#plus}, as javac wrote it: the build leaves this class untransformed */
final class Plain {
  private Plain() {}

  static int plus(int x, int y) throws Counter {
    if (x < 0) return Counter.get();
    return x + y;
  }
}
