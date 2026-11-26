package okay.rows;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import okay.effect.Op;
import okay.effect.Operation;

/** THE EFFECT AN OPERATION SAYS IT IS OF, as a class: what {@code Op<Effect, ..>} or {@code Operation<..,
 * Effect>} names where the operation's class is declared. For operations asked by class ({@link Sets}): a block
 * asks those of one effect as one set. Read once for a class. */
final class Family {
  private Family() {}

  private static final ClassValue<Class<?>> OF = new ClassValue<>() {
    @Override
    protected Class<?> computeValue(Class<?> operation) {
      Class<?> found = named(operation);
      return found == null ? operation : found;
    }
  };

  /** the class of the effect of {@code op}; its own class where it names none that can be read */
  static Class<?> of(Object op) {
    return OF.get(op.getClass());
  }

  private static Class<?> named(Class<?> c) {
    for (Type t : c.getGenericInterfaces()) {
      Class<?> raw = raw(t);
      if (raw == null) continue;
      if (t instanceof ParameterizedType p) {
        if (raw == Op.class) return raw(p.getActualTypeArguments()[0]);
        if (raw == Operation.class) return raw(p.getActualTypeArguments()[1]);
      }
      Class<?> above = named(raw);
      if (above != null) return above;
    }
    return null;
  }

  private static Class<?> raw(Type t) {
    if (t instanceof Class<?> c) return c;
    if (t instanceof ParameterizedType p) return (Class<?>) p.getRawType();
    return null;
  }
}
