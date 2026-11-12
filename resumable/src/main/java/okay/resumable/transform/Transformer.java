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
 * of {@code okay.resumable.Suspend} — is rewritten so that its frame can be saved as data and re-entered, after
 * "continuations from generalized stack inspection" (Pettyjohn et al., ICFP 2005):
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
  static final ClassDesc CD_RESUME = ClassDesc.of("okay.resumable.Resume");
  static final ClassDesc CD_CAPTURE = ClassDesc.of("okay.resumable.Capture");
  static final ClassDesc CD_FRAME = ClassDesc.of("okay.resumable.Frame");
  static final ClassDesc CD_RESUMER = ClassDesc.of("okay.resumable.Resumer");
  static final ClassDesc CD_TRANSFORMED = ClassDesc.of("okay.resumable.Transformed");
  static final ClassDesc CD_OP = ClassDesc.of("okay.rows.Op");
  static final MethodTypeDesc RESUME_TYPE =
      MethodTypeDesc.of(ConstantDescs.CD_Object, CD_FRAME, ConstantDescs.CD_Object, ConstantDescs.CD_Throwable);

  private final Function<String, byte[]> bytes;
  private final ClassFile cf;
  private final Map<String, ClassModel> models = new HashMap<>();
  private final Map<String, Boolean> suspendTypes = new HashMap<>();
  private final Map<String, Boolean> suspending = new HashMap<>();

  /** {@code bytes}: a class file by its internal name, or null — the classes being transformed, their classpath, and
   * the JDK */
  public Transformer(Function<String, byte[]> bytes) {
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
    if (name.equals(SUSPEND)) return true;
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

  // ---- the probe: the JDK's stack-map generator tells the types at each call

  private void probe(ClassModel cm, Map<String, Plan> plans) {
    byte[] probed = cf.transformClass(cm, (clb, ce) -> {
      Plan p = ce instanceof MethodModel mm ? plans.get(key(mm)) : null;
      if (p == null) {
        clb.with(ce);
        return;
      }
      clb.withMethod(p.method.methodName(), p.method.methodType(), p.method.flags().flagsMask(), mb -> mb.withCode(cb -> {
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
        mb.withCode(cb -> body(cb, p, frames, false, temps));
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
            mb -> mb.withCode(cb -> body(cb, p, frames, true, temps)));
      }
    }

    String copyName(Plan p) {
      return "$okay$r$" + p.number;
    }

    /** the method's body: as it was (copy false) or as its resumption (copy true) */
    void body(CodeBuilder cb, Plan p, Map<Site, Frames> frames, boolean copy, int temps) {
      int thr = temps;
      int frame = temps + 1;
      int answer = temps + 2;
      int values = temps + 3;
      Relabel r = new Relabel(cb);
      int n = p.sites.size();
      Label[] start = new Label[n];
      Label[] call = new Label[n];
      Label[] end = new Label[n];
      Label[] handler = new Label[n];
      for (int i = 0; i < n; i++) {
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
          cb.aload(frame).invokevirtual(CD_FRAME, "values", MethodTypeDesc.of(ConstantDescs.CD_Object.arrayType()))
              .astore(values);
          List<Slot> saved = f.saved();
          for (int j = 0; j < saved.size(); j++) {
            Slot sl = saved.get(j);
            cb.aload(values).loadConstant(j).aaload();
            unbox(cb, sl.type());
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
          if (site.perform()) cb.invokestatic(CD_RESUME, "perform",
              MethodTypeDesc.of(ConstantDescs.CD_Object, CD_OP));
          else r.emit(e);
          cb.labelBinding(end[s]);
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
        cb.loadConstant(saved.size()).anewarray(ConstantDescs.CD_Object);
        for (int j = 0; j < saved.size(); j++) {
          cb.dup().loadConstant(j);
          load(cb, saved.get(j));
          box(cb, saved.get(j).type());
          cb.aastore();
        }
        cb.invokestatic(CD_RESUME, "push", MethodTypeDesc.of(CD_CAPTURE, CD_CAPTURE, CD_RESUMER,
            ConstantDescs.CD_int, ConstantDescs.CD_Object.arrayType())).athrow();
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
            .append('|').append(s.covered() ? 1 : 0).append('|').append(s.line()).append('\n');
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

  private static void zero(CodeBuilder cb, VerificationTypeInfo t) {
    if (t == INTEGER) cb.iconst_0();
    else if (t == LONG) cb.lconst_0();
    else if (t == FLOAT) cb.fconst_0();
    else if (t == DOUBLE) cb.dconst_0();
    else cb.aconst_null();
  }

  private static void box(CodeBuilder cb, VerificationTypeInfo t) {
    if (t == INTEGER) valueOf(cb, ConstantDescs.CD_Integer, ConstantDescs.CD_int);
    else if (t == LONG) valueOf(cb, ConstantDescs.CD_Long, ConstantDescs.CD_long);
    else if (t == FLOAT) valueOf(cb, ConstantDescs.CD_Float, ConstantDescs.CD_float);
    else if (t == DOUBLE) valueOf(cb, ConstantDescs.CD_Double, ConstantDescs.CD_double);
  }

  private static void valueOf(CodeBuilder cb, ClassDesc box, ClassDesc prim) {
    cb.invokestatic(box, "valueOf", MethodTypeDesc.of(box, prim));
  }

  private static void unbox(CodeBuilder cb, VerificationTypeInfo t) {
    if (t == INTEGER) unboxTo(cb, ConstantDescs.CD_int);
    else if (t == LONG) unboxTo(cb, ConstantDescs.CD_long);
    else if (t == FLOAT) unboxTo(cb, ConstantDescs.CD_float);
    else if (t == DOUBLE) unboxTo(cb, ConstantDescs.CD_double);
    else if (t == NULL) cb.pop().aconst_null();
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
