package okay.spring;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.scheduling.annotation.AsyncAnnotationAdvisor;
import org.springframework.scheduling.annotation.AsyncConfigurer;

/**
 * {@code @Async}'s advisor, in each proxy it was put in, replaced by one of the same pointcut whose interceptor is
 * {@link EffAsyncInterceptor}: Spring's own takes nothing but {@code void} and a {@code Future}, refusing any other
 * type before the call, and goes before every other advisor — so none in front of it could take an {@code Eff}.
 *
 * <p>Of no order: it runs after Spring's {@code AsyncAnnotationBeanPostProcessor}, an ordered one, on each bean, the
 * advisor already there. The executor is Spring's choice: an {@link AsyncConfigurer}'s, the application's
 * {@code TaskExecutor} where none
 */
final class EffAsyncPostProcessor implements BeanPostProcessor, BeanFactoryAware {
  private final Map<Advisor, Advisor> replaced = new ConcurrentHashMap<>();
  private BeanFactory beanFactory;

  @Override
  public void setBeanFactory(BeanFactory beanFactory) {
    this.beanFactory = beanFactory;
  }

  @Override
  public Object postProcessAfterInitialization(Object bean, String name) {
    if (bean instanceof Advised advised && !advised.isFrozen()) {
      for (Advisor a : advised.getAdvisors()) {
        if (a instanceof AsyncAnnotationAdvisor async && async.getAdvice() instanceof MethodInterceptor spring) {
          advised.replaceAdvisor(a, replaced.computeIfAbsent(a, _ -> ours(async, spring)));
        }
      }
    }
    return bean;
  }

  private Advisor ours(AsyncAnnotationAdvisor async, MethodInterceptor spring) {
    EffAsyncInterceptor interceptor = new EffAsyncInterceptor(spring);
    interceptor.configure(() -> configurer() == null ? null : configurer().getAsyncExecutor(),
        () -> configurer() == null ? null : configurer().getAsyncUncaughtExceptionHandler());
    interceptor.setBeanFactory(beanFactory);
    DefaultPointcutAdvisor ours = new DefaultPointcutAdvisor(async.getPointcut(), interceptor);
    ours.setOrder(async.getOrder());
    return ours;
  }

  private AsyncConfigurer configurer() {
    return beanFactory.getBeanProvider(AsyncConfigurer.class).getIfUnique();
  }
}
