import okay.resumable.Counter;
import okay.resumable.Direct;

/** a method that performs an effect it does not declare */
class UndeclaredEffect {
  static int next() {
    return Direct.perform(new Counter.Get()) + 1; // refused
  }

  static int twice() throws Counter {
    return next() + Counter.get();
  }

  static int alsoNot() {
    return Counter.get(); // refused
  }
}
