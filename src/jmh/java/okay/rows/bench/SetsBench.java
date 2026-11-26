package okay.rows.bench;

import static okay.rows.Sets.*;

import okay.effect.Eff;
import okay.effect.Operation;
import okay.effect.Pure;
import okay.rows.Comp;
import okay.rows.Effects;
import okay.rows.Var;

/** A rough measure of rows as sets ({@code okay.rows.Sets}) against rows of places, the same programs under the
 * same four handlers — a store answered by a clause with the continuation (innermost), mail, ids with a state, a
 * clock that only answers (outermost): nanoseconds a question, the best of several rounds. {@code set.*}: asked by
 * class; {@code place.*}: asked by place, with {@code up}. */
public final class SetsBench {
  sealed interface Ids { record Ref(String p) implements Ids, Operation<String, Ids> {} }
  sealed interface Clock { record Now() implements Clock, Operation<Long, Clock> {} }
  sealed interface Store { record Put(String k) implements Store, Operation<Boolean, Store> {} }
  sealed interface Mail { record Send(String to) implements Mail, Operation<Void, Mail> {} }

  // ---- the handlers: of both kinds of program
  static <A, R> Comp<A, R> stored(Comp<A, ? super Eff<Store, R>> c) {
    return handle((op, k) -> switch (op) { case Store.Put p -> k.resume(p, true); }, c);
  }
  static <A, R> Comp<A, R> mailed(Comp<A, ? super Eff<Mail, R>> c) {
    return handle((op, k) -> switch (op) { case Mail.Send s -> k.resume(s, null); }, c);
  }
  static <A, R> Comp<A, R> numbered(Comp<A, ? super Eff<Ids, R>> c) {
    return handle(1, (n, op, k) -> switch (op) { case Ids.Ref r -> k.resume(r, "x", n + 1); }, c);
  }
  static <A, R> Comp<A, R> clocked(Comp<A, ? super Eff<Clock, R>> c) {
    return answering(op -> switch (op) { case Clock.Now n -> n.answer(3L); }, c);
  }

  // ---- programs of a set
  static <R extends Clock> Comp<Long, R> ticks(int n, long s) {
    return n == 0 ? pure(s) : perform(new Clock.Now()).flatMap(t -> ticks(n - 1, s + t));
  }
  static <R extends Store> Comp<Long, R> puts(int n, long s) {
    return n == 0 ? pure(s) : perform(new Store.Put("k")).flatMap(t -> puts(n - 1, s + 1));
  }
  static <R extends Ids> Comp<Long, R> refs(int n, long s) {
    return n == 0 ? pure(s) : perform(new Ids.Ref("k")).flatMap(t -> refs(n - 1, s + 1));
  }
  static <R extends Clock & Ids> Comp<Long, R> block2(int n, long s) {
    if (n == 0) return pure(s);
    Comp<Long, R> two = together(t -> {
      Var<Long> now = t.perform(new Clock.Now());
      Var<String> ref = t.perform(new Ids.Ref("k"));
      return t.yield(now.with(ref, (a, b) -> a + b.length()));
    });
    return two.flatMap(x -> block2(n - 1, s + x));
  }

  // ---- the same of places
  @SuppressWarnings({"unchecked", "rawtypes"}) // an operation at its place of the list, by hand
  static <T> Comp<T, Eff<Store, Eff<Mail, Eff<Ids, Eff<Clock, Pure>>>>> at(int place, Operation<T, ?> op) {
    Comp c = Effects.perform((okay.effect.Op) op);
    for (int i = 0; i < place; i++) c = Effects.up(c);
    return c;
  }
  static Comp<Long, Eff<Store, Eff<Mail, Eff<Ids, Eff<Clock, Pure>>>>> placeTicks(int n, long s) {
    return n == 0 ? pure(s) : at(3, new Clock.Now()).flatMap(t -> placeTicks(n - 1, s + t));
  }
  static Comp<Long, Eff<Store, Eff<Mail, Eff<Ids, Eff<Clock, Pure>>>>> placePuts(int n, long s) {
    return n == 0 ? pure(s) : at(0, new Store.Put("k")).flatMap(t -> placePuts(n - 1, s + 1));
  }
  static Comp<Long, Eff<Store, Eff<Mail, Eff<Ids, Eff<Clock, Pure>>>>> placeRefs(int n, long s) {
    return n == 0 ? pure(s) : at(2, new Ids.Ref("k")).flatMap(t -> placeRefs(n - 1, s + 1));
  }

  static Comp<Long, Eff<Store, Eff<Mail, Eff<Ids, Eff<Clock, Pure>>>>> placeBlock2(int n, long s) {
    if (n == 0) return pure(s);
    Comp<Long, Eff<Store, Eff<Mail, Eff<Ids, Eff<Clock, Pure>>>>> two = Effects.together(t -> {
      Var<Long> now = t.ask(at(3, new Clock.Now()));
      Var<String> ref = t.ask(at(2, new Ids.Ref("k")));
      return t.yield(now.with(ref, (a, b) -> a + b.length()));
    });
    return two.flatMap(x -> placeBlock2(n - 1, s + x));
  }

  static void measure(String name, int ops, Runnable body) {
    long best = Long.MAX_VALUE;
    for (int round = 0; round < 12; round++) {
      long start = System.nanoTime();
      body.run();
      best = Math.min(best, System.nanoTime() - start);
    }
    System.out.println(String.format("%-16s %7.1f ns/op", name, (double) best / ops));
  }

  public static void main(String[] args) {
    var app = row(of(Store.class, Mail.class, Ids.class, Clock.class));
    int n = 1_000_000;
    for (int warm = 0; warm < 2; warm++) {
      run(clocked(numbered(mailed(stored(app.of(ticks(n, 0)))))));
      run(clocked(numbered(mailed(stored(placeTicks(n, 0))))));
    }
    measure("set.answering", n, () -> run(clocked(numbered(mailed(stored(app.of(ticks(n, 0))))))));
    measure("place.answering", n, () -> run(clocked(numbered(mailed(stored(placeTicks(n, 0)))))));
    measure("set.clause", n, () -> run(clocked(numbered(mailed(stored(app.of(puts(n, 0))))))));
    measure("place.clause", n, () -> run(clocked(numbered(mailed(stored(placePuts(n, 0)))))));
    measure("set.state", n, () -> run(clocked(numbered(mailed(stored(app.of(refs(n, 0))))))));
    measure("place.state", n, () -> run(clocked(numbered(mailed(stored(placeRefs(n, 0)))))));
    int b = 200_000;
    measure("set.block2", 2 * b, () -> run(clocked(numbered(mailed(stored(app.of(block2(b, 0))))))));
    measure("place.block2", 2 * b, () -> run(clocked(numbered(mailed(stored(placeBlock2(b, 0)))))));
  }
}
