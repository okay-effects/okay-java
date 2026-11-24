package okay.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okay.core.Comp;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;

/** {@code @Async} methods that return programs, in an application not of the web */
@SpringBootTest(classes = AsyncEffTest.App.class, properties = "spring.main.web-application-type=none")
class AsyncEffTest {

  @SpringBootApplication
  @EnableAsync
  @Import(Work.class)
  static class App {}

  /** state in the target's fields, reached by its methods: a field of the proxy is not the target's */
  static class Work {
    private final CountDownLatch go = new CountDownLatch(1);
    private volatile String ranOn;

    public void release() {
      go.countDown();
    }

    public String ranOn() {
      return ranOn;
    }

    /** waits for the test to let it go: the call has returned by then */
    @Async
    public Comp<String, Comp.Top> slow() throws InterruptedException {
      assertTrue(go.await(10, TimeUnit.SECONDS));
      ranOn = Thread.currentThread().getName();
      return Comp.sync(() -> Thread.currentThread().getName());
    }

    @Async
    public Comp<String, Comp.Top> failing() {
      return Comp.failed(new IllegalStateException("broken"));
    }

    @Async
    public Comp<String, Comp.Top> throwing() {
      throw new IllegalArgumentException("before the program");
    }

    /** not a program: Spring's own @Async, as before */
    @Async
    public CompletableFuture<String> future() {
      return CompletableFuture.completedFuture(Thread.currentThread().getName());
    }
  }

  @Autowired
  Work work;

  @Test
  void theCallReturnsAtOnceTheMethodAndItsProgramOnTheExecutor() throws Exception {
    Comp<String, Comp.Top> result = work.slow();
    work.release();
    String programRanOn = Comp.run(result);
    assertEquals(work.ranOn(), programRanOn);
    assertNotEquals(Thread.currentThread().getName(), programRanOn);
    assertEquals(programRanOn, Comp.run(result), "the result run again: the same value, not a second run");
  }

  @Test
  void theProgramsFailureIsTheResultsFailure() {
    Comp<String, Comp.Top> result = work.failing();
    assertEquals("broken", assertThrows(IllegalStateException.class, () -> Comp.run(result)).getMessage());
    Comp<String, Comp.Top> recovered = Comp.recover(result, e -> Comp.pure("recovered " + e.getMessage()));
    assertEquals("recovered broken", Comp.run(recovered));
  }

  @Test
  void theMethodsThrowIsTheResultsFailure() {
    Comp<String, Comp.Top> result = work.throwing();
    assertEquals("before the program",
        assertThrows(IllegalArgumentException.class, () -> Comp.run(result)).getMessage());
  }

  @Test
  void anotherAsyncMethodIsSpringsAsBefore() throws Exception {
    assertNotEquals(Thread.currentThread().getName(), work.future().get(10, TimeUnit.SECONDS));
  }
}
