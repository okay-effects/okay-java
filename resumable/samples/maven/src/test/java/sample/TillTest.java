package sample;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class TillTest {
  @Test
  void aBodyOfASetUnderItsHandlers() {
    assertEquals("paid 15", Till.DESK.run(false, Till.checkout(List.of("tea", "spoon", "cup"))));
    assertEquals("declined", Till.DESK.run(false, Till.checkout(List.of("tea", "kettle"))));
    assertEquals("paid 124", Till.DESK.run(true, Till.checkout(List.of("tea", "kettle"))));
  }
}
