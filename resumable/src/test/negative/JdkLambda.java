import java.util.List;
import okay.resumable.Counter;

/** an effect performed inside a JDK lambda: the JDK's frame cannot be saved, and its interface throws nothing */
class JdkLambda {
  static void all(List<Integer> values) throws Counter {
    values.forEach(v -> Counter.put(v)); // refused
  }

  static int sum(List<Integer> values) throws Counter {
    return values.stream().mapToInt(v -> v + Counter.get()).sum(); // refused
  }
}
