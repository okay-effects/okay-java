package okay.spring;

import okay.core.Comp;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.AsyncAnnotationAdvisor;

/** {@code @Async} methods that return a {@link Comp} ({@link EffAsyncPostProcessor}): in force where
 * {@code @EnableAsync} is, nothing where it is not */
@AutoConfiguration
@ConditionalOnClass({Comp.class, AsyncAnnotationAdvisor.class})
public class OkayAsyncAutoConfiguration {

  @Bean
  static BeanPostProcessor okayEffAsync() {
    return new EffAsyncPostProcessor();
  }
}
