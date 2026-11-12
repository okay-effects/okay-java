package okay.resumable;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.LambdaConversionException;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import okay.rows.Op;
import okay.rows.Sensitive;

/** What transformed code calls. Public because the transformed classes are anywhere; not an API: the transformation
 * writes these calls, a program never does. */
public final class Resume {
  private Resume() {}

  /** {@link Direct#perform}, as a transformed caller calls it: answered in place, yielded, or the operation leaves,
   * saving the frames on its way */
  public static Object perform(Op<?, ?, ?> op) {
    Object a = Driver.perform(op);
    if (a instanceof Capture c) throw c;
    return a;
  }

  /** {@link Direct#perform(Sensitive.Op)}, as a transformed caller calls it: never answered in place */
  public static Object performSensitive(Sensitive.Op<?, ?, ?> op) {
    Object a = Driver.performSensitive(op);
    if (a instanceof Capture c) throw c;
    return a;
  }

  // ---- captures on their way out of twins, by returns

  /** how many captures are on their way out of twins, on every thread: read plainly by transformed code after each
   * call to a twin — a thread sees its own count, so 0 means none of its own. Changed atomically, by captures only */
  public static int going;

  private static final VarHandle GOING;

  static {
    try {
      GOING = MethodHandles.lookup().findStaticVarHandle(Resume.class, "going", int.class);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /** this thread's capture on its way out of twins */
  private static final ThreadLocal<Capture> OUT = new ThreadLocal<>();

  /** the capture a twin leaves by returning, for its caller to take */
  public static void out(Capture c) {
    if (c.out) return;
    c.out = true;
    OUT.set(c);
    GOING.getAndAdd(1);
  }

  /** the capture taken by a method that is no twin: it goes on by a throw, or a copy's return to the boundary */
  public static Capture in(Capture c) {
    if (c.out) {
      c.out = false;
      OUT.set(null);
      GOING.getAndAdd(-1);
    }
    return c;
  }

  /** after a call to a twin, where some capture is on its way: this thread's, or null */
  public static Capture taken() {
    return OUT.get();
  }

  /** a twin's call to a static method: to its twin, if it has one, else to the method itself */
  public static CallSite twin(MethodHandles.Lookup lookup, String name, MethodType type, Class<?> owner)
      throws ReflectiveOperationException {
    MethodHandle m;
    try {
      m = lookup.findStatic(owner, name + "$okay$t", type);
    } catch (NoSuchMethodException | IllegalAccessException e) {
      m = lookup.findStatic(owner, name, type);
    }
    return new ConstantCallSite(m);
  }

  /** {@link #perform}, as a twin calls it: the capture returned, left for the caller */
  public static Object performOut(Op<?, ?, ?> op) {
    Object a = Driver.perform(op);
    if (!(a instanceof Capture c)) return a;
    out(c);
    return null;
  }

  public static Object performSensitiveOut(Sensitive.Op<?, ?, ?> op) {
    Object a = Driver.performSensitive(op);
    if (!(a instanceof Capture c)) return a;
    out(c);
    return null;
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
    if (!r.sites[site].tail()) c.nonTail = f;
    return c;
  }

  /** the outermost frame was entered by the boundary, as {@code e} says: a body's {@code run()}, a clause's {@code on}
   * or {@code ret} */
  static void outermost(Capture c, Driver.Entry e) {
    if (c.last == null) throw new IllegalStateException("okay.resumable: " + c.op + " was performed by no "
        + "transformed method: the rest of the program could not be resumed");
    Resumer r = c.last.resumer;
    if (r.entered == e) return;
    if (!calls(e.name(), e.desc(), r)) throw lost("the boundary (calling " + e.name() + ")", r);
    r.entered = e;
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
   * {@code kind|name|desc|covered|line|tail}, one a line */
  public static Resumer resumer(MethodHandles.Lookup lookup, String name, Class<?> type, MethodHandle resume,
      String owner, String method, String desc, String sites) {
    String[] parts = sites.isEmpty() ? new String[0] : sites.split("\n");
    Resumer.Site[] s = new Resumer.Site[parts.length];
    for (int i = 0; i < parts.length; i++) {
      String[] f = parts[i].split("\\|");
      s[i] = new Resumer.Site(f[0].charAt(0), f[1], f[2], f[3].equals("1"), Integer.parseInt(f[4]),
          f[5].equals("1"));
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
