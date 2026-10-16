package okay.spring;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import okay.core.Eff;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.annotation.AnnotationAsyncExecutionInterceptor;

/**
 * {@code @Async} for a method that returns an {@link Eff}: the method and its program run on the {@code @Async}
 * executor — the one {@code @Async("name")} names, or the application's — the call returning at once with a program
 * that is their result: run, it waits for their value, and fails with their failure. Any other method is Spring's
 * own interceptor's, as it was ({@link EffAsyncPostProcessor})
 */
final class EffAsyncInterceptor extends AnnotationAsyncExecutionInterceptor {
  private final MethodInterceptor spring;

  EffAsyncInterceptor(MethodInterceptor spring) {
    super(null);
    this.spring = spring;
  }

  @Override
  public Object invoke(MethodInvocation invocation) throws Throwable {
    Class<?> target = invocation.getThis() != null ? AopUtils.getTargetClass(invocation.getThis()) : null;
    Method method = BridgeMethodResolver.getMostSpecificMethod(invocation.getMethod(), target);
    if (!Eff.class.isAssignableFrom(method.getReturnType())) return spring.invoke(invocation);
    AsyncTaskExecutor executor = determineAsyncExecutor(method);
    if (executor == null) throw new IllegalStateException("no executor for @Async " + method);
    CompletableFuture<Object> done = executor.submitCompletable(() -> {
      @SuppressWarnings("unchecked")
      Eff<Eff.Top, Object> program = (Eff<Eff.Top, Object>) proceed(invocation);
      return program == null ? null : Eff.run(program);
    });
    return Eff.<Eff.Top, Object>delay(() -> {
      try {
        return Eff.pure(done.join());
      } catch (CompletionException e) {
        return Eff.failed(e.getCause() != null ? e.getCause() : e);
      }
    });
  }

  private static Object proceed(MethodInvocation invocation) throws Exception {
    try {
      return invocation.proceed();
    } catch (Exception | Error e) {
      throw e;
    } catch (Throwable t) {
      throw new IllegalStateException(t);
    }
  }
}
