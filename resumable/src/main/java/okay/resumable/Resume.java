package okay.resumable;

import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaConversionException;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import okay.rows.Op;

/** What transformed code calls. Public because the transformed classes are anywhere; not an API: the transformation
 * writes these calls, a program never does. */
public final class Resume {
  private Resume() {}

  /** {@link Direct#perform}, as a transformed caller calls it: answered in place, yielded, or the operation leaves,
   * saving the frames on its way */
  public static Object perform(Op<?, ?, ?> op) {
    return Driver.perform(op);
  }

  /** the frame of a transformed method added to an operation on its way out. The call it was suspended at must have
   * called the frame added before it — directly, or through a lambda — else a frame between them was not saved: a
   * method that was not transformed. Refused then, never resumed without it. {@code refs}, {@code prims}: its
   * values, null where none */
  public static Capture push(Capture c, Resumer r, int site, Object[] refs, long[] prims) {
    Frame f = new Frame(r, site, refs, prims);
    Frame inner = c.last;
    if (inner == null) c.first = f;
    else {
      // checked once for each callee a call is seen to have: the same callee again needs no look at the names
      if (r.callees[site] != inner.resumer) {
        Resumer.Site s = r.sites[site];
        if (!calls(s.name(), s.desc(), inner.resumer)) throw lost(r.where(site), inner.resumer);
        r.callees[site] = inner.resumer;
      }
      inner.next = f;
    }
    c.last = f;
    if (r.sites[site].covered()) c.covered = f;
    return c;
  }

  /** the outermost frame was entered by the boundary's {@code run()} */
  static void outermost(Capture c) {
    if (c.last == null) throw new IllegalStateException("okay.resumable: " + c.op + " was performed by no "
        + "transformed method: the rest of the program could not be resumed");
    Resumer r = c.last.resumer;
    if (r.entered) return;
    if (!calls("run", "()Ljava/lang/Object;", r)) throw lost("Direct.of (calling run)", r);
    r.entered = true;
  }

  private static boolean calls(String name, String desc, Resumer inner) {
    return name.equals(inner.method) && desc.equals(inner.desc)
        || ALIASES.getOrDefault(inner.key(), Set.of()).contains(name + desc);
  }

  private static IllegalStateException lost(String outer, Resumer inner) {
    return new IllegalStateException("okay.resumable: a frame was lost between " + outer + " and "
        + inner.display() + ": a method between them was not transformed, so the rest of the program could not be "
        + "resumed. Build its classes with the okay-java-resumable plugin, or run a jar with java -jar.");
  }

  /** a resumed frame whose call is not one of its method's: the class changed since it was saved */
  public static RuntimeException badSite(Frame f) {
    return new IllegalStateException("okay.resumable: no call " + f.site + " in " + f.resumer.display());
  }

  /** the constant of a transformed method: its {@link Resumer}. {@code sites}: for each call that may suspend,
   * {@code kind|name|desc|covered|line}, one a line */
  public static Resumer resumer(MethodHandles.Lookup lookup, String name, Class<?> type, MethodHandle resume,
      String owner, String method, String desc, String sites) {
    String[] parts = sites.isEmpty() ? new String[0] : sites.split("\n");
    Resumer.Site[] s = new Resumer.Site[parts.length];
    for (int i = 0; i < parts.length; i++) {
      String[] f = parts[i].split("\\|");
      s[i] = new Resumer.Site(f[0].charAt(0), f[1], f[2], f[3].equals("1"), Integer.parseInt(f[4]));
    }
    return new Resumer(resume, owner, method, desc, s);
  }

  /** methods entered through a lambda: the method's key, and the interface methods ({@code name+desc}) whose
   * lambdas run it */
  private static final ConcurrentHashMap<String, Set<String>> ALIASES = new ConcurrentHashMap<>();

  private static void alias(MethodHandles.Lookup lookup, MethodHandle impl, String name, MethodType sam) {
    MethodHandleInfo info = lookup.revealDirect(impl);
    String key = info.getDeclaringClass().getName().replace('.', '/') + "." + info.getName()
        + info.getMethodType().toMethodDescriptorString();
    ALIASES.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(name + sam.toMethodDescriptorString());
  }

  /** {@link LambdaMetafactory#metafactory}, the lambda's method remembered as entered by its interface method */
  public static CallSite lambda(MethodHandles.Lookup lookup, String name, MethodType type, MethodType sam,
      MethodHandle impl, MethodType instantiated) throws LambdaConversionException {
    alias(lookup, impl, name, sam);
    return LambdaMetafactory.metafactory(lookup, name, type, sam, impl, instantiated);
  }

  /** {@link LambdaMetafactory#altMetafactory}, the same */
  public static CallSite altLambda(MethodHandles.Lookup lookup, String name, MethodType type, Object... args)
      throws LambdaConversionException {
    alias(lookup, (MethodHandle) args[1], name, (MethodType) args[0]); // altMetafactory's order: sam, impl, ..
    return LambdaMetafactory.altMetafactory(lookup, name, type, args);
  }
}
