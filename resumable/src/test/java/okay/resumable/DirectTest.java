package okay.resumable;

import static okay.rows.Effects.run;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import okay.rows.std.State.Final;
import org.junit.jupiter.api.Test;

class DirectTest {
  static int twice() throws Counter {
    int a = Counter.get();
    Counter.put(a + 1);
    return a + Counter.get();
  }

  static int pair() throws Pick {
    int x = Pick.among(1, 2);
    int y = Pick.among(10, 20);
    return x + y;
  }

  @Test
  void state() {
    assertEquals(new Final<>(6, 11), run(Counter.run(5, Direct.of(Counter.class, DirectTest::twice))));
  }

  @Test
  void choice() {
    assertEquals(List.of(11, 21, 12, 22), run(Pick.all(Direct.of(Pick.class, DirectTest::pair))));
  }
}
