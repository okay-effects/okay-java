package sample;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class ShopTest {
  @Test
  void everyDeliveryQuoted() {
    var quotes = Shop.quotes(List.of("tea", "cup", "tea"));
    System.out.println(quotes + ", " + okay.resumable.Direct.continuations());
    assertEquals("the JVM's continuations", okay.resumable.Direct.continuations()); // the plugin gave the tests them
    assertEquals(List.of(new Shop.Quote("pickup", 19), new Shop.Quote("courier", 34)), quotes);
  }
}
