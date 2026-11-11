package okay.rows.std;

import java.util.ArrayList;
import java.util.List;

/** Values in the order they came, the last first: a handler's state that is never changed, only added to. */
record Chain<T>(T last, Chain<T> before) {
  /** the values, the first first; {@code null} is the chain of none */
  static <T> List<T> list(Chain<T> chain) {
    List<T> values = new ArrayList<>();
    for (Chain<T> at = chain; at != null; at = at.before) values.add(at.last);
    return values.reversed();
  }
}
