package okay.resumable.transform;

import static java.lang.classfile.attribute.StackMapFrameInfo.SimpleVerificationTypeInfo.DOUBLE;
import static java.lang.classfile.attribute.StackMapFrameInfo.SimpleVerificationTypeInfo.FLOAT;
import static java.lang.classfile.attribute.StackMapFrameInfo.SimpleVerificationTypeInfo.INTEGER;
import static java.lang.classfile.attribute.StackMapFrameInfo.SimpleVerificationTypeInfo.LONG;
import static java.lang.classfile.attribute.StackMapFrameInfo.SimpleVerificationTypeInfo.NULL;
import static java.lang.classfile.attribute.StackMapFrameInfo.SimpleVerificationTypeInfo.TOP;

import java.io.ByteArrayInputStream;
import java.lang.classfile.Annotation;
import java.lang.classfile.Attribute;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassElement;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.MethodElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.attribute.StackMapFrameInfo;
import java.lang.classfile.attribute.StackMapFrameInfo.ObjectVerificationTypeInfo;
import java.lang.classfile.attribute.StackMapFrameInfo.VerificationTypeInfo;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LineNumber;
import java.lang.classfile.instruction.LocalVariable;
import java.lang.classfile.instruction.LocalVariableType;
import java.lang.classfile.instruction.LookupSwitchInstruction;
import java.lang.classfile.instruction.MonitorInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.classfile.instruction.StackInstruction;
import java.lang.classfile.instruction.SwitchCase;
import java.lang.classfile.instruction.TableSwitchInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.DynamicCallSiteDesc;
import java.lang.constant.DynamicConstantDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** THE TRANSFORMATION: every method with a call that may suspend — to a method whose {@code throws} names a subclass
 * of {@code okay.resumable.Suspend} or {@code SensitiveSuspend} — is rewritten so that its frame can be saved as
 * data and re-entered, after "continuations from generalized stack inspection" (Pettyjohn et al., ICFP 2005):
 * <ul>
 * <li>each such call is covered by a handler of {@code Capture}, first in the exception table so that no handler of
 * the method sees it: it saves the call's index, the locals, and the operand stack under the call — spilled to fresh
 * locals before the call, so the stack at the call holds only its arguments — and rethrows;
 * <li>a new private static method, {@code $okay$r$N(Frame, Object, Throwable)}, is a copy of the body that begins by
 * restoring a frame's values and jumps to just after its call, with the answer as the call's value — or to the call,
 * where the given exception is thrown as if by the callee (an unwinding: the {@code finally} blocks run);
 * <li>{@code Direct.perform} is called as {@code Resume.perform}, which suspends; a lambda whose body may suspend is
 * linked through {@code Resume.lambda}, which remembers the interface method that enters it.
 * </ul>
 * The types of the locals and of the stack at each call are found by asking the JDK's own stack-map generator: a
 * first pass puts a branch target at each call and reads the frame it computes there. */
public final class Transformer {
  /** a method the transformation cannot rewrite, with what to change */
  public static final class Refused extends RuntimeException {
    Refused(String message) {
      super(message);
    }
  }

  static final String SUSPEND = "okay/resumable/Suspend";
  static final String SENSITIVE = "okay/resumable/SensitiveSuspend";
  static final ClassDesc CD_RESUME = ClassDesc.of("okay.resumable.Resume");
  static final ClassDesc CD_CAPTURE = ClassDesc.of("okay.resumable.Capture");
  static final ClassDesc CD_FRAME = ClassDesc.of("okay.resumable.Frame");
  static final ClassDesc CD_RESUMER = ClassDesc.of("okay.resumable.Resumer");
  static final ClassDesc CD_TRANSFORMED = ClassDesc.of("okay.resumable.Transformed");
  static final ClassDesc CD_OP = ClassDesc.of("okay.rows.Op");
  static final ClassDesc CD_SENSITIVE_OP = ClassDesc.of("okay.rows.Sensitive$Op");
  static final ClassDesc CD_OBJECTS = ConstantDescs.CD_Object.arrayType();
  static final ClassDesc CD_LONGS = ConstantDescs.CD_long.arrayType();
  static final MethodTypeDesc RESUME_TYPE =
      MethodTypeDesc.of(ConstantDescs.CD_Object, CD_FRAME, ConstantDescs.CD_Object, ConstantDescs.CD_Throwable);

  /** THE GUARD: what is done with effectful code — a method rewritten here — that reaches an effect's
   * implementation other than to install it: calls a handler's clause, calls or touches the state of a handler or
   * of a class marked {@code @Implementation}. Off by default; a warning; or a refusal, as a method that cannot
   * suspend is refused */
  public enum Guard {
    OFF, WARN, ERROR;

    /** {@code off}, {@code warn} or {@code error}, as a build names it */
    public static Guard of(String name) {
      return switch (name.strip().toLowerCase(java.util.Locale.ROOT)) {
        case "", "off", "false" -> OFF;
        case "warn", "warning" -> WARN;
        case "error", "true" -> ERROR;
        default -> throw new IllegalArgumentException("okay.resumable: guard " + name + ": off, warn or error");
      };
    }
  }

  private final Function<String, byte[]> bytes;
  private final ClassFile cf;
  private final Map<String, ClassModel> models = new HashMap<>();
  private final Map<String, Boolean> suspendTypes = new HashMap<>();
  private final Map<String, Boolean> suspending = new HashMap<>();
  private final Guard guard;
  /** the APIs effectful code may not use, checked by okay-audit ({@link Forbid#of}): none when null */
  private final Forbid forbid;
  private final java.util.function.Consumer<String> warnings;
  private final Map<String, Boolean> implementations = new HashMap<>();

  /** {@code bytes}: a class file by its internal name, or null — the classes being transformed, their classpath, and
   * the JDK */
  public Transformer(Function<String, byte[]> bytes) {
    this(bytes, Guard.OFF, w -> {});
  }

  /** the same, with the guard: its warnings given to {@code warnings} */
  public Transformer(Function<String, byte[]> bytes, Guard guard, java.util.function.Consumer<String> warnings) {
    this(bytes, guard, null, warnings);
  }

