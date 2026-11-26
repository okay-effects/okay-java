package okay.spring;

import static okay.rows.Sets.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import okay.effect.Eff;
import okay.effect.Operation;
import okay.rows.Comp;
import okay.rows.Sets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** THE BOUNDARY OF AN APPLICATION AS A BEAN (docs/spring-boot.md, "The boundary as a bean"): the handlers of a
 * program whose row is a set ({@code okay.rows.Sets}) are put in one class whose type parameter names the set; the
 * class is a bean, built from the application's other beans, and injected where a program is to run. Nothing of
 * okay-java-spring-boot is needed for it. */
class SetBoundaryBeanTest {

  sealed interface Prices {
    record Price(String item) implements Prices, Operation<Long, Prices> {}
    record InStock(String item) implements Prices, Operation<Boolean, Prices> {}
  }
  sealed interface Ask { record Confirm(String question) implements Ask, Operation<Boolean, Ask> {} }

  /** business code: a program, its effects in its type */
  static <R extends Prices & Ask> Comp<String, R> checkout(List<String> cart) {
    return Sets.<String, Long, R>fold(cart, 0L, (sum, item) ->
            perform(new Prices.InStock(item)).flatMap(in ->
                in ? perform(new Prices.Price(item)).map(p -> sum + p) : pure(sum)))
        .flatMap(sum -> sum <= 100 ? pure("paid " + sum)
            : perform(new Ask.Confirm("pay " + sum + "?")).map(yes -> yes ? "paid " + sum : "declined"));
  }

  /** a resource of the application: what a real one would read from a database */
  record PriceList(Map<String, Long> table) {}

  /** the boundary: its type parameter names the set, its constructor takes the beans its handlers stand on */
  static final class Shop<V extends Prices & Ask> {
    private final PriceList prices;

    Shop(PriceList prices) { this.prices = prices; }

    <A> A run(boolean confirms, Comp<A, ? super V> program) {
      var row = Sets.<V>exactly(Shop.class).row(of(Prices.class, Ask.class));
      return Sets.run(asking(confirms, priced(row.of(program))));
    }

    private <A, R> Comp<A, R> priced(Comp<A, ? super Eff<Prices, R>> body) {
      return answering(op -> switch (op) {
        case Prices.Price p -> p.answer(prices.table().getOrDefault(p.item(), 0L));
        case Prices.InStock s -> s.answer(prices.table().containsKey(s.item()));
      }, body);
    }

    private static <A, R> Comp<A, R> asking(boolean yes, Comp<A, ? super Eff<Ask, R>> body) {
      return answering(op -> switch (op) { case Ask.Confirm c -> c.answer(yes); }, body);
    }
  }

  /** a service: the boundary injected, a program run through it */
  static final class Checkout {
    private final Shop<?> shop;

    Checkout(Shop<?> shop) { this.shop = shop; }

    String pay(List<String> cart, boolean confirms) { return shop.run(confirms, checkout(cart)); }
  }

  @Configuration
  static class App {
    @Bean PriceList priceList() { return new PriceList(Map.of("tea", 4L, "cup", 11L, "kettle", 120L)); }
    @Bean Shop<?> shop(PriceList prices) { return new Shop<>(prices); }
    @Bean Checkout checkout(Shop<?> shop) { return new Checkout(shop); }
  }

  @Test
  void theBoundaryIsABeanInjectedWhereAProgramRuns() {
    new ApplicationContextRunner().withUserConfiguration(App.class).run(c -> {
      Checkout checkout = c.getBean(Checkout.class);
      assertThat(checkout.pay(List.of("tea", "spoon", "cup"), false)).isEqualTo("paid 15");
      assertThat(checkout.pay(List.of("tea", "kettle"), false)).isEqualTo("declined");
      assertThat(checkout.pay(List.of("tea", "kettle"), true)).isEqualTo("paid 124");
    });
  }
}
