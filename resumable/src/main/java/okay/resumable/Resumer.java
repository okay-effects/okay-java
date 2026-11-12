package okay.resumable;

import java.lang.invoke.MethodHandle;

/** What the transformation knows of one transformed method: the method that resumes its saved frames, and its calls
 * that may suspend. Made once, by a constant of the method's class ({@link Resume#resumer}). For transformed code
 * only. */
public final class Resumer {
  /** a call that may suspend: how it invokes, what, whether a handler of the method covers it (a finally, a catch),
   * its line */
  record Site(char kind, String name, String desc, boolean covered, int line) {}

  /** (Frame, Object answer, Throwable thrown) Object: the method re-entered at the frame's call */
  final MethodHandle resume;
  /** the class, internal name */
  final String owner;
  final String method;
  final String desc;
  final Site[] sites;

  Resumer(MethodHandle resume, String owner, String method, String desc, Site[] sites) {
    this.resume = resume;
    this.owner = owner;
    this.method = method;
    this.desc = desc;
    this.sites = sites;
  }

  String key() { return owner + "." + method + desc; }

  String display() { return owner.replace('/', '.') + "." + method; }

  String where(int site) {
    Site s = sites[site];
    return display() + " (line " + s.line() + ", calling " + s.name() + ")";
  }
}
