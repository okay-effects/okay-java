package okay.rows;

/** A general {@link Handler} that declares it resumes the rest of the program at most once: resumed, unwound, or
 * neither — never twice. The machine does not check it here; it lets direct-style code (okay-java-resumable) run
 * the rest on the JVM's own one-shot continuation instead of saving its frames as data. A handler that declares it
 * and resumes twice is refused there. */
public interface Once {}
