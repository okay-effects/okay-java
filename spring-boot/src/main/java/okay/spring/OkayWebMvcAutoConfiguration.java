package okay.spring;

import java.util.ArrayList;
import java.util.List;
import okay.core.Eff;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

/**
 * okay-java in a servlet application of Spring Boot: a controller's method may return an {@link Eff}
 * ({@link EffReturnValueHandler}). The handler goes FIRST among the adapter's: one added the usual way, by a
 * {@code WebMvcConfigurer}, comes after {@code @ResponseBody}'s, which would write the program itself as the body.
 * Off by {@code spring.autoconfigure.exclude}
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({Eff.class, RequestMappingHandlerAdapter.class})
public class OkayWebMvcAutoConfiguration {

  @Bean
  static BeanPostProcessor okayEffReturnValues() {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String name) {
        if (bean instanceof RequestMappingHandlerAdapter adapter) {
          List<HandlerMethodReturnValueHandler> handlers = new ArrayList<>();
          handlers.add(new EffReturnValueHandler());
          List<HandlerMethodReturnValueHandler> before = adapter.getReturnValueHandlers();
          if (before != null) handlers.addAll(before);
          adapter.setReturnValueHandlers(handlers);
        }
        return bean;
      }
    };
  }
}
