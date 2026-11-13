package okay.resumable;

import static okay.rows.Effects.run;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/** run with no flag but the trace: a handler declared Once is asked from the body's stack all the same — answered
 * there when it resumes at once; when it does not, the frames are saved, and the reason is said once */
public final class TiersMain {
  private TiersMain() {}

  static int sumOfThree() throws Input {
    return Input.next() + Input.next() + Input.next();
  }

  public static void main(String[] args) {
    System.out.println(Direct.continuations());
    System.out.println(TiersTest.taken(() -> System.out.println(run(Input.once(IntStream.iterate(1, i -> i + 1)
        .iterator(), Direct.row(Input.class).of(TiersMain::sumOfThree))))));
    List<String> log = new ArrayList<>();
    System.out.println(TiersTest.taken(() -> System.out.println(run(Fail.runOnce(Direct.row(Fail.class).of(() ->
        DirectTest.outerFinally(log)))))));
    System.out.println(log);
  }
}
