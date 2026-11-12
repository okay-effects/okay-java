package okay.resumable;

/** Code built WITHOUT the transformation: the build copies this class as javac wrote it, as a jar compiled without
 * the plugin would be */
public final class Untransformed {
  private Untransformed() {}

  /** performs, not transformed */
  public static int performs() throws Counter {
    return Direct.perform(new Counter.Get());
  }

  /** reads an input, not transformed itself */
  public static int input() throws Input {
    return Input.next();
  }

  /** calls a transformed method, not transformed itself: its frame would be lost */
  public static int middle() throws Counter {
    return Counter.get() + 1;
  }
}
