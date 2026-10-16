package okay.spring;

import okay.core.Eff;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;

/** {@link EffTransactions} on the application's one {@link PlatformTransactionManager}: after Spring Boot's
 * transactions, which come after every configuration that makes a manager (JDBC's, JPA's) */
@AutoConfiguration(afterName = "org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration")
@ConditionalOnClass({Eff.class, PlatformTransactionManager.class})
@ConditionalOnSingleCandidate(PlatformTransactionManager.class)
public class OkayTransactionAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  EffTransactions effTransactions(PlatformTransactionManager transactions) {
    return new EffTransactions(transactions);
  }
}
