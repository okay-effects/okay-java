package okay.resumable;

import static okay.rows.Effects.run;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** A missing transformation is refused when the program runs, never a silent loss of the rest of the program */
class RefusalTest {
  @Test
  void performUnderACallerThatWasNotTransformedNamesIt() {
    var e = assertThrows(IllegalStateException.class,
        () -> run(Counter.run(0, Direct.row(Counter.class).of(Untransformed::performs))));
    System.out.println(e.getMessage());
    assertTrue(e.getMessage().startsWith("okay.resumable: okay.resumable.Untransformed.performs (line 10) performed "
        + "Get, but its class was not transformed"), e.getMessage());
  }

  @Test
  void aFrameLostBetweenTwoTransformedOnesIsRefused() {
    var e = assertThrows(IllegalStateException.class,
        () -> run(Counter.run(0,
            Counter.watched(Direct.row(Counter.class).of(() -> Untransformed.middle() * 2)))));
    System.out.println(e.getMessage());
    assertTrue(e.getMessage().startsWith("okay.resumable: a frame was lost between okay.resumable.RefusalTest."
        + "lambda$aFrameLostBetweenTwoTransformedOnesIsRefused$"), e.getMessage());
    assertTrue(e.getMessage().contains("calling middle) and okay.resumable.Counter.get"), e.getMessage());
  }
}
