package okay.resumable;

import static okay.rows.Sets.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import okay.effect.Eff;
import okay.effect.Operation;
import okay.resumable.Direct.Asks;
import okay.rows.Comp;
import okay.rows.std.State;
import org.junit.jupiter.api.Test;

/** Plain Java whose row is a set ({@code Direct.body}): the effects in the type of the method, nothing declared
 * for an effect, no {@code throws}; under the handlers of {@code okay.rows}, with the plugin's flags and with none. */
class SetBodyTest {

  sealed interface Ids { record Ref(String prefix) implements Ids, Operation<String, Ids> {} }
  sealed interface Clock { record Now() implements Clock, Operation<Long, Clock> {} }
  sealed interface Mail { record Send(String to) implements Mail, Operation<Void, Mail> {} }
  sealed interface Abort { record Fail(String why) implements Abort, Operation<Void, Abort> {} }
  sealed interface Choice { record Flip() implements Choice, Operation<Boolean, Choice> {} }

  // ---- bodies: answers in locals, for and if as they are

  static <R extends Ids & Clock> Comp<String, R> stamp(String prefix) {
    return Direct.body(d -> {
      String ref = d.perform(new Ids.Ref(prefix));
      long now = d.perform(new Clock.Now());
      return ref + "@" + now;
    });
  }

  /** a method a body calls takes what it asks with */
  static <R extends Mail> int tellAll(Asks<R> d, List<String> people) {
    int told = 0;
    for (String who : people) {
      if (who.isEmpty()) continue;
      d.perform(new Mail.Send(who));
      told++;
    }
    return told;
  }

  static <R extends Ids & Clock & Mail> Comp<String, R> invite(List<String> people) {
    return Direct.body(d -> {
      String s = d.embed(stamp("I"));               // a program of fewer effects, as a part of the body
      int told = tellAll(d, people);
      return s + " told " + told;
    });
  }

  // ---- handlers: okay.rows' own

  final List<String> sent = new ArrayList<>();

  static <A, R> Comp<A, R> identified(Comp<A, ? super Eff<Ids, R>> c) {
    return answering(op -> switch (op) { case Ids.Ref r -> r.answer(r.prefix() + "-1"); }, c);
  }
  static <A, R> Comp<A, R> numbered(Comp<A, ? super Eff<Ids, R>> c) {
    return handle(1, (n, op, k) -> switch (op) { case Ids.Ref r -> k.resume(r, r.prefix() + "-" + n, n + 1); }, c);
  }
  static <A, R> Comp<A, R> clocked(long at, Comp<A, ? super Eff<Clock, R>> c) {
    return answering(op -> switch (op) { case Clock.Now n -> n.answer(at); }, c);
  }
  /** takes the rest: goes on after it */
  <A, R> Comp<A, R> mailed(Comp<A, ? super Eff<Mail, R>> c) {
    return handle((op, k) -> switch (op) {
      case Mail.Send s -> k.resume(s, null).map(a -> { sent.add(s.to()); return a; });
    }, c);
  }
  static <A, R> Comp<Optional<A>, R> attempt(Comp<A, ? super Eff<Abort, R>> c) {
    return handle(a -> pure(Optional.of(a)), (op, k) -> k.unwind().map(left -> Optional.empty()), c);
  }

  static <R extends Abort & Ids> Comp<String, R> risky(List<String> closed) {
    return Direct.body(d -> {
      String ref = d.perform(new Ids.Ref("B"));
      try {
        d.perform(new Abort.Fail("no"));
        return ref;
      } finally {
        closed.add(ref);
      }
    });
  }

  @Test
  void aBodyUnderHandlersThatAnswerInPlace() {
    var two = row(of(Ids.class, Clock.class));
    assertEquals("B-1@7", run(clocked(7, identified(two.of(stamp("B"))))));
  }

  @Test
  void aLoopAnIfAMethodAndAProgramInsideABody() {
    var app = row(of(Mail.class, Ids.class, Clock.class));
    assertEquals("I-1@3 told 2", run(clocked(3, numbered(mailed(app.of(invite(List.of("ann", "", "bob"))))))));
    assertEquals(List.of("bob", "ann"), sent);     // each letter noted after the rest of the body: the rest was taken
  }

  @Test
  void aBodyLeftWhereItFailed() {
    var app = row(of(Abort.class, Ids.class));
    List<String> closed = new ArrayList<>();
    Comp<Optional<String>, Eff<Ids, okay.effect.Pure>> p = attempt(app.of(risky(closed)));
    assertEquals(Optional.empty(), run(identified(p)));
    assertEquals(List.of("B-1"), closed);          // the body's finally ran when its rest was left
  }

  @Test
  void theLibrarysStateInABody() {
    var app = row(of(Ids.class, okay.rows.Sets.<State<Integer>>the(State.class)));
    State.Final<Integer, String> end = run(State.run(40, identified(app.of(counting()))));
    assertEquals(new State.Final<>(42, "C-1 40"), end);
  }

  static <R extends State<Integer> & Ids> Comp<String, R> counting() {
    return Direct.body(d -> {
      int n = d.perform(new State.Get<Integer>());
      for (int i = 0; i < 2; i++) d.perform(new State.Put<>(d.perform(new State.Get<Integer>()) + 1));
      return d.perform(new Ids.Ref("C")) + " " + n;
    });
  }

  @Test
  void aRestResumedTwiceIsRefused() {
    var app = row(of(Choice.class));
    Comp<String, Eff<Choice, okay.effect.Pure>> flip = app.of(Direct.body(d -> d.perform(new Choice.Flip()) ? "H" : "T"));
    Comp<String, okay.effect.Pure> both = handle((op, k) -> switch (op) {
      case Choice.Flip f -> k.resume(f, true).flatMap(a -> k.resume(f, false).map(b -> a + b));
    }, flip);
    assertThrows(IllegalStateException.class, () -> run(both));
  }
}
