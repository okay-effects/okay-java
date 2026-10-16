package okay.spring;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;
import okay.core.Eff;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.ResolvableType;
import org.springframework.web.reactive.HandlerResult;
import org.springframework.web.reactive.HandlerResultHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * WebFlux's: a controller's method that returns an {@link Eff} — a program at the top, {@code Eff<Eff.Top, A>}: run
 * when the response is subscribed to, by {@link Eff#run}, its value handed to the other result handlers as if the
 * method had returned it, of the type {@code A} (a body, a {@code ResponseEntity}, a view's name); its failure the
 * method's, to the {@code @ExceptionHandler}s. Not a {@code ReactiveAdapter}: an adapted type's value is its FIRST
 * type argument, and an {@code Eff}'s first is its level. The run is on the thread that subscribes, an event loop's:
 * a program that blocks is {@code Async.runAsync}'s {@code CompletableFuture} on {@code blocking}, which WebFlux
 * takes as it is
 */
public final class EffResultHandler implements HandlerResultHandler, Ordered {
  private final ObjectProvider<HandlerResultHandler> handlers;
  private volatile List<HandlerResultHandler> others;

  public EffResultHandler(ObjectProvider<HandlerResultHandler> handlers) {
    this.handlers = handlers;
  }

  /** before every other: {@code ResponseEntity}'s is at 0, {@code @ResponseBody}'s would write the program itself */
  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }

  @Override
  public boolean supports(HandlerResult result) {
    return result.getReturnValue() instanceof Eff || Eff.class.isAssignableFrom(result.getReturnType().toClass());
  }

  @Override
  public Mono<Void> handleResult(ServerWebExchange exchange, HandlerResult result) {
    @SuppressWarnings("unchecked")
    Eff<Eff.Top, Object> program = (Eff<Eff.Top, Object>) result.getReturnValue();
    MethodParameter source = result.getReturnTypeSource();
    // the value's type: Eff's second argument where the method declares an Eff, the method's own type where not
    MethodParameter valueType = Eff.class.isAssignableFrom(source.getParameterType())
        ? new Value(source, result.getReturnType().as(Eff.class).getGeneric(1)) : source;
    return Mono.fromCallable(() -> Optional.ofNullable(program == null ? null : Eff.run(program)))
        .flatMap(value -> {
          HandlerResult answer = new HandlerResult(result.getHandler(), value.orElse(null), valueType,
              result.getBindingContext());
          for (HandlerResultHandler h : others()) {
            if (h.supports(answer)) return h.handleResult(exchange, answer);
          }
          return Mono.error(new IllegalStateException("no HandlerResultHandler for " + answer.getReturnValue()));
        });
  }

  /** the result handlers but this one, in their order: DispatcherHandler's, asked once */
  private List<HandlerResultHandler> others() {
    List<HandlerResultHandler> hs = others;
    if (hs == null) others = hs = handlers.orderedStream().filter(h -> h != this).toList();
    return hs;
  }

  /** the method's return type as the program's value's, {@code A} of {@code Eff<L, A>}, the method and its
   * annotations the same — a nested {@code MethodParameter} is not one WebFlux's handlers can take as theirs: its
   * {@code ResolvableType}'s source is the type, not the parameter (MVC's {@code ConcurrentResultMethodParameter}) */
  private static final class Value extends MethodParameter {
    private final ResolvableType type;

    Value(MethodParameter source, ResolvableType type) {
      super(source);
      this.type = type;
    }

    @Override
    public Class<?> getParameterType() {
      return type.toClass();
    }

    @Override
    public Type getGenericParameterType() {
      return type.getType();
    }

    @Override
    public Value clone() {
      return new Value(this, type);
    }
  }
}
