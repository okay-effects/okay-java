package okay.resumable.transform;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import okay.audit.Classes;
import okay.audit.Code;
import okay.audit.Hit;
import okay.audit.Presets;
import okay.audit.Site;

/** THE FORBIDDEN APIS OF EFFECTFUL CODE, CHECKED BY okay-audit: the rules, the presets named after the standard
 * effects and the effect each names instead, the matching up a class's superclasses and interfaces, and the state rule
 * are okay-audit's ({@code okay.audit.Presets}, {@code okay.audit.Code}); effectful code — a method this
 * transformation rewrites — is what okay-audit calls business code, held to them method by method. This class only
 * words what okay-audit finds as this transformation's refusals always read: the method, the line, what it does, the
 * entry that forbids it, the effect to use instead. Loaded only when a list is named, so okay-audit (and the Scala
 * library under it) is needed on the transformer's class path then, and never by a program at run time. */
final class Audited implements Transformer.Forbid {
  private static final String WORLD = ": effectful code reaches the world by its effects, whose handlers answer them";

  private final String list;
  private final Classes classes;

  private Audited(String list, Classes classes) {
    this.list = list;
    this.classes = classes;
  }

  static Transformer.Forbid of(String list, Function<String, byte[]> bytes) {
    // every entry resolved now: a build's typo or an unknown preset reads as an entry of its own, as before
    Presets.rules(list);
    return new Audited(list, Classes.byInternalName(bytes::apply));
  }

  @Override
  public void check(byte[] bytes, Set<String> methods, List<String> refused, List<String> said) {
    for (Hit h : Code.check(bytes, methods, list, classes)) {
      String api = h.rule().api();
      String use = h.rule().use().isEmpty() ? "" : " — use " + h.rule().use() + " instead";
      if (api.equals("state")) {
        String what = h.site().op() == Site.GetField() || h.site().op() == Site.PutField()
            ? ", a field of a Scala object" : ", a static field that is not final";
        if (h.said()) said.add(h.where() + what + " (state" + use
            + "; said, not refused: a static field nothing writes may be a constant)");
        else refused.add(h.where() + what + ", forbidden here by state" + use + WORLD);
      } else refused.add(h.where() + ", forbidden here by " + spelled(api) + use + WORLD);
    }
  }

  /** an okay-audit rule as this module's lists spell it: {@code java.net.} is {@code java.net.*},
   * {@code java.lang.System#exit} is {@code java.lang.System.exit} */
  static String spelled(String api) {
    String a = api.replace('#', '.');
    return a.endsWith(".") ? a + "*" : a;
  }
}
