package okay.rows;

import static okay.rows.Sets.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import okay.effect.Op;
import okay.effect.Operation;
import okay.rows.Sets.And;
import org.junit.jupiter.api.Test;

/** Rows as sets ({@link Sets}): programs whose row is a type bounded by their effects, handlers that are functions
 * over a program given the continuation, the set written as a list once, where the handlers are put. */
class SetsTest {

  // ---- effects: a sealed interface, each operation naming its answer and its effect

  sealed interface Ids { record Ref(String prefix) implements Ids, Operation<String, Ids> {} }
  sealed interface Clock { record Now() implements Clock, Operation<Long, Clock> {} }
  sealed interface Store {
    record Put(String key, String value) implements Store, Operation<Boolean, Store> {}
    record Get(String key) implements Store, Operation<Optional<String>, Store> {}
  }
  sealed interface Mail { record Send(String to) implements Mail, Operation<Void, Mail> {} }
  sealed interface Abort { record Fail(String why) implements Abort, Operation<Void, Abort> {} }
  sealed interface Choice { record Flip() implements Choice, Operation<Boolean, Choice> {} }
  /** the library's other form of an operation — (effect, answer, itself) — is asked the same way */
  sealed interface Rates { record Rate(String currency) implements Rates, Op<Rates, Integer, Rate> {} }

  // ---- programs: the row is a set, in any order; a program of fewer effects is used in one of more as it is

  static <R extends Ids & Clock> Comp<String, R> stamp(String prefix) {
    return perform(new Ids.Ref(prefix)).flatMap(ref -> perform(new Clock.Now()).map(now -> ref + "@" + now));
  }

  static <R extends Clock & Mail & Store & Ids> Comp<String, R> reserve(String who) {
    return stamp("B").flatMap(ref ->
        perform(new Store.Put(ref, who)).flatMap(ok -> perform(new Mail.Send(who)).map(sent -> ref)));
  }

  static <R extends Abort & Ids & Clock> Comp<String, R> orFail(boolean fail) {
    return stamp("A").flatMap(s -> fail ? perform(new Abort.Fail("no")).map(never -> s) : pure(s));
  }

  static <R extends Choice> Comp<String, R> coins() {
    return perform(new Choice.Flip()).flatMap(a ->
        perform(new Choice.Flip()).map(b -> (a ? "H" : "T") + (b ? "H" : "T")));
  }

  /** a row named once, for a class of programs */
  static final class Booking<R extends Store & Mail & Ids & Clock> {
    Comp<String, R> twice(String a, String b) {
      return SetsTest.<R>reserve(a).flatMap(x -> told(b).map(y -> x + " " + y));
    }
    Comp<String, R> told(String who) { return perform(new Mail.Send(who)).map(sent -> who); }
  }

  // ---- handlers: functions over a program, each given the continuation

  final Map<String, String> db = new HashMap<>();
  final List<String> sent = new ArrayList<>();

  <A, R> Comp<A, R> stored(Comp<A, ? super And<Store, R>> c) {
    return handle((op, k) -> switch (op) {
      case Store.Put p -> { db.put(p.key(), p.value()); yield k.resume(p, true); }
      case Store.Get g -> k.resume(g, Optional.ofNullable(db.get(g.key())));
    }, c);
  }

  <A, R> Comp<A, R> mailed(Comp<A, ? super And<Mail, R>> c) {
    return handle((op, k) -> switch (op) { case Mail.Send s -> { sent.add(s.to()); yield k.resume(s, null); } }, c);
  }

  /** a clause that asks another effect: the list that stays must have it */
  <A, R> Comp<A, And<Clock, R>> audited(Comp<A, ? super And<Mail, And<Clock, R>>> c) {
    return handle((op, k) -> switch (op) {
      case Mail.Send s -> ask(k, new Clock.Now()).flatMap(now -> {
        sent.add(s.to() + " at " + now);
        return k.resume(s, null);
      });
    }, c);
  }

  static <A, R> Comp<A, R> identified(Comp<A, ? super And<Ids, R>> c) {
    return answering(op -> switch (op) { case Ids.Ref r -> r.answer(r.prefix() + "-1"); }, c);
  }

  /** a state: each reference its own number */
  static <A, R> Comp<A, R> numbered(Comp<A, ? super And<Ids, R>> c) {
    return handle(1, (n, op, k) -> switch (op) {
      case Ids.Ref r -> k.resume(r, r.prefix() + "-" + n, n + 1);
    }, c);
  }

  static <A, R> Comp<A, R> clocked(long at, Comp<A, ? super And<Clock, R>> c) {
    return answering(op -> switch (op) { case Clock.Now n -> n.answer(at); }, c);
  }

  /** the program's value made another: the rest left where it failed */
  static <A, R> Comp<Optional<A>, R> attempt(Comp<A, ? super And<Abort, R>> c) {
    return handle(a -> pure(Optional.of(a)), (op, k) -> pure(Optional.empty()), c);
  }

  /** the rest resumed twice: every way the program can go */
  static <A, R> Comp<List<A>, R> all(Comp<A, ? super And<Choice, R>> c) {
    return handle(a -> pure(List.of(a)), (op, k) -> switch (op) {
      case Choice.Flip f -> k.resume(f, true).flatMap(xs -> k.resume(f, false).map(ys -> {
        List<A> both = new ArrayList<>(xs);
        both.addAll(ys);
        return both;
      }));
    }, c);
  }

  // ---- the set written as a list, where the handlers are put

