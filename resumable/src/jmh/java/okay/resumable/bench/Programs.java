package okay.resumable.bench;

import okay.resumable.Direct;
import okay.resumable.Counter;
import okay.resumable.Input;
import okay.resumable.Pick;

/** The direct-style programs measured: transformed by the build */
final class Programs {
  private Programs() {}

  static int count(int n) throws Counter {
    for (int i = 0; i < n; i++) Counter.put(Counter.get() + 1);
    return Counter.get();
  }

  static int sum(int n) throws Input {
    int s = 0;
    for (int i = 0; i < n; i++) s += Input.next();
    return s;
  }

  /** the same as count, the operations performed here: no call to the effect's own methods, which C2 never inlines
   * into a caller that is no Throwable — the effect is one */
  static int countPerformingHere(int n) throws Counter {
    for (int i = 0; i < n; i++) {
      int s = Direct.perform(new Counter.Get());
      Direct.perform(new Counter.Put(s + 1));
    }
    return Direct.perform(new Counter.Get());
  }

  /** the same, each operation ten calls deep: what a suspension costs by the frames it saves */
  static int sumDeep(int n) throws Input {
    int s = 0;
    for (int i = 0; i < n; i++) s += down(9);
    return s;
  }

  static int down(int d) throws Input {
    return d == 0 ? Input.next() : down(d - 1);
  }

  /** ten values at three levels: a thousand paths */
  static int choose() throws Pick {
    Integer[] ten = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
    int a = Pick.among(ten);
    int b = Pick.among(ten);
    int c = Pick.among(ten);
    return a * 100 + b * 10 + c;
  }

  /** a transformed method that never suspends: its call that may suspend is never taken */
  static int plus(int x, int y) throws Counter {
    if (x < 0) return Counter.get();
    return x + y;
  }
}
