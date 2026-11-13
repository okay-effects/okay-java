package okay.resumable.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/** Which calls the transformation takes to be in tail position, on code as javac writes it: a method named
 * {@code tail*} has every call to {@code call} in tail position, one named {@code not*} none */
class TailTest {
  static final String FIXTURE = """
      import java.util.List;
      class Fixture {
        static Object call(int x) { return x; }
        static void run() {}

        static Object tailDirect() { return call(1); }
        static Object tailLocal() { var r = call(1); return r; }
        static String tailCastOfALocal() { Object r = call(1); return (String) r; }
        static Object tailTernary(boolean b) { return b ? call(1) : call(2); }
        static Object tailSwitchsLocal(int i) {
          Object r = switch (i) {
            case 0 -> call(0);
            case 1 -> { int j = i + 1; yield call(j); }
            default -> call(2);
          };
          return r;
        }
        static int tailUnboxedLocal() { int r = (Integer) call(1); return r; }
        static Integer tailUnboxedAndBoxed() { int r = (Integer) call(1); return r; }
        static Object tailMovedToAnotherLocal() { var r = call(1); var s = r; return s; }
        static void tailVoid() { run(); }

        static Object notReadForAnythingElse(List<Object> l) { var r = call(1); l.add(r); return r; }
        static Object notTheLocalReturned() { Object r = call(1); Object s = "x"; return s; }
        static Object notAdded() { return (Integer) call(1) + 1; }
        static Object notTested(boolean b) { var r = call(1); if (b) return r; return null; }
        static Object notDropped() { call(1); return null; }
        static Object notTwiceAfterwards(List<Object> l) { var r = call(1); return l.add(r) ? r : null; }
      }
      """;

  @Test
  void callsInTailPositionAndNot() throws IOException {
    Path dir = Files.createTempDirectory("tail");
    Path src = dir.resolve("Fixture.java");
    Files.writeString(src, FIXTURE);
    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", dir.toString(), src.toString()));
    var cm = ClassFile.of().parse(dir.resolve("Fixture.class"));
    int checked = 0;
    for (MethodModel mm : cm.methods()) {
      String name = mm.methodName().stringValue();
      if (!name.startsWith("tail") && !name.startsWith("not")) continue;
      List<CodeElement> elements = mm.findAttribute(Attributes.code()).orElseThrow().elementList();
      int calls = 0;
      for (int i = 0; i < elements.size(); i++) {
        if (!(elements.get(i) instanceof InvokeInstruction inv)) continue;
        String callee = inv.name().stringValue();
        if (!callee.equals("call") && !callee.equals("run")) continue;
        calls++;
        if (name.startsWith("tail")) assertTrue(Transformer.tail(elements, i), name);
        else assertFalse(Transformer.tail(elements, i), name);
      }
      assertTrue(calls > 0, name);
      checked++;
    }
    assertEquals(15, checked);
  }
}
