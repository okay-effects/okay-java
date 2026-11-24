package okay.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import okay.core.Comp;
import okay.std.Async;
import okay.std.State;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** a servlet application on a port of its own: its controller's methods return programs, answered over HTTP;
 * {@link WebFluxTest} the same in a reactive one */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.main.web-application-type=servlet")
class EffControllerTest {

  @SpringBootApplication
  @Import(Programs.class)
  static class App {}

  record Total(String name, int value) {}

  @RestController
  static class Programs {
    @GetMapping("/pure")
    Comp<String, Comp.Top> pure() {
      return Comp.pure("hello");
    }

    /** a handler in the program: the sum of 1..n by a State */
    @GetMapping("/sum/{n}")
    Comp<Total, Comp.Top> sum(@PathVariable("n") int n) {
      return State.state(0, new State.Body<Comp.Top, Integer, String>() {
        public <M extends Comp.Top> Comp<String, M> in(State<M, Integer> st) {
          Comp<Void, M> p = Comp.pure(null);
          for (int i = 1; i <= n; i++) {
            int x = i;
            p = p.then(st.modify(s -> s + x));
          }
          return p.then(Comp.pure("sum"));
        }
      }).map(f -> new Total(f.value(), f.state()));
    }

    @GetMapping("/created")
    Comp<ResponseEntity<String>, Comp.Top> created() {
      return Comp.<Comp.Top, String>pure("made").map(s -> ResponseEntity.status(HttpStatus.CREATED).body(s));
    }

    @GetMapping("/failed")
    Comp<String, Comp.Top> failed() {
      return Comp.<Comp.Top, String>pure("x").flatMap(_ -> Comp.failed(new IllegalArgumentException("no such x")));
    }

    @GetMapping("/recovered")
    Comp<String, Comp.Top> recovered() {
      return Comp.recover(Comp.failed(new IllegalArgumentException("lost")), e -> Comp.pure("found " + e.getMessage()));
    }

    /** a program that waits: Async's future, which Spring takes as it is */
    @GetMapping("/async")
    CompletableFuture<String> async() {
      return Async.runAsync(new Async.Body<String>() {
        public <M extends Comp.Top> Comp<String, M> in(Async<M> as) {
          return as.blocking(() -> "waited").map(s -> s + "!");
        }
      });
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<String> bad(IllegalArgumentException e) {
      return ResponseEntity.badRequest().body("bad: " + e.getMessage());
    }
  }

  @Value("${local.server.port}")
  int port;

  final HttpClient http = HttpClient.newHttpClient();

  HttpResponse<String> get(String path) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void aProgramsValueIsTheBody() throws Exception {
    var r = get("/pure");
    assertEquals(200, r.statusCode());
    assertEquals("hello", r.body());
  }

  @Test
  void aProgramWithAHandlerWrittenAsJson() throws Exception {
    var r = get("/sum/100");
    assertEquals(200, r.statusCode());
    assertTrue(r.body().replace(" ", "").contains("\"value\":5050"), r.body());
    assertTrue(r.body().contains("\"name\":\"sum\""), r.body());
  }

  @Test
  void aResponseEntityOfTheProgramDecidesTheStatus() throws Exception {
    var r = get("/created");
    assertEquals(201, r.statusCode());
    assertEquals("made", r.body());
  }

  @Test
  void aFailureGoesToTheExceptionHandler() throws Exception {
    var r = get("/failed");
    assertEquals(400, r.statusCode());
    assertEquals("bad: no such x", r.body());
  }

  @Test
  void aRecoveredFailureIsAValue() throws Exception {
    var r = get("/recovered");
    assertEquals(200, r.statusCode());
    assertEquals("found lost", r.body());
  }

  @Test
  void anAsyncRunsFutureIsSpringsOwn() throws Exception {
    var r = get("/async");
    assertEquals(200, r.statusCode());
    assertEquals("waited!", r.body());
  }
}
