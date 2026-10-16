package okay.spring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/** web MVC's on in a servlet application, WebFlux's in a reactive one, neither in an application not of the web;
 * {@code @Async}'s in any; the transactions' where there is one manager */
class AutoConfigurationTest {
  final AutoConfigurations okay =
      AutoConfigurations.of(OkayWebMvcAutoConfiguration.class, OkayWebFluxAutoConfiguration.class,
          OkayAsyncAutoConfiguration.class, OkayTransactionAutoConfiguration.class);

  @Test
  void webMvcsInAServletApplication() {
    new WebApplicationContextRunner().withConfiguration(okay)
        .run(c -> assertThat(c).hasBean("okayEffReturnValues").doesNotHaveBean("okayEffResults"));
  }

  @Test
  void webFluxsInAReactiveApplication() {
    new ReactiveWebApplicationContextRunner().withConfiguration(okay)
        .run(c -> assertThat(c).hasBean("okayEffResults").doesNotHaveBean("okayEffReturnValues"));
  }

  @Test
  void neitherInAnApplicationNotOfTheWeb() {
    new ApplicationContextRunner().withConfiguration(okay)
        .run(c -> assertThat(c).doesNotHaveBean("okayEffReturnValues").doesNotHaveBean("okayEffResults"));
  }

  @Test
  void asyncsInAnApplicationNotOfTheWeb() {
    new ApplicationContextRunner().withConfiguration(okay).run(c -> assertThat(c).hasBean("okayEffAsync"));
  }

  @Test
  void transactionsWhereThereIsAManager() {
    new ApplicationContextRunner().withConfiguration(okay)
        .run(c -> assertThat(c).doesNotHaveBean(EffTransactions.class));
    new ApplicationContextRunner().withConfiguration(okay)
        .withBean(PlatformTransactionManager.class, Nothing::new)
        .run(c -> assertThat(c).hasSingleBean(EffTransactions.class));
  }

  static final class Nothing extends AbstractPlatformTransactionManager {
    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object tx, org.springframework.transaction.TransactionDefinition definition) {}

    @Override
    protected void doCommit(DefaultTransactionStatus status) {}

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}
  }
}
