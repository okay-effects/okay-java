package okay.resumable.transform;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** The transformation of a directory of classes, as a build runs it:
 * {@code Main <classes> <output> <classpath>} — every class of {@code classes} written to {@code output}, rewritten
 * where it may suspend, as it was otherwise; {@code classpath} (the path separator between entries) is what its
 * calls are resolved against. {@code output} may be {@code classes}: rewritten in place, the rest untouched. A
 * refusal fails the build with what to change. */
public final class Main {
  private Main() {}

  public static void main(String[] args) throws IOException {
    Path in = Path.of(args[0]);
    int changed = transform(in, Path.of(args[1]), args.length > 2 ? args[2] : "");
    System.out.println("okay.resumable: " + changed + " class(es) made resumable in " + in);
  }

  /** the classes of {@code in} written to {@code out}, rewritten where they may suspend; how many were. Refused:
   * an {@code IllegalStateException} after the reasons are printed on stderr */
  public static int transform(Path in, Path out, String classpath) throws IOException {
    List<URL> urls = new ArrayList<>();
    urls.add(in.toUri().toURL());
    for (String p : classpath.split(File.pathSeparator)) if (!p.isBlank()) urls.add(Path.of(p).toUri().toURL());
    int changed = 0;
    List<String> refused = new ArrayList<>();
    try (URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
        Stream<Path> files = Files.walk(in)) {
      Transformer t = new Transformer(name -> read(loader, name));
      for (Path f : files.filter(Files::isRegularFile).toList()) {
        Path target = out.resolve(in.relativize(f).toString());
        Files.createDirectories(target.getParent());
        byte[] bytes = Files.readAllBytes(f);
        byte[] result = null;
        if (f.toString().endsWith(".class")) {
          try {
            result = t.transform(bytes);
          } catch (Transformer.Refused e) {
            refused.add(e.getMessage());
          }
        }
        if (result != null) changed++;
        if (result != null || !in.equals(out)) Files.write(target, result == null ? bytes : result);
      }
    }
    if (!refused.isEmpty()) {
      refused.forEach(System.err::println);
      throw new IllegalStateException("okay.resumable: " + refused.size() + " method(s) cannot be made resumable");
    }
    return changed;
  }

  static byte[] read(ClassLoader loader, String name) {
    try (InputStream s = loader.getResourceAsStream(name + ".class")) {
      return s == null ? null : s.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
