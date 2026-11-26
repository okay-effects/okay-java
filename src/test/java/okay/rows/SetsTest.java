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
import okay.effect.Eff;
import okay.rows.std.Choose;
import okay.rows.std.State;
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

  <A, R> Comp<A, R> stored(Comp<A, ? super Eff<Store, R>> c) {
    return handle((op, k) -> switch (op) {
      case Store.Put p -> { db.put(p.key(), p.value()); yield k.resume(p, true); }
      case Store.Get g -> k.resume(g, Optional.ofNullable(db.get(g.key())));
    }, c);
  }

  <A, R> Comp<A, R> mailed(Comp<A, ? super Eff<Mail, R>> c) {
    return handle((op, k) -> switch (op) { case Mail.Send s -> { sent.add(s.to()); yield k.resume(s, null); } }, c);
  }

  /** a clause that asks another effect: the list that stays must have it */
  <A, R> Comp<A, Eff<Clock, R>> audited(Comp<A, ? super Eff<Mail, Eff<Clock, R>>> c) {
    return handle((op, k) -> switch (op) {
      case Mail.Send s -> ask(k, new Clock.Now()).flatMap(now -> {
        sent.add(s.to() + " at " + now);
        return k.resume(s, null);
      });
    }, c);
  }

  static <A, R> Comp<A, R> identified(Comp<A, ? super Eff<Ids, R>> c) {
    return answering(op -> switch (op) { case Ids.Ref r -> r.answer(r.prefix() + "-1"); }, c);
  }

  /** a state: each reference its own number */
  static <A, R> Comp<A, R> numbered(Comp<A, ? super Eff<Ids, R>> c) {
    return handle(1, (n, op, k) -> switch (op) {
      case Ids.Ref r -> k.resume(r, r.prefix() + "-" + n, n + 1);
    }, c);
  }

  static <A, R> Comp<A, R> clocked(long at, Comp<A, ? super Eff<Clock, R>> c) {
    return answering(op -> switch (op) { case Clock.Now n -> n.answer(at); }, c);
  }

  /** the program's value made another: the rest left where it failed */
  static <A, R> Comp<Optional<A>, R> attempt(Comp<A, ? super Eff<Abort, R>> c) {
    return handle(a -> pure(Optional.of(a)), (op, k) -> pure(Optional.empty()), c);
  }

  /** the rest resumed twice: every way the program can go */
  static <A, R> Comp<List<A>, R> all(Comp<A, ? super Eff<Choice, R>> c) {
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
    Comp<Integer, Eff<Rates, okay.effect.Pure>> p = row(of(Rates.class)).of(perform(new Rates.Rate("EUR")));
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

  // ---- handlers of rows of places around a program of a set: the library's effects, a handler written before

  static <R extends State<Integer> & okay.rows.std.Clock & Ids> Comp<String, R> counted() {
    return perform(new State.Get<Integer>()).flatMap(n ->
        perform(new State.Put<>(n + 1)).flatMap(put ->
            perform(new okay.rows.std.Clock.Now()).flatMap(at ->
                perform(new Ids.Ref("C")).map(ref -> ref + ":" + n + "@" + at))));
  }

  @Test
  void theLibrarysEffectsUnderTheirOwnHandlers() {
    var app = row(of(Ids.class, Sets.<State<Integer>>the(State.class), okay.rows.std.Clock.class));
    State.Final<Integer, String> end = run(okay.rows.std.Clock.at(() -> 5L, State.run(10,
        identified(app.of(pair(counted(), counted()))))));
    assertEquals(new State.Final<>(12, "C-1:10@5 C-1:11@5"), end);
  }

  static <A, R> Comp<String, R> pair(Comp<A, R> a, Comp<A, R> b) {
    return a.flatMap(x -> b.map(y -> x + " " + y));
  }

  @Test
  void anOperationAskedByItsClassPassesAHandlerOfAPlace() {
    // the clock, a handler of a place, is INSIDE the handler of Ids: a Ref goes past it
    var app = row(of(Sets.<State<Integer>>the(State.class), okay.rows.std.Clock.class, Ids.class));
    State.Final<Integer, String> end = run(numbered(okay.rows.std.Clock.at(() -> 7L, State.run(0,
        app.of(counted())))));
    assertEquals(new State.Final<>(1, "C-1:0@7"), end);
  }

  @Test
  void aStateForEachPathOfALibraryChoice() {
    var app = row(of(Sets.<State<Integer>>the(State.class), Choose.class));
    List<State.Final<Integer, String>> all = run(Choose.all(State.run(0, app.of(chosen()))));
    assertEquals(List.of(new State.Final<>(1, "a0"), new State.Final<>(1, "b0")), all);
  }

  static <R extends Choose & State<Integer>> Comp<String, R> chosen() {
    return perform(new Choose.Among<>(List.of("a", "b"))).flatMap(x ->
        perform(new State.Get<Integer>()).flatMap(n -> perform(new State.Put<>(n + 1)).map(put -> x + n)));
  }

  /** a handler written for rows of places, of an effect declared for sets */
  static <A, R> Comp<A, R> clockOfPlaces(long at, Comp<A, ? super Eff<Clock, R>> c) {
    return Effects.answering(op -> switch (op) { case Clock.Now n -> n.answer(at); }, c);
  }

  @Test
  void aHandlerWrittenForPlaces() {
    var app = row(of(Store.class, Ids.class, Clock.class));
    assertEquals("Q-1@3 true [a-2, b-3]", run(clockOfPlaces(3, numbered(stored(app.of(quote("Ann")))))));
  }

  @Test
  void inFrontOfAHandlerTheWordsOfPlaces() {
    // observe and cached keep the effect in the list: what they pass on goes to the handler outside them
    var app = row(of(Ids.class, Clock.class));
    List<String> seen = new ArrayList<>();
    String twice = run(clocked(4, numbered(Effects.observe(Ids.class, op -> {
      seen.add(op.toString());
      return pure(null);
    }, Effects.cached(app.of(both()))))));
    assertEquals("B-1@4 B-1@4", twice);          // the same Ref asked twice: once outside the cache
    assertEquals(1, seen.size());
  }

  /** a store that answers the operations a block asks at once as ONE set — one query */
  <A, R> Comp<A, R> storedInSets(List<Integer> queries, Comp<A, ? super Eff<Store, R>> c) {
    return handle((op, k) -> switch (op) {
      case Store.Put p -> { queries.add(1); db.put(p.key(), p.value()); yield k.resume(p, true); }
      case Store.Get g -> { queries.add(1); yield k.resume(g, Optional.ofNullable(db.get(g.key()))); }
    }, (ops, k) -> {
      queries.add(ops.size());
      for (Store op : ops) {
        switch (op) {
          case Store.Put p -> { db.put(p.key(), p.value()); k.answer(p, true); }
          case Store.Get g -> k.answer(g, Optional.ofNullable(db.get(g.key())));
        }
      }
      return k.resume();
    }, c);
  }

  static <R extends Store & Clock & Ids> Comp<String, R> three() {
    return together(t -> {
      Var<Optional<String>> a = t.perform(new Store.Get("a"));
      Var<Long> now = t.perform(new Clock.Now());
      Var<Optional<String>> b = t.perform(new Store.Get("b"));
      Var<String> ref = t.perform(new Ids.Ref("R"));
      Var<Optional<String>> c = t.perform(new Store.Get("c"));
      return t.yield(a.with(b, (x, y) -> x.orElse("-") + y.orElse("-")), c, now.with(ref, (n, r) -> r + "@" + n),
          (ab, z, at) -> ab + z.orElse("-") + " " + at);
    });
  }

  @Test
  void theOperationsOfOneEffectInARoundAreOneSet() {
    db.put("a", "A");
    db.put("c", "C");
    List<Integer> queries = new ArrayList<>();
    var app = row(of(Store.class, Ids.class, Clock.class));
    assertEquals("A-C R-1@2", run(clocked(2, identified(storedInSets(queries, app.of(three()))))));
    assertEquals(List.of(3), queries);      // the three Gets, among a Now and a Ref: one set
  }

  // ---- the set named by a class of the application: a boundary that can be a field

  /** its type parameter says the set; its method puts the handlers */
  static final class Desk<V extends Ids & Clock> {
    <A> A run(long at, Comp<A, ? super V> p) {
      var row = Sets.<V>exactly(Desk.class).row(of(Ids.class, Clock.class));
      return Sets.run(clocked(at, numbered(row.of(p))));
    }
  }

  static final Desk<?> DESK = new Desk<>();

  @Test
  void theSetNamedByAClassIsAField() {
    assertEquals("B-1@6", DESK.run(6, stamp("B")));
    assertEquals("B-1@6 B-2@6", DESK.run(6, both()));
  }

  /** bounded by an effect its list does not name */
  static final class Careless<V extends Ids & Clock & Mail> {
    <A> A run(Comp<A, ? super V> p) {
      var row = Sets.<V>exactly(Careless.class).row(of(Ids.class, Clock.class));
      return Sets.run(clocked(1, numbered(row.of(p))));
    }
  }

  @Test
  void aNamedSetWiderThanItsListIsRefusedWhenItIsUsed() {
    IllegalStateException e = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
        () -> new Careless<>().run(stamp("B")));
    assertEquals(true, e.getMessage().contains("Mail"));
  }

  // ---- a set asked from a chain, and a handler that cannot know its effect

  @Test
  void aSetOfOperationsFromAChain() {
    db.put("a", "A");
    List<Integer> queries = new ArrayList<>();
    var app = row(of(Store.class));
    Comp<List<Optional<String>>, Eff<Store, okay.effect.Pure>> both =
        app.of(performAll(List.of(new Store.Get("a"), new Store.Get("b"))));
    assertEquals(List.of(Optional.of("A"), Optional.empty()), run(storedInSets(queries, both)));
    assertEquals(List.of(2), queries);
  }

  /** generic in its effect: where it is made javac knows no class for F, so it answers by its place only */
  static <F, A, R> Comp<A, R> silent(Comp<A, ? super Eff<F, R>> c) {
    return handle((F op, K<A, R> k) -> k.unwind().map(left -> null), c);
  }

  @Test
  void aHandlerGenericInItsEffectDoesNotAnswerAProgramOfASet() {
    var app = row(of(Clock.class));
    NoHandler e = org.junit.jupiter.api.Assertions.assertThrows(NoHandler.class,
        () -> run(SetsTest.<Clock, Long, okay.effect.Pure>silent(app.of(perform(new Clock.Now())))));
    assertEquals(true, e.getMessage().contains("asked by its effect's class"), e.getMessage());
  }

  // ---- a handler that leaves, and a finalizer of the rest that asks its effect

  sealed interface Say {
    record Line(String text) implements Say, Operation<Void, Say> {}
    record Stop() implements Say, Operation<Void, Say> {}
  }

  static <R extends Say> Comp<String, R> speaking() {
    Comp<String, R> body = perform(new Say.Stop()).map(never -> "said all");
    return body.ensuring(() -> perform(new Say.Line("bye")));
  }

  /** leaves the rest at Stop: the rest's finalizers run, and what they ask of this handler ends where it was */
  static <R> Comp<String, R> heard(String who, List<String> seen, Comp<String, ? super Eff<Say, R>> c) {
    return handle((op, k) -> switch (op) {
      case Say.Line l -> { seen.add(who + " heard " + l.text()); yield k.resume(l, null); }
      case Say.Stop s -> k.unwind().map(left -> who + " stopped");
    }, c);
  }

  @Test
  void aFinalizersOperationOfTheHandlerThatLeftEndsWhereItWas() {
    List<String> seen = new ArrayList<>();
    assertEquals("h stopped", run(heard("h", seen, row(of(Say.class)).of(speaking()))));
    assertEquals(List.of(), seen);
  }

  /** two handlers of the effect: the inner one leaves, and the outer is not asked what was the inner's */
  static <R extends Say> Comp<String, R> inner(List<String> seen) {
    return heard("inner", seen, Sets.<R>keep().row(of(Say.class)).of(speaking()));
  }

  @Test
  void anOuterHandlerOfTheEffectIsNotAskedForTheOneThatLeft() {
    List<String> seen = new ArrayList<>();
    assertEquals("inner stopped", run(heard("outer", seen, row(of(Say.class)).of(inner(seen)))));
    assertEquals(List.of(), seen);
  }
}
