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
  /** for each call, the callee last found to be the method it calls: the names are compared once for it. Written
   * by any thread: a stale entry only costs a comparison */
  final Resumer[] callees;
  /** whether the method was found to be entered by the boundary, as a body is */
  boolean entered;

  Resumer(MethodHandle resume, String owner, String method, String desc, Site[] sites) {
    this.callees = new Resumer[sites.length];
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
