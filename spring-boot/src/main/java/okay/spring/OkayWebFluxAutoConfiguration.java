package okay.spring;

import okay.core.Eff;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.HandlerResultHandler;

/**
 * okay-java in a reactive application of Spring Boot: a controller's method may return an {@link Eff}
 * ({@link EffResultHandler}), first among the result handlers by its order. Off by {@code spring.autoconfigure.exclude}
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnClass({Eff.class, HandlerResultHandler.class})
public class OkayWebFluxAutoConfiguration {

  @Bean
  EffResultHandler okayEffResults(ObjectProvider<HandlerResultHandler> handlers) {
    return new EffResultHandler(handlers);
  }
}
