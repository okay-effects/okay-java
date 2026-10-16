package okay.spring;

import java.util.concurrent.CompletableFuture;
import okay.core.Eff;
import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.mvc.method.annotation.DeferredResultMethodReturnValueHandler;

/**
 * a controller's method that returns an {@link Eff} — a program at the top, {@code Eff<Eff.Top, A>}: run where the
 * request is, by {@link Eff#run}, its value the method's as if it had returned it (a body, a {@code ResponseEntity},
 * a view's name); its failure the method's throw, to the {@code @ExceptionHandler}s. Spring's way for a
 * {@code CompletableFuture} of the value, completed already: an async dispatch, the value's own type deciding how it
 * is written. A program that waits is {@code Async.runAsync}'s {@code CompletableFuture}, which Spring takes as it is
 */
public final class EffReturnValueHandler implements HandlerMethodReturnValueHandler {
  private final DeferredResultMethodReturnValueHandler future = new DeferredResultMethodReturnValueHandler();

  @Override
  public boolean supportsReturnType(MethodParameter returnType) {
    return Eff.class.isAssignableFrom(returnType.getParameterType());
  }

  @Override
  public void handleReturnValue(Object returnValue, MethodParameter returnType, ModelAndViewContainer mav,
      NativeWebRequest request) throws Exception {
    if (returnValue == null) {
      mav.setRequestHandled(true);
      return;
    }
    @SuppressWarnings("unchecked")
    Eff<Eff.Top, Object> program = (Eff<Eff.Top, Object>) returnValue;
    CompletableFuture<Object> done = new CompletableFuture<>();
    try {
      done.complete(Eff.run(program));
    } catch (RuntimeException e) {
      done.completeExceptionally(e);
    }
    future.handleReturnValue(done, returnType, mav, request);
  }
}