  /** the same, with the APIs effectful code may not use ({@link Forbid#of}, or null): refused, or said if the guard
   * warns */
  public Transformer(Function<String, byte[]> bytes, Guard guard, Forbid forbid,
      java.util.function.Consumer<String> warnings) {
    this.forbid = forbid;
    this.guard = guard;
    this.warnings = warnings;
    this.bytes = bytes;
    this.cf = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(
        ClassHierarchyResolver.ofResourceParsing(d -> {
          byte[] b = bytes.apply(d.descriptorString().substring(1, d.descriptorString().length() - 1));
          return b == null ? null : new ByteArrayInputStream(b);
        }).orElse(ClassHierarchyResolver.defaultResolver()).cached()));
  }

  /** the class rewritten, or null when it has nothing to rewrite */
  public byte[] transform(byte[] in) {
    ClassModel cm = cf.parse(in);
    String self = cm.thisClass().asInternalName();
    if (runtime(self) || marked(cm)) return null;
    models.put(self, cm);
    Map<String, Plan> plans = new HashMap<>();
    for (MethodModel mm : cm.methods()) {
      Plan p = plan(cm, mm);
      if (p != null) plans.put(key(mm), p);
    }
    Map<String, Boolean> lambdas = lambdas(cm, plans);
    if (plans.isEmpty() && lambdas.isEmpty()) return null;
    if (!plans.isEmpty()) probe(cm, plans);
    List<Plan> unmade = plans.values().stream().filter(Transformer::unmade).toList();
    if (!unmade.isEmpty() && !made(cm)) return transform(made(cm, unmade));
    if (guard != Guard.OFF || forbid != null) guard(cm, in, plans.values());
    return cf.transformClass(cm, new Rewrite(cm, plans, lambdas));
  }

  /** whether an object not yet constructed is on the stack at a call that may suspend: {@code new X(.., call(), ..)} */
  private static boolean unmade(Plan p) {
    for (StackMapFrameInfo f : p.frames.values())
      for (VerificationTypeInfo t : f.stack())
        if (t instanceof StackMapFrameInfo.UninitializedVerificationTypeInfo) return true;
    return false;
  }

  private static final String MADE = "okay$made";

  private boolean made(ClassModel cm) {
    return cm.fields().stream().anyMatch(f -> f.fieldName().equalsString(MADE));
  }

  /** the class with each {@code new X; dup; args..; invokespecial X.<init>} of these methods made
   * {@code args..; new X; dup; args..; invokespecial}, the arguments spilled to locals and loaded back: nothing
   * unconstructed is then on the stack at a call. X's class is initialized after the arguments, not before. A field
   * marks the class as done, so this happens once. */
  private byte[] made(ClassModel cm, List<Plan> unmade) {
    Map<String, Plan> byKey = new HashMap<>();
    for (Plan p : unmade) byKey.put(key(p.method), p);
    return cf.transformClass(cm, ClassTransform.endHandler(clb -> clb.withField(MADE, ConstantDescs.CD_boolean,
        ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC)).andThen((clb, ce) -> {
          Plan p = ce instanceof MethodModel mm ? byKey.get(key(mm)) : null;
          if (p == null) {
            clb.with(ce);
            return;
          }
          clb.withMethod(p.method.methodName(), p.method.methodType(), p.method.flags().flagsMask(), mb -> {
            for (MethodElement me : p.method) if (!(me instanceof CodeAttribute)) mb.with(me);
            mb.withCode(cb -> made(cb, p));
          });
        }));
  }

  private static void made(CodeBuilder cb, Plan p) {
    Relabel r = new Relabel(cb);
    ArrayDeque<Boolean> pending = new ArrayDeque<>();
    for (int i = 0; i < p.elements.size(); i++) {
      CodeElement e = p.elements.get(i);
      if (e instanceof NewObjectInstruction) {
        boolean moved = i + 1 < p.elements.size()
            && p.elements.get(i + 1) instanceof StackInstruction s && s.opcode() == Opcode.DUP;
        pending.push(moved);
        if (moved) {
          i++;
          continue;
        }
      }
      if (e instanceof InvokeInstruction inv && inv.opcode() == Opcode.INVOKESPECIAL
          && inv.name().equalsString("<init>") && !pending.isEmpty() && pending.pop()) {
        List<ClassDesc> params = inv.typeSymbol().parameterList();
        int[] slots = new int[params.size()];
        int slot = p.maxLocals;
        for (int j = 0; j < params.size(); j++) {
          slots[j] = slot;
          slot += TypeKind.from(params.get(j)).slotSize();
        }
        for (int j = params.size() - 1; j >= 0; j--) cb.storeLocal(TypeKind.from(params.get(j)), slots[j]);
        cb.new_(inv.owner().asSymbol()).dup();
        for (int j = 0; j < params.size(); j++) cb.loadLocal(TypeKind.from(params.get(j)), slots[j]);
      }
      r.emit(e);
    }
  }

  /** the boundary itself runs bodies and catches their suspensions: it is never rewritten */
  private static boolean runtime(String name) {
    for (String c : new String[] {"Direct", "Driver", "LoomRun"}) {
      String n = "okay/resumable/" + c;
      if (name.equals(n) || name.startsWith(n + "$")) return true;
    }
    return false;
  }

  // ---- what to rewrite

  /** a call that may suspend: its index in the method's elements, its invoke, its line */
  record Site(int index, InvokeInstruction invoke, boolean perform, boolean covered, int line, int ordinal) {}

  static final class Plan {
    final MethodModel method;
    final List<CodeElement> elements;
    final int maxLocals;
    final List<Site> sites = new ArrayList<>();
    final Map<Site, StackMapFrameInfo> frames = new IdentityHashMap<>();
    int number;

    Plan(MethodModel method, CodeAttribute code) {
      this.method = method;
      this.elements = code.elementList();
      this.maxLocals = code.maxLocals();
    }
  }

  private static String key(MethodModel mm) {
    return mm.methodName().stringValue() + mm.methodType().stringValue();
  }

  private Plan plan(ClassModel cm, MethodModel mm) {
    CodeAttribute code = mm.findAttribute(Attributes.code()).orElse(null);
    if (code == null) return null;
    Plan p = new Plan(mm, code);
    List<int[]> ranges = new ArrayList<>();
    for (CodeElement e : p.elements)
      if (e instanceof ExceptionCatch c)
        ranges.add(new int[] {code.labelToBci(c.tryStart()), code.labelToBci(c.tryEnd())});
    int bci = 0;
    int line = 0;
    int ordinal = 0;
    boolean monitor = false;
    for (int i = 0; i < p.elements.size(); i++) {
      CodeElement e = p.elements.get(i);
      if (e instanceof LineNumber ln) line = ln.line();
      if (e instanceof MonitorInstruction) monitor = true;
      if (e instanceof InvokeInstruction inv) {
        boolean perform = inv.owner().asInternalName().equals("okay/resumable/Direct")
            && inv.name().stringValue().equals("perform");
        if (perform || suspends(inv.owner().asInternalName(), inv.name().stringValue(), inv.type().stringValue())) {
          int at = bci;
          boolean covered = ranges.stream().anyMatch(r -> r[0] <= at && at < r[1]);
          p.sites.add(new Site(i, inv, perform, covered, line, ordinal));
        }
        ordinal++;
      }
      if (e instanceof Instruction ins) bci += ins.sizeInBytes();
    }
    if (p.sites.isEmpty()) return null;
    String where = cm.thisClass().asInternalName().replace('/', '.') + "." + mm.methodName().stringValue();
    if (mm.methodName().stringValue().equals("<init>"))
      throw new Refused(where + ": a constructor cannot suspend; perform in a method or a static factory");
    if (mm.methodName().stringValue().equals("<clinit>"))
      throw new Refused(where + ": a static initializer cannot suspend");
    if (mm.flags().has(java.lang.reflect.AccessFlag.SYNCHRONIZED) || monitor)
      throw new Refused(where + ": a synchronized method or block cannot suspend: a monitor cannot be saved");
    return p;
  }

  /** whether the call at {@code index} is in tail position: what follows it only returns its value — a cast, an
   * unboxing or a boxing, a jump, a store to a local and that local's load, then a return. The value is followed
   * where it goes, on the stack or in one local: a local read for anything but to be returned, any other
   * instruction, a branch that tests, is not tail. {@code var r = k.resume(..); return r;}, a ternary's arms, a
   * switch's value kept in a local and returned all are. A frame suspended at such a call adds nothing to what the
   * call gives */
  static boolean tail(List<CodeElement> elements, int index) {
    Map<Label, Integer> at = new HashMap<>();
    for (int i = 0; i < elements.size(); i++) if (elements.get(i) instanceof LabelTarget t) at.put(t.label(), i);
    // where the call's value is: on the stack (-1), in a local (its slot), or none (-2) for a void call
    boolean isVoid = elements.get(index) instanceof InvokeInstruction inv
        && inv.typeSymbol().returnType().equals(ConstantDescs.CD_void);
    int where = isVoid ? -2 : -1;
    int i = index + 1;
    for (int steps = 0; i < elements.size() && steps < 64; steps++) {
      CodeElement e = elements.get(i);
      switch (e) {
        case ReturnInstruction r -> {
          return r.typeKind() == TypeKind.VOID ? where == -2 : where == -1;
        }
        case BranchInstruction b when b.opcode() == Opcode.GOTO || b.opcode() == Opcode.GOTO_W -> {
          i = at.get(b.target());
          continue;
        }
        case java.lang.classfile.instruction.TypeCheckInstruction c when c.opcode() == Opcode.CHECKCAST -> {
          if (where != -1) return false;
        }
        case InvokeInstruction u when unboxing(u) || boxing(u) -> {
          if (where != -1) return false;
        }
        case java.lang.classfile.instruction.StoreInstruction s -> {
          if (where != -1) return false;
          where = s.slot();
        }
        case java.lang.classfile.instruction.LoadInstruction l -> {
          if (where < 0 || l.slot() != where) return false;
          where = -1;
        }
        case Instruction ins -> {
          return false;
        }
        default -> { } // a label, a line number, debug information
      }
      i++;
    }
    return false;
  }

  /** {@code Integer.intValue()} and the like: a value unboxed to be returned, boxed again by a resumed copy */
  private static boolean unboxing(InvokeInstruction u) {
    String owner = u.owner().asInternalName();
    return u.opcode() == Opcode.INVOKEVIRTUAL && owner.startsWith("java/lang/")
        && u.name().stringValue().endsWith("Value") && u.typeSymbol().parameterCount() == 0
        && u.typeSymbol().returnType().isPrimitive();
  }

  /** {@code Integer.valueOf(int)} and the like: a primitive boxed to be returned — of a value unboxed before */
  private static boolean boxing(InvokeInstruction u) {
    String owner = u.owner().asInternalName();
    return u.opcode() == Opcode.INVOKESTATIC && owner.startsWith("java/lang/")
        && u.name().stringValue().equals("valueOf") && u.typeSymbol().parameterCount() == 1
        && u.typeSymbol().parameterList().getFirst().isPrimitive()
        && u.typeSymbol().returnType().descriptorString().equals("L" + owner + ";");
  }

  private boolean marked(ClassModel cm) {
    return cm.findAttribute(Attributes.runtimeVisibleAnnotations()).map(a -> a.annotations().stream()
        .anyMatch(an -> an.classSymbol().equals(CD_TRANSFORMED))).orElse(false);
  }

  private ClassModel model(String name) {
    if (models.containsKey(name)) return models.get(name);
    byte[] b = name.startsWith("[") ? null : bytes.apply(name);
    ClassModel m = b == null ? null : cf.parse(b);
    models.put(name, m);
    return m;
  }

  private static boolean jdk(String name) {
    return name.startsWith("java/") || name.startsWith("javax/") || name.startsWith("jdk/")
        || name.startsWith("sun/") || name.startsWith("[");
  }

  /** whether the method, found from {@code owner} up its classes and interfaces, declares a Suspend in throws */
  boolean suspends(String owner, String name, String desc) {
    if (jdk(owner)) return false;
    String k = owner + "." + name + desc;
    Boolean known = suspending.get(k);
    if (known != null) return known;
    suspending.put(k, false); // a cycle of interfaces ends here
    boolean r = find(owner, name, desc);
    suspending.put(k, r);
    return r;
  }

  private boolean find(String owner, String name, String desc) {
    ClassModel c = model(owner);
    if (c == null) return false;
    for (MethodModel m : c.methods())
      if (m.methodName().equalsString(name) && m.methodType().equalsString(desc))
        return m.findAttribute(Attributes.exceptions()).map(x -> x.exceptions().stream()
            .anyMatch(e -> suspendType(e.asInternalName()))).orElse(false);
    if (c.superclass().isPresent() && suspends(c.superclass().get().asInternalName(), name, desc)) return true;
    for (var i : c.interfaces()) if (suspends(i.asInternalName(), name, desc)) return true;
    return false;
  }

  private boolean suspendType(String name) {
    if (name.equals(SUSPEND) || name.equals(SENSITIVE)) return true;
    if (jdk(name)) return false;
    Boolean known = suspendTypes.get(name);
    if (known != null) return known;
    ClassModel c = model(name);
    boolean r = c != null && c.superclass().isPresent() && suspendType(c.superclass().get().asInternalName());
    suspendTypes.put(name, r);
    return r;
  }

  /** the lambdas of the class whose method may suspend, by the instruction: true for altMetafactory */
  private Map<String, Boolean> lambdas(ClassModel cm, Map<String, Plan> plans) {
    Map<String, Boolean> found = new HashMap<>();
    String self = cm.thisClass().asInternalName();
    for (MethodModel mm : cm.methods()) {
      var code = mm.findAttribute(Attributes.code());
      if (code.isEmpty()) continue;
      for (CodeElement e : code.get().elementList()) {
        if (!(e instanceof InvokeDynamicInstruction indy)) continue;
        DirectMethodHandleDesc bsm = indy.bootstrapMethod();
        if (!bsm.owner().descriptorString().equals("Ljava/lang/invoke/LambdaMetafactory;")) continue;
        if (!(indy.bootstrapArgs().get(1) instanceof DirectMethodHandleDesc impl)) continue;
        String owner = impl.owner().descriptorString();
        owner = owner.substring(1, owner.length() - 1);
        boolean mine = owner.equals(self) && plans.containsKey(impl.methodName() + impl.lookupDescriptor());
        if (mine || suspends(owner, impl.methodName(), impl.lookupDescriptor()))
          found.put(lambdaKey(indy), bsm.methodName().equals("altMetafactory"));
      }
    }
    return found;
  }

  private static String lambdaKey(InvokeDynamicInstruction indy) {
    return indy.name().stringValue() + indy.type().stringValue() + indy.bootstrapArgs();
  }

  // ---- the guard: effectful code that reaches an effect's implementation

  /** the handler interfaces: a class that implements one is a handler, and their methods are its clauses */
  static final java.util.Set<String> HANDLERS = java.util.Set.of("okay/resumable/Direct$Handler0",
      "okay/resumable/Direct$Handler1", "okay/resumable/Direct$Handler2", "okay/resumable/Direct$Handler3",
      "okay/resumable/Direct$Handler4", "okay/resumable/Direct$InPlace", "okay/rows/Handler", "okay/rows/InPlace",
      "okay/rows/Interceptor", "okay/rows/Observer");
  static final String IMPLEMENTATION = "Lokay/resumable/Implementation;";

  /** what the guard finds in the methods rewritten of {@code cm}, and what {@link #forbid} finds there: said, or
   * refused */
  private void guard(ClassModel cm, byte[] in, java.util.Collection<Plan> plans) {
    String self = cm.thisClass().asInternalName();
    boolean impl = guard != Guard.OFF && implementation(self);
    List<String> found = new ArrayList<>();
    List<String> forbade = new ArrayList<>();
    List<String> flagged = new ArrayList<>();
    if (forbid != null) {
      java.util.Set<String> methods = new java.util.HashSet<>();
      for (Plan p : plans) methods.add(p.method.methodName().stringValue() + p.method.methodType().stringValue());
      forbid.check(in, methods, forbade, flagged);
    }
    String reaching = ": effectful code may construct a handler or a class marked @Implementation, to install it, "
        + "and nothing else — perform the effect instead, so that its handlers, and its audit, answer it";
    for (Plan p : plans) {
      if (guard == Guard.OFF || impl) break;
      String where = self.replace('/', '.') + "." + p.method.methodName().stringValue();
      int line = 0;
      for (CodeElement e : p.elements) {
        if (e instanceof LineNumber ln) line = ln.line();
        String owner;
        String name;
        String what;
        switch (e) {
          case InvokeInstruction inv -> {
            owner = inv.owner().asInternalName();
            name = inv.name().stringValue();
            what = name.equals("<init>") ? "constructs" : "calls " + name + " of";
          }
          case java.lang.classfile.instruction.FieldInstruction f -> {
            owner = f.owner().asInternalName();
            name = f.name().stringValue();
            boolean put = f.opcode() == Opcode.PUTFIELD || f.opcode() == Opcode.PUTSTATIC;
            what = (put ? "writes " : "reads ") + name + " of";
          }
          default -> {
            continue;
          }
        }
        String at = where + " (line " + line + ") ";
        if (HANDLERS.contains(owner) && e instanceof InvokeInstruction)
          found.add(at + "calls " + simple(owner) + "." + name + ", a handler's clause, directly" + reaching);
        else if (!name.equals("<init>") && !owner.equals(self) && implementation(owner))
          found.add(at + what + " " + owner.replace('/', '.') + ", an effect's implementation" + reaching);
      }
    }
    for (String f : flagged) warnings.accept("okay.resumable: warning: " + f);
    found.addAll(forbade);
    if (found.isEmpty()) return;
    // the forbidden APIs, named with the guard off, are refused
    if (guard != Guard.WARN) throw new Refused(String.join("\n", found));
    for (String f : found) warnings.accept("okay.resumable: warning: " + f);
  }

  /** THE APIS EFFECTFUL CODE MAY NOT USE — the rules and presets are okay-audit's ({@code Audited}), this module
   * keeps only what is the transformation's own: the guard above, and a monitor in a method that may suspend */
  public interface Forbid {
    /** what the methods {@code methods} (name and descriptor) of the class file {@code bytes} reach that the list
     * forbids: a refusal each into {@code refused}, what is said and not refused into {@code said} */
    void check(byte[] bytes, java.util.Set<String> methods, List<String> refused, List<String> said);

    /** the list as a build names it — comma or space separated: {@code strict}, the presets named after the effects
     * of okay.rows.std ({@code state}, {@code reader}, {@code writer}/{@code trail}, {@code clock}, {@code fresh},
     * {@code async}, {@code io}, {@code nested-run}), {@code pure} all of them, a package ({@code java.net.*}), a
     * class or a member ({@code java.lang.System.exit}) — checked by okay-audit; null for an empty list. Refused by
     * name when okay-audit is not on the transformer's class path. {@code bytes}: a class file by internal name */
    static Forbid of(String list, Function<String, byte[]> bytes) {
      if (list == null || list.isBlank()) return null;
      try {
        return Audited.of(list, bytes);
      } catch (LinkageError e) {
        throw new IllegalStateException("okay.resumable: forbid = \"" + list.strip() + "\" is checked by okay-audit "
            + "(io.github.okay-effects:okay-audit_3), which is not on the transformer's class path: add it, or name "
            + "nothing to forbid", e);
      }
    }
  }

  private static String simple(String internal) {
    return internal.substring(internal.lastIndexOf('/') + 1).replace('$', '.');
  }

  /** whether the class is a handler (it implements a handler interface, up its classes and interfaces) or is marked
   * {@code @Implementation} */
  boolean implementation(String name) {
    if (jdk(name)) return false;
    Boolean known = implementations.get(name);
    if (known != null) return known;
    implementations.put(name, false); // a cycle ends here
    ClassModel c = model(name);
    boolean r = c != null && (marked(c, IMPLEMENTATION) || HANDLERS.contains(name)
        || c.interfaces().stream().anyMatch(i -> HANDLERS.contains(i.asInternalName())
            || implementation(i.asInternalName()))
        || c.superclass().isPresent() && implementation(c.superclass().get().asInternalName()));
    implementations.put(name, r);
    return r;
  }

  private static boolean marked(ClassModel c, String annotation) {
    return c.findAttribute(Attributes.runtimeInvisibleAnnotations()).map(a -> a.annotations().stream()
        .anyMatch(an -> an.className().equalsString(annotation))).orElse(false)
        || c.findAttribute(Attributes.runtimeVisibleAnnotations()).map(a -> a.annotations().stream()
            .anyMatch(an -> an.className().equalsString(annotation))).orElse(false);
  }

  // ---- the probe: the JDK's stack-map generator tells the types at each call

  private void probe(ClassModel cm, Map<String, Plan> plans) {
    byte[] probed = cf.transformClass(cm, (clb, ce) -> {
      Plan p = ce instanceof MethodModel mm ? plans.get(key(mm)) : null;
      if (p == null) {
        clb.with(ce);
        return;
      }
      int flags = p.method.flags().flagsMask();
      clb.withMethod(p.method.methodName(), p.method.methodType(), flags, mb -> mb.withCode(cb -> {
        Relabel r = new Relabel(cb);
        int s = 0;
        for (int i = 0; i < p.elements.size(); i++) {
          CodeElement e = p.elements.get(i);
          if (s < p.sites.size() && p.sites.get(s).index() == i) {
            Label at = cb.newLabel();
            cb.iconst_0().ifne(at).labelBinding(at);
            s++;
          }
          if (!r.debug(e)) r.emit(e);
        }
      }));
    });
    ClassModel pm = cf.parse(probed);
    for (MethodModel mm : pm.methods()) {
      Plan p = plans.get(key(mm));
      if (p == null) continue;
      CodeAttribute code = mm.findAttribute(Attributes.code()).orElseThrow();
      Map<Integer, StackMapFrameInfo> byBci = new HashMap<>();
      code.findAttribute(Attributes.stackMapTable()).ifPresent(t -> t.entries().forEach(
          f -> byBci.put(code.labelToBci(f.target()), f)));
      Map<Integer, Integer> bciOfOrdinal = new HashMap<>();
      int bci = 0;
      int ordinal = 0;
      for (CodeElement e : code.elementList()) {
        if (e instanceof InvokeInstruction) bciOfOrdinal.put(ordinal++, bci);
        if (e instanceof Instruction ins) bci += ins.sizeInBytes();
      }
      for (Site s : p.sites) {
        StackMapFrameInfo f = byBci.get(bciOfOrdinal.get(s.ordinal()));
        if (f == null) throw new IllegalStateException("no frame at a call of " + key(mm));
        p.frames.put(s, f);
      }
    }
  }

  // ---- the rewrite

  /** how a method's body is written: as it was, as its resumption (a copy), as its twin */
  enum Mode { ORIGINAL, COPY, TWIN }

  /** A TWIN of each static method rewritten, called by transformed code alone: a capture leaves it by a return, not
   * a throw, which would cost the VM a search for the handler in each frame. Its caller reads one count after the
   * call and takes the capture if there is one of this thread's. Linked by {@code Resume.twin}, which falls back to
   * the method itself where it has no twin (a class built without the transformation); the method itself still
   * throws, for any caller */
  static final String TWIN = "$okay$t";

  static boolean twinned(MethodModel mm) {
    String name = mm.methodName().stringValue();
    return mm.flags().has(java.lang.reflect.AccessFlag.STATIC) && !name.startsWith("lambda$") && !name.startsWith("<");
  }

  private static int twinFlags(MethodModel mm) {
    int access = mm.flags().flagsMask() & (ClassFile.ACC_PUBLIC | ClassFile.ACC_PRIVATE | ClassFile.ACC_PROTECTED);
    return access | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC;
  }

  /** a call that may be linked to its callee's twin: a static one */
  private static boolean twins(Site s) {
    return s.invoke().opcode() == Opcode.INVOKESTATIC && !s.invoke().name().stringValue().startsWith("lambda$");
  }

  static final DirectMethodHandleDesc TWIN_BSM = MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.STATIC,
      CD_RESUME, "twin", MethodTypeDesc.of(ConstantDescs.CD_CallSite,
          ClassDesc.of("java.lang.invoke.MethodHandles$Lookup"), ConstantDescs.CD_String, ConstantDescs.CD_MethodType,
          ConstantDescs.CD_Class));

  /** a local or a spilled stack value: its slot and its type */
  record Slot(int slot, VerificationTypeInfo type) {}

  private final class Rewrite implements ClassTransform {
    final ClassModel cm;
    final Map<String, Plan> plans;
    final Map<String, Boolean> lambdas;
    final ClassDesc self;
    final boolean isInterface;
    boolean annotated;
    int next;

    Rewrite(ClassModel cm, Map<String, Plan> plans, Map<String, Boolean> lambdas) {
      this.cm = cm;
      this.plans = plans;
      this.lambdas = lambdas;
      this.self = cm.thisClass().asSymbol();
      this.isInterface = cm.flags().has(java.lang.reflect.AccessFlag.INTERFACE);
    }

    @Override
    public void accept(ClassBuilder clb, ClassElement ce) {
      if (ce instanceof RuntimeVisibleAnnotationsAttribute a && !plans.isEmpty()) {
        List<Annotation> all = new ArrayList<>(a.annotations());
        all.add(Annotation.of(CD_TRANSFORMED));
        clb.with(RuntimeVisibleAnnotationsAttribute.of(all));
        annotated = true;
        return;
      }
      if (!(ce instanceof MethodModel mm) || mm.findAttribute(Attributes.code()).isEmpty()) {
        clb.with(ce);
        return;
      }
      Plan p = plans.get(key(mm));
      if (p == null) {
        if (lambdas.isEmpty()) clb.with(ce);
        else clb.transformMethod(mm, MethodTransform.transformingCode((cb, e) -> {
          if (!(e instanceof InvokeDynamicInstruction indy && lambda(cb, indy))) cb.with(e);
        }));
        return;
      }
      p.number = next++;
      Map<Site, Frames> frames = new IdentityHashMap<>();
      int top = p.maxLocals;
      for (Site s : p.sites) {
        Frames f = new Frames(p, s);
        frames.put(s, f);
        top = Math.max(top, f.tempTop);
      }
      int temps = Math.max(top, 3); // above the copy's three parameters, which it moves there
      clb.withMethod(mm.methodName(), mm.methodType(), mm.flags().flagsMask(), mb -> {
        for (MethodElement me : mm) if (!(me instanceof CodeAttribute)) mb.with(me);
        mb.withCode(cb -> body(cb, p, frames, Mode.ORIGINAL, temps));
      });
    }

    @Override
    public void atEnd(ClassBuilder clb) {
      if (plans.isEmpty()) return;
      if (!annotated) clb.with(RuntimeVisibleAnnotationsAttribute.of(Annotation.of(CD_TRANSFORMED)));
      for (Plan p : plans.values()) {
        Map<Site, Frames> frames = new IdentityHashMap<>();
        int top = p.maxLocals;
        for (Site s : p.sites) {
          Frames f = new Frames(p, s);
          frames.put(s, f);
          top = Math.max(top, f.tempTop);
        }
        int temps = Math.max(top, 3); // above the copy's three parameters, which it moves there
        clb.withMethod(copyName(p), RESUME_TYPE,
            ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC,
            mb -> mb.withCode(cb -> body(cb, p, frames, Mode.COPY, temps)));
        if (twinned(p.method)) clb.withMethod(p.method.methodName().stringValue() + TWIN,
            p.method.methodTypeSymbol(), twinFlags(p.method),
            mb -> mb.withCode(cb -> body(cb, p, frames, Mode.TWIN, temps)));
      }
    }

    String copyName(Plan p) {
      return "$okay$r$" + p.number;
    }

    /** the method's body: as it was (copy false) or as its resumption (copy true) */
    void body(CodeBuilder cb, Plan p, Map<Site, Frames> frames, Mode mode, int temps) {
      boolean copy = mode == Mode.COPY;
      int thr = temps;
      int frame = temps + 1;
      int answer = temps + 2;
      int values = temps + 3;
      int prims = temps + 4;
      int held = temps + 5; // a call's value, while the slow path asks whether a twin left a capture
      Relabel r = new Relabel(cb);
      int n = p.sites.size();
      Label[] start = new Label[n];
      Label[] call = new Label[n];
      Label[] end = new Label[n];
      Label[] handler = new Label[n];
      Label[] slow = new Label[n];
      Label[] back = new Label[n];
      boolean[] checked = new boolean[n];
      for (int i = 0; i < n; i++) {
        slow[i] = cb.newLabel();
        back[i] = cb.newLabel();
        start[i] = cb.newLabel();
        call[i] = copy ? cb.newLabel() : start[i];
        end[i] = cb.newLabel();
        handler[i] = cb.newLabel();
        cb.exceptionCatch(call[i], end[i], handler[i], CD_CAPTURE); // first: before any handler of the method
      }
      if (copy) {
        cb.aload(0).astore(frame).aload(1).astore(answer).aload(2).astore(thr);
        Label bad = cb.newLabel();
        Label[] dispatch = new Label[n];
        List<SwitchCase> cases = new ArrayList<>();
        for (int i = 0; i < n; i++) cases.add(SwitchCase.of(i, dispatch[i] = cb.newLabel()));
        cb.aload(frame).invokevirtual(CD_FRAME, "site", MethodTypeDesc.of(ConstantDescs.CD_int));
        cb.tableswitch(0, n - 1, bad, cases);
        for (int i = 0; i < n; i++) {
          Site s = p.sites.get(i);
          Frames f = frames.get(s);
          cb.labelBinding(dispatch[i]);
          List<Slot> saved = f.saved();
          int nRefs = 0;
          int nPrims = 0;
          for (Slot sl : saved) if (prim(sl.type())) nPrims++; else nRefs++;
          if (nRefs > 0) cb.aload(frame).invokevirtual(CD_FRAME, "refs", MethodTypeDesc.of(CD_OBJECTS)).astore(values);
          if (nPrims > 0) cb.aload(frame).invokevirtual(CD_FRAME, "prims", MethodTypeDesc.of(CD_LONGS)).astore(prims);
          int ri = 0;
          int qi = 0;
          for (Slot sl : saved) {
            if (prim(sl.type())) {
              cb.aload(prims).loadConstant(qi++).laload();
              fromBits(cb, sl.type());
            } else {
              cb.aload(values).loadConstant(ri++).aaload();
              unbox(cb, sl.type());
            }
            store(cb, sl);
          }
          for (Slot sl : f.rest) load(cb, sl);
          Label unwinding = cb.newLabel();
          cb.aload(thr).ifnonnull(unwinding);
          ClassDesc ret = s.invoke().typeSymbol().returnType();
          if (!ret.equals(ConstantDescs.CD_void)) {
            cb.aload(answer);
            unboxTo(cb, ret);
          }
          cb.goto_(end[i]);
          cb.labelBinding(unwinding);
          for (VerificationTypeInfo a : f.args) zero(cb, a);
          cb.goto_(start[i]);
        }
        cb.labelBinding(bad).aload(frame)
            .invokestatic(CD_RESUME, "badSite", MethodTypeDesc.of(ClassDesc.of("java.lang.RuntimeException"), CD_FRAME))
            .athrow();
      }
      int s = 0;
      for (int i = 0; i < p.elements.size(); i++) {
        CodeElement e = p.elements.get(i);
        if (s < n && p.sites.get(s).index() == i) {
          Site site = p.sites.get(s);
          Frames f = frames.get(site);
          if (!f.rest.isEmpty()) {
            for (int j = f.args.size() - 1; j >= 0; j--) store(cb, f.argSlots.get(j));
            for (int j = f.rest.size() - 1; j >= 0; j--) store(cb, f.rest.get(j));
            for (Slot sl : f.rest) load(cb, sl);
            for (Slot sl : f.argSlots) load(cb, sl);
          }
          cb.labelBinding(start[s]);
          if (copy) {
            cb.aload(thr).ifnull(call[s]);
            for (int j = f.args.size() - 1; j >= 0; j--) {
              VerificationTypeInfo a = f.args.get(j);
              if (a == LONG || a == DOUBLE) cb.pop2();
              else cb.pop();
            }
            cb.aload(thr).aconst_null().astore(thr).athrow();
            cb.labelBinding(call[s]);
          }
          boolean twin = mode == Mode.TWIN;
          if (site.perform()) {
            // by the operation's type: a sensitive one never answered in place. A twin's returns its capture
            ClassDesc op = site.invoke().typeSymbol().parameterList().getFirst();
            boolean sensitive = op.equals(CD_SENSITIVE_OP);
            cb.invokestatic(CD_RESUME, (sensitive ? "performSensitive" : "perform") + (twin ? "Out" : ""),
                MethodTypeDesc.of(ConstantDescs.CD_Object, sensitive ? CD_SENSITIVE_OP : CD_OP));
          } else if (twins(site)) {
            twin = true;
            InvokeInstruction inv = site.invoke();
            Plan callee = inv.owner().asSymbol().equals(self) ? plans.get(inv.name().stringValue()
                + inv.type().stringValue()) : null;
            // a method of this class: its twin is known to be made, and a direct call costs no stack of a linkage
            if (callee != null && twinned(callee.method)) cb.invokestatic(self, inv.name().stringValue() + TWIN,
                inv.typeSymbol(), inv.isInterface());
            else cb.invokedynamic(DynamicCallSiteDesc.of(TWIN_BSM, inv.name().stringValue(), inv.typeSymbol(),
                inv.owner().asSymbol()));
          } else r.emit(e);
          cb.labelBinding(end[s]);
          // after a call that may return with a capture on its way: one plain read, the slow path out of line
          if (twin) cb.getstatic(CD_RESUME, "going", ConstantDescs.CD_int).ifne(slow[s]).labelBinding(back[s]);
          checked[s] = twin;
          s++;
          continue;
        }
        if (copy && r.debug(e)) continue;
        if (copy && e instanceof ReturnInstruction ret) {
          boxReturn(cb, ret.typeKind(), p.method.methodTypeSymbol().returnType());
          continue;
        }
        if (e instanceof InvokeDynamicInstruction indy && lambda(cb, indy)) continue;
        r.emit(e);
      }
      for (int i = 0; i < n; i++) {
        Site site = p.sites.get(i);
        List<Slot> saved = frames.get(site).saved();
        cb.labelBinding(handler[i]);
        cb.ldc(resumer(p));
        cb.loadConstant(i);
        List<Slot> refs = saved.stream().filter(sl -> !prim(sl.type())).toList();
        List<Slot> bits = saved.stream().filter(sl -> prim(sl.type())).toList();
        if (refs.isEmpty()) cb.aconst_null();
        else {
          cb.loadConstant(refs.size()).anewarray(ConstantDescs.CD_Object);
          for (int j = 0; j < refs.size(); j++) {
            cb.dup().loadConstant(j);
            load(cb, refs.get(j));
            cb.aastore();
          }
        }
        if (bits.isEmpty()) cb.aconst_null();
        else {
          cb.loadConstant(bits.size()).newarray(TypeKind.LONG);
          for (int j = 0; j < bits.size(); j++) {
            cb.dup().loadConstant(j);
            load(cb, bits.get(j));
            toBits(cb, bits.get(j).type());
            cb.lastore();
          }
        }
        cb.invokestatic(CD_RESUME, "push", MethodTypeDesc.of(CD_CAPTURE, CD_CAPTURE, CD_RESUMER,
            ConstantDescs.CD_int, CD_OBJECTS, CD_LONGS));
        switch (mode) {
          // called by transformed code only, a twin returns, its capture left for the caller to take
          case TWIN -> {
            cb.invokestatic(CD_RESUME, "out", MethodTypeDesc.of(ConstantDescs.CD_void, CD_CAPTURE));
            TypeKind kind = TypeKind.from(p.method.methodTypeSymbol().returnType());
            if (kind != TypeKind.VOID) zero(cb, kind);
            cb.return_(kind);
          }
          // a resumed copy is called by the boundary alone: it returns the capture there, no throw to cross frames
          case COPY -> cb.invokestatic(CD_RESUME, "in", MethodTypeDesc.of(CD_CAPTURE, CD_CAPTURE)).areturn();
          case ORIGINAL -> cb.invokestatic(CD_RESUME, "in", MethodTypeDesc.of(CD_CAPTURE, CD_CAPTURE)).athrow();
        }
      }
      for (int i = 0; i < n; i++) {
        if (!checked[i]) continue;
        // a capture may be on its way: this thread's, to its handler; another thread's, the call's value goes on
        TypeKind kind = TypeKind.from(p.sites.get(i).invoke().typeSymbol().returnType());
        // the stack under the call is in its spilled locals too: dropped for the handler, loaded back to go on
        List<Slot> rest = frames.get(p.sites.get(i)).rest;
        cb.labelBinding(slow[i]);
        if (kind != TypeKind.VOID) cb.storeLocal(kind, held);
        for (int j = rest.size() - 1; j >= 0; j--) {
          VerificationTypeInfo t = rest.get(j).type();
          if (t == LONG || t == DOUBLE) cb.pop2();
          else cb.pop();
        }
        cb.invokestatic(CD_RESUME, "taken", MethodTypeDesc.of(CD_CAPTURE)).dup().ifnonnull(handler[i]).pop();
        for (Slot sl : rest) load(cb, sl);
        if (kind != TypeKind.VOID) cb.loadLocal(kind, held);
        cb.goto_(back[i]);
      }
    }

    DynamicConstantDesc<?> resumer(Plan p) {
      var bsm = ConstantDescs.ofConstantBootstrap(CD_RESUME, "resumer", CD_RESUMER,
          ConstantDescs.CD_MethodHandle, ConstantDescs.CD_String, ConstantDescs.CD_String, ConstantDescs.CD_String,
          ConstantDescs.CD_String);
      var resume = MethodHandleDesc.ofMethod(isInterface ? DirectMethodHandleDesc.Kind.INTERFACE_STATIC
          : DirectMethodHandleDesc.Kind.STATIC, self, copyName(p), RESUME_TYPE);
      StringBuilder sites = new StringBuilder();
      for (Site s : p.sites) {
        char kind = s.perform() ? 'P' : switch (s.invoke().opcode()) {
          case INVOKESTATIC -> 'S';
          case INVOKEINTERFACE -> 'I';
          case INVOKESPECIAL -> 'X';
          default -> 'V';
        };
        String name = s.perform() ? "perform" : s.invoke().name().stringValue();
        sites.append(kind).append('|').append(name).append('|').append(s.invoke().type().stringValue())
            .append('|').append(s.covered() ? 1 : 0).append('|').append(s.line())
            .append('|').append(!s.covered() && tail(p.elements, s.index()) ? 1 : 0).append('\n');
      }
      return DynamicConstantDesc.ofNamed(bsm, "resumer", CD_RESUMER, resume, cm.thisClass().asInternalName(),
          p.method.methodName().stringValue(), p.method.methodType().stringValue(), sites.toString());
    }

    /** a lambda of a method that may suspend, linked through Resume */
    boolean lambda(CodeBuilder cb, InvokeDynamicInstruction indy) {
      Boolean alt = lambdas.get(lambdaKey(indy));
      if (alt == null) return false;
      ClassDesc lookup = ClassDesc.of("java.lang.invoke.MethodHandles$Lookup");
      ClassDesc mt = ConstantDescs.CD_MethodType;
      MethodTypeDesc type = alt
          ? MethodTypeDesc.of(ConstantDescs.CD_CallSite, lookup, ConstantDescs.CD_String, mt,
              ConstantDescs.CD_Object.arrayType())
          : MethodTypeDesc.of(ConstantDescs.CD_CallSite, lookup, ConstantDescs.CD_String, mt, mt,
              ConstantDescs.CD_MethodHandle, mt);
      var bsm = MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.STATIC, CD_RESUME,
          alt ? "altLambda" : "lambda", type);
      cb.invokedynamic(DynamicCallSiteDesc.of(bsm, indy.name().stringValue(), indy.typeSymbol(),
          indy.bootstrapArgs().toArray(new ConstantDesc[0])));
      return true;
    }
  }

  /** the slots of one call: the locals the probe found typed, the stack under the call's arguments spilled to fresh
   * locals from the method's last one, and the arguments' types */
  private static final class Frames {
    final List<Slot> locals = new ArrayList<>();
    final List<Slot> rest = new ArrayList<>();
    final List<VerificationTypeInfo> args;
    final List<Slot> argSlots = new ArrayList<>();
    final int tempTop;

    Frames(Plan p, Site s) {
      StackMapFrameInfo f = p.frames.get(s);
      int slot = 0;
      for (VerificationTypeInfo t : f.locals()) {
        check(p, s, t);
        if (t != TOP) locals.add(new Slot(slot, t));
        slot += t == LONG || t == DOUBLE ? 2 : 1;
      }
      List<VerificationTypeInfo> stack = f.stack();
      int nArgs = s.invoke().typeSymbol().parameterCount() + (s.invoke().opcode() == Opcode.INVOKESTATIC ? 0 : 1);
      args = stack.subList(stack.size() - nArgs, stack.size());
      int temp = p.maxLocals;
      for (VerificationTypeInfo t : stack.subList(0, stack.size() - nArgs)) {
        check(p, s, t);
        rest.add(new Slot(temp, t));
        temp += t == LONG || t == DOUBLE ? 2 : 1;
      }
      for (VerificationTypeInfo t : args) {
        argSlots.add(new Slot(temp, t));
        temp += t == LONG || t == DOUBLE ? 2 : 1;
      }
      tempTop = temp;
    }

    List<Slot> saved() {
      List<Slot> all = new ArrayList<>(locals);
      all.addAll(rest);
      return all;
    }

    private static void check(Plan p, Site s, VerificationTypeInfo t) {
      if (t instanceof StackMapFrameInfo.UninitializedVerificationTypeInfo
          || t == StackMapFrameInfo.SimpleVerificationTypeInfo.UNINITIALIZED_THIS)
        throw new Refused(p.method.parent().map(c -> c.thisClass().asInternalName().replace('/', '.')).orElse("?")
            + "." + p.method.methodName().stringValue() + " (line " + s.line() + "): a call that may suspend is an "
            + "argument of a constructor (new X(.., call(), ..)): its object cannot be saved before it is made. "
            + "Assign the call's value to a local first.");
    }
  }

  // ---- loading, storing, boxing by verification type

  private static void load(CodeBuilder cb, Slot s) {
    VerificationTypeInfo t = s.type();
    if (t == INTEGER) cb.iload(s.slot());
    else if (t == LONG) cb.lload(s.slot());
    else if (t == FLOAT) cb.fload(s.slot());
    else if (t == DOUBLE) cb.dload(s.slot());
    else cb.aload(s.slot());
  }

  private static void store(CodeBuilder cb, Slot s) {
    VerificationTypeInfo t = s.type();
    if (t == INTEGER) cb.istore(s.slot());
    else if (t == LONG) cb.lstore(s.slot());
    else if (t == FLOAT) cb.fstore(s.slot());
    else if (t == DOUBLE) cb.dstore(s.slot());
    else cb.astore(s.slot());
  }

  private static void zero(CodeBuilder cb, TypeKind k) {
    switch (k) {
      case LONG -> cb.lconst_0();
      case FLOAT -> cb.fconst_0();
      case DOUBLE -> cb.dconst_0();
      case REFERENCE -> cb.aconst_null();
      default -> cb.iconst_0();
    }
  }

  private static void zero(CodeBuilder cb, VerificationTypeInfo t) {
    if (t == INTEGER) cb.iconst_0();
    else if (t == LONG) cb.lconst_0();
    else if (t == FLOAT) cb.fconst_0();
    else if (t == DOUBLE) cb.dconst_0();
    else cb.aconst_null();
  }

  /** a primitive local, saved as bits in the frame's long[] instead of boxed */
  private static boolean prim(VerificationTypeInfo t) {
    return t == INTEGER || t == LONG || t == FLOAT || t == DOUBLE;
  }

  /** the primitive on the stack as a long of its bits */
  private static void toBits(CodeBuilder cb, VerificationTypeInfo t) {
    if (t == INTEGER) cb.i2l();
    else if (t == FLOAT) cb.invokestatic(ConstantDescs.CD_Float, "floatToRawIntBits",
        MethodTypeDesc.of(ConstantDescs.CD_int, ConstantDescs.CD_float)).i2l();
    else if (t == DOUBLE) cb.invokestatic(ConstantDescs.CD_Double, "doubleToRawLongBits",
        MethodTypeDesc.of(ConstantDescs.CD_long, ConstantDescs.CD_double));
  }

  /** the long of bits on the stack as the primitive it was */
  private static void fromBits(CodeBuilder cb, VerificationTypeInfo t) {
    if (t == INTEGER) cb.l2i();
    else if (t == FLOAT) cb.l2i().invokestatic(ConstantDescs.CD_Float, "intBitsToFloat",
        MethodTypeDesc.of(ConstantDescs.CD_float, ConstantDescs.CD_int));
    else if (t == DOUBLE) cb.invokestatic(ConstantDescs.CD_Double, "longBitsToDouble",
        MethodTypeDesc.of(ConstantDescs.CD_double, ConstantDescs.CD_long));
  }

  private static void valueOf(CodeBuilder cb, ClassDesc box, ClassDesc prim) {
    cb.invokestatic(box, "valueOf", MethodTypeDesc.of(box, prim));
  }

  /** a saved reference made the type of its local again */
  private static void unbox(CodeBuilder cb, VerificationTypeInfo t) {
    if (t == NULL) cb.pop().aconst_null();
    else if (t instanceof ObjectVerificationTypeInfo o && !o.classSymbol().equals(ConstantDescs.CD_Object))
      cb.checkcast(o.classSymbol());
  }

  /** an Object on the stack made a value of {@code type}; a number of any box for a numeric primitive, as a lambda's
   * method may return a narrower one than its interface */
  private static void unboxTo(CodeBuilder cb, ClassDesc type) {
    switch (type.descriptorString()) {
      case "Z" -> cb.checkcast(ConstantDescs.CD_Boolean)
          .invokevirtual(ConstantDescs.CD_Boolean, "booleanValue", MethodTypeDesc.of(ConstantDescs.CD_boolean));
      case "C" -> cb.checkcast(ConstantDescs.CD_Character)
          .invokevirtual(ConstantDescs.CD_Character, "charValue", MethodTypeDesc.of(ConstantDescs.CD_char));
      case "B", "S", "I" -> number(cb, "intValue", ConstantDescs.CD_int);
      case "J" -> number(cb, "longValue", ConstantDescs.CD_long);
      case "F" -> number(cb, "floatValue", ConstantDescs.CD_float);
      case "D" -> number(cb, "doubleValue", ConstantDescs.CD_double);
      default -> {
        if (!type.equals(ConstantDescs.CD_Object)) cb.checkcast(type);
      }
    }
  }

  private static void number(CodeBuilder cb, String method, ClassDesc prim) {
    ClassDesc number = ClassDesc.of("java.lang.Number");
    cb.checkcast(number).invokevirtual(number, method, MethodTypeDesc.of(prim));
  }

  /** a return of the copy: the value boxed by the method's return type, null for void */
  private static void boxReturn(CodeBuilder cb, TypeKind kind, ClassDesc type) {
    switch (type.descriptorString()) {
      case "V" -> cb.aconst_null();
      case "Z" -> valueOf(cb, ConstantDescs.CD_Boolean, ConstantDescs.CD_boolean);
      case "C" -> valueOf(cb, ConstantDescs.CD_Character, ConstantDescs.CD_char);
      case "B" -> valueOf(cb, ConstantDescs.CD_Byte, ConstantDescs.CD_byte);
      case "S" -> valueOf(cb, ConstantDescs.CD_Short, ConstantDescs.CD_short);
      case "I" -> valueOf(cb, ConstantDescs.CD_Integer, ConstantDescs.CD_int);
      case "J" -> valueOf(cb, ConstantDescs.CD_Long, ConstantDescs.CD_long);
      case "F" -> valueOf(cb, ConstantDescs.CD_Float, ConstantDescs.CD_float);
      case "D" -> valueOf(cb, ConstantDescs.CD_Double, ConstantDescs.CD_double);
      default -> {
        if (kind != TypeKind.REFERENCE) throw new IllegalStateException("return of " + kind + " for " + type);
      }
    }
    cb.areturn();
  }

  /** the elements of a method emitted into another builder, each label of the old code mapped to a new one */
  private static final class Relabel {
    final CodeBuilder cb;
    final Map<Label, Label> labels = new HashMap<>();

    Relabel(CodeBuilder cb) {
      this.cb = cb;
    }

    Label map(Label l) {
      return labels.computeIfAbsent(l, k -> cb.newLabel());
    }

    /** debug information a copy leaves out */
    boolean debug(CodeElement e) {
      return e instanceof LocalVariable || e instanceof LocalVariableType || e instanceof Attribute<?>
          || e instanceof java.lang.classfile.instruction.CharacterRange;
    }

    void emit(CodeElement e) {
      switch (e) {
        case LabelTarget t -> cb.labelBinding(map(t.label()));
        case BranchInstruction b -> cb.branch(b.opcode(), map(b.target()));
        case TableSwitchInstruction t -> cb.tableswitch(t.lowValue(), t.highValue(), map(t.defaultTarget()),
            t.cases().stream().map(c -> SwitchCase.of(c.caseValue(), map(c.target()))).toList());
        case LookupSwitchInstruction t -> cb.lookupswitch(map(t.defaultTarget()),
            t.cases().stream().map(c -> SwitchCase.of(c.caseValue(), map(c.target()))).toList());
        case ExceptionCatch c -> cb.exceptionCatch(map(c.tryStart()), map(c.tryEnd()), map(c.handler()),
            c.catchType());
        case LocalVariable v -> cb.localVariable(v.slot(), v.name(), v.type(), map(v.startScope()),
            map(v.endScope()));
        case LocalVariableType v -> cb.localVariableType(v.slot(), v.name(), v.signature(), map(v.startScope()),
            map(v.endScope()));
        case Attribute<?> a -> { } // a code attribute (stack maps are made anew; type annotations are dropped)
        case java.lang.classfile.instruction.CharacterRange c -> { }
        default -> cb.with(e);
      }
    }
  }
}
