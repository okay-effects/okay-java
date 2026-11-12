package okay.resumable;

import static okay.rows.Effects.run;

import java.util.stream.IntStream;

/** run with no flag: the handler declared Once takes saved frames, and the reason is said once */
public final class TiersMain {
  private TiersMain() {}

  static int sumOfThree() throws Input {
    return Input.next() + Input.next() + Input.next();
  }

  public static void main(String[] args) {
    System.out.println(Direct.continuations());
    System.out.println(run(Input.once(IntStream.iterate(1, i -> i + 1).iterator(),
        Direct.row(Input.class).of(TiersMain::sumOfThree))));
  }
}