  @Test
  void aWholeProgram() {
    var app = row(of(Store.class, Mail.class, Ids.class, Clock.class));
    String ref = run(clocked(42, identified(mailed(stored(app.of(reserve("Ann")))))));
    assertEquals("B-1@42", ref);
    assertEquals(Map.of("B-1@42", "Ann"), db);
    assertEquals(List.of("Ann"), sent);
  }

  @Test
  void aProgramOfFewerEffectsUnderTheHandlersOfMore() {
    var app = row(of(Store.class, Mail.class, Ids.class, Clock.class));
    assertEquals("T-1@7", run(clocked(7, identified(mailed(stored(app.of(stamp("T"))))))));
  }

  @Test
  void theListSaysTheOrderOfTheHandlersNotOfTheProgram() {
    var app = row(of(Clock.class, Ids.class, Mail.class, Store.class));
    assertEquals("B-1@42", run(stored(mailed(identified(clocked(42, app.of(reserve("Ann"))))))));
  }

  @Test
  void aRowNamedOnceByAClass() {
    var app = row(of(Store.class, Mail.class, Ids.class, Clock.class));
    assertEquals("B-1@1 Bob", run(clocked(1, identified(mailed(stored(app.of(new Booking<>().twice("Ann", "Bob"))))))));
    assertEquals(List.of("Ann", "Bob"), sent);
  }

  @Test
  void aStateForEachRun() {
    var two = row(of(Ids.class, Clock.class));
    assertEquals("B-1@5 B-2@5", run(clocked(5, numbered(two.of(both())))));
    assertEquals("B-1@5 B-2@5", run(clocked(5, numbered(two.of(both())))));
  }

  static <R extends Ids & Clock> Comp<String, R> both() {
    return stamp("B").flatMap(a -> stamp("B").map(b -> a + " " + b));
  }

  @Test
  void aClauseAsksAnotherEffect() {
    var app = row(of(Store.class, Ids.class, Mail.class, Clock.class));
    assertEquals("B-1@9", run(clocked(9, audited(numbered(stored(app.of(reserve("Zoe"))))))));
    assertEquals(List.of("Zoe at 9"), sent);
  }

  @Test
  void aHandlerInsideAProgramLeavesTheRestInItsRow() {
    var two = row(of(Ids.class, Clock.class));
    assertEquals(Optional.of("A-1@3"), run(clocked(3, identified(two.of(careful(false))))));
    assertEquals(Optional.empty(), run(clocked(3, identified(two.of(careful(true))))));
  }

  /** Abort answered here; Ids and Clock stay in the row R */
  static <R extends Ids & Clock> Comp<Optional<String>, R> careful(boolean fail) {
    return attempt(Sets.<R>keep().row(of(Abort.class)).of(orFail(fail)));
  }

  @Test
  void theRestResumedTwice() {
    assertEquals(List.of("HH", "HT", "TH", "TT"), run(all(row(of(Choice.class)).of(coins()))));
  }

  @Test
  void aStateForEachPath() {
    // the state inside the choice: each path numbers from where the choice was made
    var inside = row(of(Ids.class, Choice.class));
    assertEquals(List.of("H X-1", "T X-1"), run(all(numbered(inside.of(flipThenRef())))));
  }

  static <R extends Choice & Ids> Comp<String, R> flipThenRef() {
    return perform(new Choice.Flip()).flatMap(h -> perform(new Ids.Ref("X")).map(r -> (h ? "H " : "T ") + r));
  }

  @Test
  void theNearestHandlerOfAnEffectAnswers() {
    // a part of the program under its own clock; the rest under the one outside
    var whole = row(of(Clock.class));
    assertEquals(101L, run(clocked(100, whole.of(SetsTest.<Clock>inner()))));
  }

  static <R extends Clock> Comp<Long, R> inner() {
    Comp<Long, R> part = clocked(1, Sets.<R>keep().row(of(Clock.class)).of(perform(new Clock.Now())));
    return part.flatMap(a -> perform(new Clock.Now()).map(b -> a + b));
  }

  @Test
  void anOperationOfTheOtherForm() {
    Comp<Integer, And<Rates, Sets.None>> p = row(of(Rates.class)).of(perform(new Rates.Rate("EUR")));
    assertEquals(4, run(answering(op -> switch (op) { case Rates.Rate r -> r.answer(4); }, p)));
  }

  @Test
  void aLongLoopOnTheTrampoline() {
    var app = row(of(Store.class, Mail.class, Ids.class, Clock.class));
    assertEquals(3_000_000L, run(clocked(3, identified(mailed(stored(app.of(ticks(1_000_000, 0))))))));
  }

  static <R extends Clock> Comp<Long, R> ticks(int n, long sum) {
    return n == 0 ? pure(sum) : perform(new Clock.Now()).flatMap(t -> ticks(n - 1, sum + t));
  }

  // ---- a block: questions, each answer a name; the row a set

  static <R extends Ids & Clock & Store> Comp<String, R> quote(String who) {
    return together(t -> {
      Var<String> ref = t.perform(new Ids.Ref("Q"));
      Var<Long> now = t.perform(new Clock.Now());
      Var<Boolean> kept = ref.then(r -> t.perform(new Store.Put(r, who)));
      Var<List<String>> more = t.gather(g -> { for (String p : List.of("a", "b")) g.perform(new Ids.Ref(p)); });
      return t.yield(ref.with(now, (r, n) -> r + "@" + n), kept, more, (s, k, m) -> s + " " + k + " " + m);
    });
  }

  @Test
  void aBlockOfARowThatIsASet() {
    var app = row(of(Store.class, Ids.class, Clock.class));
    assertEquals("Q-1@8 true [a-2, b-3]", run(clocked(8, numbered(stored(app.of(quote("Ann")))))));
    assertEquals(Map.of("Q-1", "Ann"), db);
  }
}
