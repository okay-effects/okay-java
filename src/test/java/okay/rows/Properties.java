package okay.rows;

import static okay.rows.Effects.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import okay.rows.std.Async;
import okay.rows.std.Choose;
import okay.rows.std.Clock;
import okay.rows.std.Emit;
import okay.rows.std.Fresh;
import okay.rows.std.Pair;
import okay.rows.std.Reader;
import okay.rows.std.State;
import okay.rows.std.Throws;
import okay.rows.std.Writer;

/** What the model promises, each promise a test. Run: {@code java -cp out okay.rows.Properties}. What the
 * compiler promises — no program with an effect left runs — is in {@code negative/}. */
final class Properties {
  static int failed = 0;

  interface Check {
    void run() throws Exception;
  }

  static void test(String name, Check body) {
    try {
      body.run();
      System.out.println("  + " + name);
    } catch (Throwable t) {
      failed++;
      System.out.println("==> X " + name + ": " + t);
    }
  }

  static void same(Object got, Object expected) {
    if (!Objects.equals(got, expected)) throw new AssertionError("expected " + expected + ", got " + got);
  }

  /** the first {@code n} values of a stream */
  static <T> List<T> first(int n, Iterator<T> stream) {
    List<T> values = new ArrayList<>();
    while (values.size() < n && stream.hasNext()) values.add(stream.next());
    return values;
  }

  // The programs: the type of each is the row of the effects it asks, in the order of the handlers it is run under.

  static Comp<Integer, Eff<State<Integer>, ?>> count(int n) {
    if (n == 0) return State.get();
    return State.<Integer>get().flatMap(s -> State.put(s + 1)).then(() -> count(n - 1));
  }

  static Comp<Integer, Eff<State<Integer>, Eff<Writer<String>, Eff<Throws<String>, ?>>>> logged() {
    return State.<Integer>get().flatMap(s ->
        up(Writer.tell("was " + s)).flatMap(told ->
            State.put(s + 1).flatMap(put -> s > 100 ? up(up(Throws.raise("too big"))) : pure(s * 2))));
  }

  static Comp<Integer, Eff<State<Integer>, ?>> nested(int binds) {
    Comp<Integer, Eff<State<Integer>, ?>> p = State.get();
    for (int i = 0; i < binds; i++) p = p.flatMap(s -> State.put(s + 1).map(done -> s + 1));
    return p;
  }

  static <R> Comp<Boolean, R> even(int n) { return n == 0 ? pure(true) : defer(() -> odd(n - 1)); }
  static <R> Comp<Boolean, R> odd(int n) { return n == 0 ? pure(false) : defer(() -> even(n - 1)); }

  static Comp<Integer, Eff<State<Integer>, ?>> add(int x) {
    return State.<Integer>get().flatMap(s -> State.put(s + x).map(done -> s + x));
  }

  static Comp<Integer, Eff<Choose, ?>> oddOnly() {
    return Choose.among(List.of(2, 4, 5, 7)).flatMap(x -> x % 2 == 0 ? Choose.none() : pure(x));
  }

  static Comp<Void, Eff<Emit<Integer>, ?>> from(int n) { return Emit.send(n).then(() -> from(n + 1)); }

  static Comp<Void, Eff<Emit<Integer>, ?>> three() { return Emit.sendAll(List.of(1, 2, 3)); }

  static Comp<Integer, Eff<Async, ?>> later(ArrayDeque<Runnable> waiting, int n) {
    return Async.await(resume -> waiting.push(() -> resume.accept(n)));
  }

  static Comp<Integer, Eff<Async, ?>> after(long millis, int n) { return Async.sleep(millis).map(done -> n); }

  static Comp<Integer, Eff<Async, ?>> answered(int n, int sum) {
    if (n == 0) return pure(sum);
    return Async.<Integer>await(resume -> resume.accept(1)).flatMap(x -> answered(n - 1, sum + x));
  }

  /** an interceptor at the state's place in the row — a handler of the program's first effect, put over the state
   * ({@code under}): every operation on the state seen, a put of more than 2 changed, and all asked again */
  static <A, T> Comp<A, Eff<State<Integer>, T>> audit(List<String> seen, Comp<A, ? super Eff<State<Integer>, T>> p) {
    return handle(State.class, new Handler<State<Integer>, A, A, Eff<State<Integer>, T>>() {
      public Comp<A, Eff<State<Integer>, T>> ret(A a) { return pure(a); }
      public Comp<A, Eff<State<Integer>, T>> on(State<Integer> op, K<A, Eff<State<Integer>, T>> k) {
        seen.add(op.toString());
        return switch (op) {
          case State.Put<Integer> put when put.value() > 2 ->
              perform(new State.Put<>(2)).flatMap(done -> k.resume(put, done));
          case State.Put<Integer> put -> perform(put).flatMap(done -> k.resume(put, done));
          case State.Get<Integer> get -> perform(get).flatMap(s -> k.resume(get, s));
        };
      }
    }, under(p));
  }

  sealed interface Account {}
  record Deposit(int amount) implements Account, Op<Account, Integer, Deposit> {}

  /** an account kept in a state: the handler of one effect asks the operations of another, the next in the row */
  static <A, R extends Eff<State<Integer>, ?>> Comp<A, R> onState(Comp<A, ? super Eff<Account, R>> p) {
    return handle(Account.class, new Handler<Account, A, A, R>() {
      public Comp<A, R> ret(A a) { return pure(a); }
      public Comp<A, R> on(Account op, K<A, R> k) {
        return switch (op) {
          case Deposit deposit -> State.<Integer>get().flatMap(s ->
              State.put(s + deposit.amount()).then(() -> k.resume(deposit, s + deposit.amount())));
        };
      }
    }, p);
  }

  // Two effects of one class are two places in the row: an operation goes to the one its type says.

  static Comp<String, Eff<State<String>, Eff<State<Integer>, ?>>> twoStates() {
    return State.<String>modify(s -> s + "b").flatMap(done ->
        up(State.<Integer>modify(n -> n + 1)).flatMap(more ->
            up(State.<Integer>get()).flatMap(n -> State.<String>get().map(s -> s + n))));
  }

  static Comp<Integer, Eff<State<Integer>, Eff<Reader<Integer>, ?>>> stateOverReader() {
    return State.<Integer>get().flatMap(s ->
        up(Reader.<Integer>ask()).flatMap(v -> State.put(s + v).map(done -> s + v)));
  }

  /** a choice two places out, asked under a marker: the rest of the program, taken there, has the marker in it */
  static Comp<Integer, Eff<State<String>, Eff<State<Integer>, Eff<Choose, ?>>>> chosenAcross() {
    Comp<Integer, Eff<State<Integer>, Eff<Choose, ?>>> inner = up(Choose.among(List.of(1, 2))).flatMap(x -> add(x));
    return up(inner).flatMap(v -> State.<String>modify(s -> s + v).map(done -> v));
  }

  static Comp<String, Eff<Reader<String>, Eff<Reader<Integer>, ?>>> twoReaders() {
    return up(Reader.<Integer>ask()).flatMap(n -> Reader.<String>ask().map(s -> s + n));
  }

  /** an interceptor found by the class: every fresh number that passes is seen, asked again, and a hundred more */
  static <A, R extends Eff<Fresh, ?>> Comp<A, R> hundred(List<String> seen, Comp<A, ? super R> p) {
    return intercept(Fresh.class, new Handler<Fresh, A, A, R>() {
      public Comp<A, R> ret(A a) { return pure(a); }
      public Comp<A, R> on(Fresh op, K<A, R> k) {
        seen.add(op.toString());
        return switch (op) {
          case Fresh.Next next -> perform(next).flatMap(n -> k.resume(next, n + 100));
        };
      }
    }, p);
  }

  static Comp<Pair<Long, Long>, Eff<Fresh, Eff<Fresh, ?>>> twoFresh() {
    return Fresh.fresh().flatMap(a -> up(Fresh.fresh()).map(b -> new Pair<>(a, b)));
  }

  /** the rest of a program that stopped at a question, resumed with the answer */
  @SuppressWarnings("unchecked") // the answer is given as the test knows the operation asks: an Integer for a Get
  static <X, A, R> Step<A, R> resumed(Step<A, R> at, Object answer) {
    return step(((Step.Asked<X, A, R>) at).k().apply((X) answer));
  }

  /** the lie the compiler does not let a program tell: a row without an effect that is asked */
  @SuppressWarnings("unchecked") // on purpose: the only way to an operation with no handler
  static <A> Comp<A, Pure> closed(Comp<A, ?> open) { return (Comp<A, Pure>) open; }

  // ---- a finalizer runs when a part of a program is left: with its value, or unwound

  static List<String> log = new ArrayList<>();

  /** {@code what} is written down when this program runs */
  static <R> Comp<Void, R> said(String what) {
    return defer(() -> {
      log.add(what);
      return pure(null);
    });
  }

  /** a result, and what was said on the way, in order */
  static <A> Pair<A, List<String>> told(Supplier<A> result) {
    log = new ArrayList<>();
    A a = result.get();
    return new Pair<>(a, List.copyOf(log));
  }

  /** an account whose handler does with the deposit and the rest of the program what {@code on} says */
  static <B, R> Comp<B, R> account(
      BiFunction<Deposit, K<B, R>, Comp<B, R>> on, Comp<B, ? super Eff<Account, R>> p) {
    return handle(Account.class, new Handler<Account, B, B, R>() {
      public Comp<B, R> ret(B a) { return pure(a); }
      public Comp<B, R> on(Account op, K<B, R> k) {
        return switch (op) {
          case Deposit deposit -> on.apply(deposit, k);
        };
      }
    }, p);
  }

  static Comp<Integer, Eff<Account, ?>> deposited() {
    return perform(new Deposit(1)).ensuring(() -> said("closed"));
  }

  static Comp<Integer, Eff<Throws<String>, ?>> raised() { return Throws.raise("no"); }

  static Comp<Integer, Eff<Choose, ?>> opened(int x) {
    return Properties.<Eff<Choose, ?>>said("open " + x).then(() -> x == 1 ? Choose.<Integer>none() : pure(x))
        .ensuring(() -> said("closed " + x));
  }

  static Comp<Integer, Eff<Choose, ?>> tenfold(int x) {
    Comp<Integer, Eff<Choose, ?>> path = x == 2 ? Choose.none() : pure(x * 10);
    return path.ensuring(() -> said("closed " + x));
  }

  static Comp<Void, Eff<State<Integer>, ?>> tick() { return State.modify(s -> s + 1); }

  static Comp<Void, Eff<State<Integer>, ?>> saw() {
    return State.<Integer>get().flatMap(s -> said("saw " + s).then(() -> State.put(s + 1)));
  }

  /** a state left at 5 by a failure, seen by a finalizer: the state inside the handler of the failure */
  static Comp<Integer, Eff<State<Integer>, Eff<Throws<String>, ?>>> stateInside() {
    Comp<Integer, Eff<State<Integer>, Eff<Throws<String>, ?>>> failing =
        State.put(5).flatMap(done -> up(Throws.raise("no")));
    return failing.ensuring(() -> saw());
  }

  /** the same, the state outside the handler of the failure: the row the other way */
  static Comp<Integer, Eff<Throws<String>, Eff<State<Integer>, ?>>> stateOutside() {
    Comp<Integer, Eff<Throws<String>, Eff<State<Integer>, ?>>> failing =
        up(State.put(5)).flatMap(done -> Throws.raise("no"));
    return failing.ensuring(() -> up(saw()));
  }

  /** each path of a choice leaves its state, and has no value */
  static Comp<Integer, Eff<State<Integer>, Eff<Choose, ?>>> statePaths() {
    return up(Choose.among(List.of(1, 2))).flatMap(x -> {
      Comp<Integer, Eff<State<Integer>, Eff<Choose, ?>>> ended = State.put(x).flatMap(done -> up(Choose.none()));
      return ended.ensuring(() -> State.<Integer>get().flatMap(s -> said("saw " + s)));
    });
  }

  /** a failure, and a finalizer that fails with a number: the number's handler outside the failure's */
  static Comp<Integer, Eff<Throws<String>, ? extends Eff<Throws<Integer>, ?>>> closingOutside() {
    Comp<Integer, Eff<Throws<String>, ? extends Eff<Throws<Integer>, ?>>> failing = fit(Throws.raise("no"));
    return failing.ensuring(() -> up(Throws.<Integer, Void>raise(7)).then(() -> said("not run")))
        .ensuring(() -> said("outer"));
  }

  /** the same, the number's handler inside the failure's: the row the other way */
  static Comp<Integer, Eff<Throws<Integer>, ? extends Eff<Throws<String>, ?>>> closingInside() {
    Comp<Integer, Eff<Throws<Integer>, ? extends Eff<Throws<String>, ?>>> failing = fit(up(Throws.raise("no")));
    return failing.ensuring(() -> Throws.<Integer, Void>raise(7).then(() -> said("not run")))
        .ensuring(() -> said("outer"));
  }

  static Comp<Integer, Eff<Throws<String>, ?>> used(boolean fails) {
    return bracket(Properties.<Eff<Throws<String>, ?>>said("open").map(done -> "file"),
        f -> said("use " + f).then(() -> fails ? Throws.<String, Integer>raise("no") : pure(f.length())),
        f -> said("close " + f));
  }

  static Comp<Integer, Eff<State<Integer>, ?>> ticking(int n) {
    return n == 0 ? State.get() : tick().ensuring(() -> tick()).then(() -> ticking(n - 1));
  }

  /** an interceptor found by the class, in a row where the numbers' handler is one place out: every fresh number
   * that passes is seen, and asked again */
  static <A, R extends Eff<?, ? extends Eff<Fresh, ?>>> Comp<A, R> watched(List<String> seen, Comp<A, ? super R> p) {
    return intercept(Fresh.class, new Handler<Fresh, A, A, R>() {
      public Comp<A, R> ret(A a) { return pure(a); }
      public Comp<A, R> on(Fresh op, K<A, R> k) {
        seen.add(op.toString());
        return switch (op) {
          case Fresh.Next next -> up(perform(next)).flatMap(n -> k.resume(next, n));
        };
      }
    }, p);
  }

  /** a state written, a failure, and a finalizer that writes the state: the failure first in the row */
  static Comp<Void, Eff<Throws<String>, Eff<State<Integer>, ?>>> small() {
    Comp<Void, Eff<Throws<String>, Eff<State<Integer>, ?>>> failing =
        up(State.put(1)).flatMap(done -> Throws.raise("no"));
    return failing.ensuring(() -> up(State.put(2)));
  }

  /** an interceptor at the state's place in the row ({@code handle(.., under(p))}) that leaves at a put: the rest
   * is unwound, keeping the effect in the row ({@code K.unwindKeeping}) or not ({@code k.unwind()}); then the
   * state outside is read */
  static <T> Comp<Integer, Eff<State<Integer>, T>> leaving(
      boolean keeping, List<String> seen, Comp<Integer, ? super Eff<State<Integer>, T>> p) {
    return handle(State.class, new Handler<State<Integer>, Integer, Integer, Eff<State<Integer>, T>>() {
      public Comp<Integer, Eff<State<Integer>, T>> ret(Integer a) { return pure(a); }
      public Comp<Integer, Eff<State<Integer>, T>> on(State<Integer> op, K<Integer, Eff<State<Integer>, T>> k) {
        seen.add(op.toString());
        return switch (op) {
          case State.Put<Integer> put -> (keeping ? K.unwindKeeping(k, put) : k.unwind())
              .then(() -> State.<Integer>get().map(s -> s + 100));
          case State.Get<Integer> get -> perform(get).flatMap(s -> k.resume(get, s));
        };
      }
    }, under(p));
  }

  /** an interceptor found by the class that leaves at the number asked: the rest is unwound, then a number is
   * asked outside */
  static <R extends Eff<Fresh, ?>> Comp<Long, R> leavingFresh(List<String> seen, Comp<Long, ? super R> p) {
    return intercept(Fresh.class, new Handler<Fresh, Long, Long, R>() {
      public Comp<Long, R> ret(Long a) { return pure(a); }
      public Comp<Long, R> on(Fresh op, K<Long, R> k) {
        seen.add(op.toString());
        return switch (op) {
          case Fresh.Next next -> k.unwind().then(() -> perform(next)).map(n -> n + 100);
        };
      }
    }, p);
  }

  /** a finalizer that fails, and has a finalizer of its own */
  static Comp<Void, Eff<Throws<String>, ?>> closing() {
    return Properties.<Eff<Throws<String>, ?>>said("closing")
        .then(() -> Throws.<String, Void>raise("again")).then(() -> said("not run"))
        .ensuring(() -> said("inner"));
  }

  /** a failure, and a finalizer that fails at the next place of the row: the same effect, its handler outside */
  static Comp<Integer, Eff<Throws<String>, ? extends Eff<Throws<String>, ?>>> closingAtTheNext() {
    Comp<Integer, Eff<Throws<String>, ? extends Eff<Throws<String>, ?>>> failing = fit(Throws.raise("no"));
    return failing.ensuring(() -> up(Throws.<String, Void>raise("other")).then(() -> said("not run")))
        .ensuring(() -> said("outer"));
  }

  /** a state put to 5 and a deposit, under a finalizer that sees the state: the state's handler inside the
   * account's */
  static Comp<Integer, Eff<State<Integer>, Eff<Account, ?>>> depositedOver() {
    Comp<Integer, Eff<State<Integer>, Eff<Account, ?>>> body = State.put(5).flatMap(done ->
        up(perform(new Deposit(1))).flatMap(n -> State.<Integer>get().map(s -> s + n)));
    return body.ensuring(() -> saw());
  }

  public static void main(String[] args) {
    test("a row: handlers take their effects one by one, a program runs when none is left", () -> {
      var got = run(Throws.run(Writer.run(State.run(1, logged()))));
      same(got, new Throws.Ok<>(new Writer.Logged<>(List.of("was 1"), new State.Final<>(2, 2))));
    });

    test("the order of handlers is the meaning: state kept or lost at a failure", () -> {
      same(run(State.run(0, Throws.run(up(State.put(5)).flatMap(put -> Throws.raise("no"))))),
          new State.Final<>(5, new Throws.Err<>("no")));
      same(run(Throws.run(State.run(0, State.put(5).flatMap(put -> up(Throws.raise("no")))))), new Throws.Err<>("no"));
    });

    test("constant stack: a million operations", () ->
        same(run(State.run(0, count(1_000_000))), new State.Final<>(1_000_000, 1_000_000)));

    test("constant stack: a hundred thousand binds nested to the left", () ->
        same(run(State.run(0, nested(100_000))), new State.Final<>(100_000, 100_000)));

    test("mutual tail recursion without end: a million calls", () -> same(run(even(1_000_000)), true));

    test("a continuation taken many times: every path of a choice", () -> {
      List<Pair<Integer, Integer>> pairs = run(Choose.all(Choose.among(List.of(1, 2, 3)).flatMap(x ->
          Choose.among(List.of(10, 20)).map(y -> new Pair<>(x, y)))));
      same(pairs, List.of(new Pair<>(1, 10), new Pair<>(1, 20), new Pair<>(2, 10), new Pair<>(2, 20),
          new Pair<>(3, 10), new Pair<>(3, 20)));
    });

    test("many times, with state: each path its own state, or one state through all", () -> {
      same(run(Choose.all(State.run(0, up(Choose.among(List.of(1, 2))).flatMap(x -> add(x))))),
          List.of(new State.Final<>(1, 1), new State.Final<>(2, 2)));
      same(run(State.run(0, Choose.all(Choose.among(List.of(1, 2)).flatMap(x -> up(add(x)))))),
          new State.Final<>(3, List.of(1, 3)));
    });

    test("an endless stream, pulled", () -> {
      same(first(3, Emit.pull(from(0))), List.of(0, 1, 2));
      Iterator<Integer> stream = Emit.pull(from(0));
      for (int i = 0; i < 1_000_000; i++) stream.next();
      same(stream.next(), 1_000_000);
    });

    test("asynchrony: the handler returns, the answer resumes the program later", () -> {
      ArrayDeque<Runnable> waiting = new ArrayDeque<>();
      List<Integer> result = new ArrayList<>();
      Async.start(later(waiting, 1).flatMap(a -> later(waiting, 2).map(b -> a + b)), result::add);
      same(result, List.of());
      while (!waiting.isEmpty()) waiting.pop().run();
      same(result, List.of(3));
    });

    test("interception and audit: an operation seen, changed, and asked again", () -> {
      List<String> seen = new ArrayList<>();
      same(run(State.run(0, audit(seen, count(5)))), new State.Final<>(2, 2));
      same(seen.stream().filter(s -> s.startsWith("Put")).count(), 5L);
    });

    test("delegation: an effect answered by the operations of another", () -> {
      var got = run(State.run(100, onState(perform(new Deposit(10)).then(() -> perform(new Deposit(5))))));
      same(got, new State.Final<>(115, 115));
    });

    test("reader: a value of the surroundings, and changed for a part of the program", () ->
        same(run(Reader.run(2, Reader.<Integer>ask().flatMap(a ->
            Reader.local(v -> v * 10, Reader.<Integer>ask()).map(b -> new Pair<>(a, b))))), new Pair<>(2, 20)));

    test("a failure recovered; the first of the choices; fresh numbers; a clock that a test sets", () -> {
      same(run(Throws.recover(e -> pure(e.length()), Throws.raise("no"))), 2);
      same(run(Choose.first(oddOnly())), Optional.of(5));
      same(run(Choose.all(oddOnly())), List.of(5, 7));
      same(run(Fresh.run(Fresh.fresh().flatMap(a -> Fresh.fresh().map(b -> new Pair<>(a, b))))), new Pair<>(0L, 1L));
      same(run(Clock.at(() -> 42L, Clock.now())), 42L);
    });

    test("streams: collected, looped over, mapped, filtered, cut short, zipped, merged", () -> {
      same(run(Emit.collect(three())), new Emit.Sent<>(List.of(1, 2, 3), null));
      same(run(State.run(0, Emit.each(x -> State.modify(s -> s + x), three()))).state(), 6);
      // from the inside out: the endless stream, its even values, each doubled, the first three
      var evens = run(Emit.collect(Emit.take(3, Emit.map(x -> x * 2, Emit.filter(x -> x % 2 == 0, from(0))))));
      same(evens.values(), List.of(0, 4, 8));
      Iterator<Pair<Integer, Integer>> zipped = Emit.pull(Emit.zip(from(0), three()));
      same(first(9, zipped), List.of(new Pair<>(0, 1), new Pair<>(1, 2), new Pair<>(2, 3)));
      same(first(5, Emit.pull(Emit.merge(three(), from(10)))), List.of(1, 10, 2, 11, 3));
    });

    test("asynchrony: both at once, the first of two, all, a sleep, a future", () -> {
      same(Async.runBlocking(Async.both(after(30, 1), after(10, 2))), new Pair<>(1, 2));
      same(Async.runBlocking(Async.race(after(200, 1), after(10, 2))), 2);
      same(Async.runBlocking(Async.all(List.of(after(20, 1), after(10, 2), after(1, 3)))), List.of(1, 2, 3));
      same(Async.toFuture(after(5, 7).map(x -> x + 1)).get(5, TimeUnit.SECONDS), 8);
      CompletableFuture<Integer> late = CompletableFuture.supplyAsync(() -> 4);
      same(Async.runBlocking(Async.fromFuture(() -> late)), new Throws.Ok<>(4));
    });

    test("asynchrony: a million answers given at once take no stack", () ->
        same(Async.runBlocking(answered(1_000_000, 0)), 1_000_000));

    test("no handler: an operation that reached the top is refused by name", () -> {
      Comp<Integer, Eff<State<Integer>, ?>> open = State.get();
      try {
        run(closed(open));
        throw new AssertionError("ran");
      } catch (NoHandler e) {
        same(e.op, new State.Get<>());
      }
    });

    test("two handlers of one class: an operation goes to the one its place in the row says", () -> {
      same(run(Reader.run(1, Reader.run("s", up(Reader.<Integer>ask()).map(x -> x + 1)))), 2);
      same(run(Reader.run(1, Reader.run("s", up(Reader.<Integer>ask().map(x -> x + 1))))), 2);
      same(run(State.run(1, State.run("a", twoStates()))), new State.Final<>(2, new State.Final<>("ab", "ab2")));
    });

    test("two places up, past a handler of the operation's own class", () -> {
      same(run(Reader.run(7, Reader.run("s", Reader.run(1.5, up(up(Reader.<Integer>ask())))))), 7);
      same(run(Reader.run(7, Reader.run("s", Reader.run(1.5, up(up(Reader.<Integer>ask().map(x -> x + 1))))))), 8);
      same(run(Reader.run(7, Reader.run("s", up(Reader.run(1.5, up(Reader.<Integer>ask().map(x -> x + 1))))))), 8);
    });

    test("under: a handler put between the first effect and the rest", () -> {
      same(run(Reader.run(5, Reader.run("s", State.run(0, under(stateOverReader()))))), new State.Final<>(5, 5));
      same(run(Reader.run(5, Reader.run("s", Reader.run(1.5, State.run(0, under(under(stateOverReader()))))))),
          new State.Final<>(5, 5));
      same(run(Reader.run(5, State.run(0, Reader.run("s", up(stateOverReader()))))), new State.Final<>(5, 5));
    });

    test("constant stack: a million operations under a marker", () ->
        same(run(State.run(0, Reader.run("s", up(count(1_000_000))))), new State.Final<>(1_000_000, 1_000_000)));

    test("a continuation resumed twice across a marker: each path its own states", () ->
        same(run(Choose.all(State.run(0, State.run("", chosenAcross())))), List.of(
            new State.Final<>(1, new State.Final<>("1", 1)), new State.Final<>(2, new State.Final<>("2", 2)))));

    test("a question no handler answers, asked under a marker: the rest resumed with the answer", () -> {
      Step<Integer, ?> at = step(up(State.<Integer>get().flatMap(s -> State.put(s + 1).map(done -> s))));
      at = resumed(at, 4);
      same(at instanceof Step.Asked<?, ?, ?> asked ? asked.op() : null, new State.Put<>(5));
      same(resumed(at, null), new Step.Done<>(4));
    });

    test("an interceptor between two handlers of one class", () -> {
      // at a place in the row: the inner reader's asks changed, the outer one's pass
      same(run(Reader.run(1, Reader.run("s", Reader.local(s -> s + "!", twoReaders())))), "s!1");
      same(run(Reader.run(1, Reader.local(n -> n * 10, Reader.run("s", twoReaders())))), "s10");
      // by the class: what goes to the outer handler passes the interceptor, what goes to the inner does not
      List<String> seen = new ArrayList<>();
      same(run(Fresh.run(hundred(seen, Fresh.run(twoFresh())))), new Pair<>(0L, 100L));
      same(seen, List.of("Next[]"));
    });

    test("ensuring, a value: the finalizer runs after the program, and the program's value is the result", () -> {
      Comp<Integer, Pure> p =
          Properties.<Pure>said("body").map(done -> 1).ensuring(() -> said("closed").map(done -> 2));
      same(told(() -> run(p)), new Pair<>(1, List.of("body", "closed")));
      // a finalizer is made when it runs: building the program runs nothing
      log = new ArrayList<>();
      Comp<Integer, Pure> built = Effects.<Integer, Pure>pure(1).ensuring(() -> {
        log.add("closed");
        return pure(null);
      });
      same(log, List.of());
      same(told(() -> run(built)), new Pair<>(1, List.of("closed")));
    });

    test("ensuring, a failure: the finalizer runs before the failure is answered", () -> {
      Comp<Integer, Eff<Throws<String>, ?>> p = Properties.<Eff<Throws<String>, ?>>said("body")
          .then(() -> raised()).ensuring(() -> said("closed"));
      Comp<Integer, Pure> rescued = Throws.recover(e -> said("rescued " + e).map(done -> 0), p);
      same(told(() -> run(rescued)), new Pair<>(0, List.of("body", "closed", "rescued no")));
      same(told(() -> run(Throws.run(p))), new Pair<>(new Throws.Err<>("no"), List.of("body", "closed")));
    });

    test("ensuring, a handler's abort: a handler that does not go on unwinds what it leaves", () ->
        same(told(() -> run(account((deposit, k) -> k.unwind().then(() -> pure(-1)), deposited()))),
            new Pair<>(-1, List.of("closed"))));

    test("ensuring, the first of the choices: a path with no value is unwound, a path not begun holds nothing", () ->
        same(told(() -> run(Choose.first(Choose.among(List.of(1, 2, 3)).flatMap(x -> opened(x))))),
            new Pair<>(Optional.of(2), List.of("open 1", "closed 1", "open 2", "closed 2"))));

    test("ensuring, a stream cut short: the producer is unwound where it stopped", () -> {
      same(told(() -> run(Emit.collect(Emit.take(2, from(0).ensuring(() -> said("closed")))))),
          new Pair<>(new Emit.Sent<>(List.of(0, 1), null), List.of("closed")));
      // what a finalizer sends when the stream is cut goes to the stream's handler: the cut has left
      same(run(Emit.collect(Emit.take(2, from(0).ensuring(() -> Emit.send(-1))))).values(), List.of(0, 1, -1));
      same(first(9, Emit.pull(Emit.take(2, from(0).ensuring(() -> Emit.send(-1))))), List.of(0, 1, -1));
    });

    test("ensuring, every path of a choice: a finalizer after the choice runs once in each path", () ->
        same(told(() -> run(Choose.all(Choose.among(List.of(1, 2, 3)).flatMap(x -> tenfold(x))))),
            new Pair<>(List.of(10, 30), List.of("closed 1", "closed 2", "closed 3"))));

    test("ensuring, every path of a choice: a finalizer before the choice runs once for each path that passes it",
        () -> {
          Comp<Integer, Eff<Choose, ?>> chosen =
              Choose.among(List.of(1, 2, 3)).flatMap(x -> said("path " + x).map(done -> x));
          Comp<Integer, Eff<Choose, ?>> p =
              chosen.ensuring(() -> said("closed")).flatMap(x -> x == 2 ? Choose.none() : pure(x));
          same(told(() -> run(Choose.all(p))), new Pair<>(List.of(1, 3),
              List.of("path 1", "closed", "path 2", "closed", "path 3", "closed")));
        });

    test("ensuring, nested: the innermost finalizer first, at a value and at a failure", () -> {
      Comp<Integer, Eff<Throws<String>, ?>> one = pure(1);
      same(told(() -> run(Throws.run(one.ensuring(() -> said("inner")).ensuring(() -> said("outer"))))),
          new Pair<>(new Throws.Ok<>(1), List.of("inner", "outer")));
      Comp<Integer, Eff<Throws<String>, ?>> failed = raised().ensuring(() -> said("inner"))
          .then(() -> said("not run").map(done -> 2)).ensuring(() -> said("outer"));
      same(told(() -> run(Throws.run(failed))), new Pair<>(new Throws.Err<>("no"), List.of("inner", "outer")));
    });

    test("ensuring, state: a finalizer reads and writes the state as its path left it, inside the handler or outside",
        () -> {
          // the state inside the handler that leaves: it is in what is unwound, and is dropped after the finalizer
          same(told(() -> run(Throws.run(State.run(0, stateInside())))),
              new Pair<>(new Throws.Err<>("no"), List.of("saw 5")));
          // the state outside it: what the finalizer wrote stays
          same(told(() -> run(State.run(0, Throws.run(stateOutside())))),
              new Pair<>(new State.Final<>(6, new Throws.Err<>("no")), List.of("saw 5")));
          // each path of a choice has the state it left
          same(told(() -> run(Choose.all(State.run(0, statePaths())))),
              new Pair<>(List.of(), List.of("saw 1", "saw 2")));
        });

    test("ensuring, a finalizer that fails at a value: the failure is the result, and what waits outside is unwound",
        () -> {
          Comp<Integer, Eff<Throws<String>, ?>> one = pure(1);
          Comp<Integer, Eff<Throws<String>, ?>> p =
              one.ensuring(() -> Throws.raise("closing")).ensuring(() -> said("outer"));
          same(told(() -> run(Throws.run(p))), new Pair<>(new Throws.Err<>("closing"), List.of("outer")));
        });

    test("ensuring, a finalizer that fails in an unwinding: the unwinding goes on, the failure goes to its handler",
        () -> {
          // its handler is outside the one that leaves: it unwinds the rest, and its answer is the result
          same(told(() -> run(Throws.run(Throws.run(closingOutside())))),
              new Pair<>(new Throws.Err<>(7), List.of("outer")));
          // its handler is inside what is unwound: the failure is answered there, and the unwinding goes on from there
          Comp<Void, Eff<Throws<String>, ?>> inside =
              Throws.recover(n -> said("rescued " + n).map(done -> 0), closingInside()).then(() -> said("not run"));
          same(told(() -> run(Throws.run(inside))),
              new Pair<>(new Throws.Err<>("no"), List.of("outer", "rescued 7")));
          // its handler is the one that leaves: nobody answers, the finalizer ends there, the first failure stands
          Comp<Integer, Eff<Throws<String>, ?>> own = raised()
              .ensuring(() -> Throws.<String, Void>raise("closing").then(() -> said("not run")))
              .ensuring(() -> said("outer"));
          same(told(() -> run(Throws.run(own))), new Pair<>(new Throws.Err<>("no"), List.of("outer")));
        });

    test("ensuring, `k` is a value: unwound twice the finalizers run twice; resumed after, its path ends once more",
        () -> {
          Comp<Integer, Eff<Account, ?>> p = perform(new Deposit(1))
              .flatMap(n -> said("got " + n).map(done -> n)).ensuring(() -> said("closed"));
          Comp<Integer, Pure> handled = account((deposit, k) ->
              k.unwind().then(() -> k.unwind()).then(() -> k.resume(deposit, deposit.amount() + 1)), p);
          same(told(() -> run(handled)), new Pair<>(2, List.of("closed", "closed", "got 2", "closed")));
        });

    test("ensuring, the rest of an unwinding kept by a handler inside it: resumed where the unwinding is not, refused",
        () -> {
          Comp<Integer, Eff<Account, ? extends Eff<Throws<String>, ?>>> failing = fit(up(Throws.raise("no")));
          Comp<Integer, Eff<Account, ? extends Eff<Throws<String>, ?>>> p =
              failing.ensuring(() -> perform(new Deposit(1)).then(() -> said("closed")));
          List<Comp<Integer, Eff<Throws<String>, ?>>> kept = new ArrayList<>();
          // the finalizer waits at its operation; the unwinding goes on without it
          Comp<Integer, Eff<Throws<String>, ?>> left = account((deposit, k) -> {
            kept.add(k.resume(deposit, deposit.amount()));
            return pure(0);
          }, p);
          same(told(() -> run(Throws.run(left))), new Pair<>(new Throws.Err<>("no"), List.of()));
          // resumed, the finalizer ends — and there is no unwinding to go on with, and no value
          try {
            run(Throws.run(kept.get(0)));
            throw new AssertionError("ran");
          } catch (NoUnwinding refused) {
            same(log, List.of("closed"));
          }
        });

    test("ensuring, `k` neither resumed nor unwound: nothing of it runs", () ->
        same(told(() -> run(account((deposit, k) -> pure(0), deposited()))), new Pair<>(0, List.of())));

    test("ensuring, a stream pulled and not read to its end: no finalizer runs; read to its end, it runs", () -> {
      // the iterator keeps the rest of the producer, and nobody leaves it
      same(told(() -> first(3, Emit.pull(from(0).ensuring(() -> said("closed"))))),
          new Pair<>(List.of(0, 1, 2), List.of()));
      same(told(() -> first(2, Emit.pull(three().ensuring(() -> said("closed"))))),
          new Pair<>(List.of(1, 2), List.of()));
      // every value taken, the end not asked for: the producer still waits at its last value
      same(told(() -> first(3, Emit.pull(three().ensuring(() -> said("closed"))))),
          new Pair<>(List.of(1, 2, 3), List.of()));
      same(told(() -> first(9, Emit.pull(three().ensuring(() -> said("closed"))))),
          new Pair<>(List.of(1, 2, 3), List.of("closed")));
    });

    test("ensuring, asynchrony: a program that waits is kept, and its finalizer runs when it ends", () -> {
      ArrayDeque<Runnable> waiting = new ArrayDeque<>();
      List<Integer> result = new ArrayList<>();
      Comp<Integer, Eff<Async, ?>> p = later(waiting, 1).map(x -> x + 1).ensuring(() -> said("closed"));
      same(told(() -> { Async.start(p, result::add); return result.size(); }), new Pair<>(0, List.of()));
      same(told(() -> { waiting.pop().run(); return result; }), new Pair<>(List.of(2), List.of("closed")));
    });

    test("bracket: acquired, used, released — when the use ends and when it fails", () -> {
      same(told(() -> run(Throws.run(used(false)))),
          new Pair<>(new Throws.Ok<>(4), List.of("open", "use file", "close file")));
      same(told(() -> run(Throws.run(used(true)))),
          new Pair<>(new Throws.Err<>("no"), List.of("open", "use file", "close file")));
    });

    test("ensuring, constant stack: a million finalizers in a loop", () ->
        same(run(State.run(0, ticking(1_000_000))), new State.Final<>(2_000_000, 2_000_000)));

    test("ensuring, constant stack: a continuation with a hundred thousand waiting finalizers is unwound", () -> {
      Comp<Integer, Eff<Throws<String>, ? extends Eff<State<Integer>, ?>>> p = fit(Throws.raise("no"));
      for (int i = 0; i < 100_000; i++) p = p.ensuring(() -> up(tick()));
      same(run(State.run(0, Throws.run(p))), new State.Final<>(100_000, new Throws.Err<>("no")));
    });

    test("ensuring, an interceptor between the finalizer and the handler that leaves sees what the finalizer asks",
        () -> {
          List<String> seen = new ArrayList<>();
          Comp<Integer, Eff<Throws<String>, Eff<Fresh, ?>>> failing =
              up(Fresh.fresh()).flatMap(n -> Throws.raise("no"));
          Comp<Integer, Eff<Throws<String>, Eff<Fresh, ?>>> p =
              failing.ensuring(() -> up(Fresh.fresh()).flatMap(n -> said("fresh " + n)));
          same(told(() -> run(Fresh.run(Throws.run(watched(seen, p))))),
              new Pair<>(new Throws.Err<>("no"), List.of("fresh 1")));
          same(seen, List.of("Next[]", "Next[]"));
        });

    test("ensuring, a program under a marker (`up`, `under`): its finalizer runs there, its places counted", () -> {
      Comp<Void, Eff<Writer<String>, Eff<Throws<String>, Eff<State<Integer>, ?>>>> over =
          Writer.tell("before").flatMap(told -> up(small()).flatMap(left -> Writer.tell("after")));
      same(run(State.run(0, Throws.run(Writer.run(over)))), new State.Final<>(2, new Throws.Err<>("no")));
      Comp<Void, Eff<Throws<String>, Eff<Writer<String>, Eff<State<Integer>, ?>>>> between =
          up(Writer.tell("before")).flatMap(told -> under(small()).flatMap(left -> up(Writer.tell("after"))));
      same(run(State.run(0, Writer.run(Throws.run(between)))),
          new State.Final<>(2, new Writer.Logged<>(List.of("before"), new Throws.Err<>("no"))));
    });

    test("ensuring, an operation no handler answers: whoever steps the program resumes its rest, or unwinds it",
        () -> {
          if (!(step(deposited()) instanceof Step.Asked<?, Integer, ?> asked)) throw new AssertionError("no operation");
          same(told(() -> step(asked.k().unwind())), new Pair<>(new Step.Done<>(null), List.of("closed")));
        });

    test("ensuring, an interceptor that unwinds: what a finalizer asks of its own effect reaches the handler outside",
        () -> {
          // Scala's interceptor is of a place of the row. Java's `intercept` is by the class, and refuses an effect
          // with type arguments, as the state is: here the interceptor at a place is `handle(.., under(p))`, and
          // it leaves with `K.unwindKeeping`
          List<String> seen = new ArrayList<>();
          Comp<Integer, Eff<State<Integer>, ?>> body = State.put(1).then(() -> said("not run").map(done -> 0));
          Comp<Integer, Eff<State<Integer>, ?>> p =
              body.ensuring(() -> State.<Integer>get().flatMap(s -> State.put(s + 9)));
          // the finalizer read the 5 outside and wrote 14 there: the put of 1 never passed the interceptor
          same(told(() -> run(State.run(5, leaving(true, seen, p)))),
              new Pair<>(new State.Final<>(14, 114), List.of()));
          // the interceptor has left: it saw the put it left at, and nothing of the finalizer
          same(seen, List.of("Put[value=1]"));
          // by the class, of an effect with no type arguments: `k.unwind()` leaves nothing where the interceptor
          // was, and the finalizer's number is the handler's outside
          List<String> numbers = new ArrayList<>();
          Comp<Long, Eff<Fresh, ?>> asking = Fresh.fresh().flatMap(n -> said("not run").map(done -> n))
              .ensuring(() -> Fresh.fresh().flatMap(n -> said("fresh " + n)));
          same(told(() -> run(Fresh.run(leavingFresh(numbers, asking)))), new Pair<>(101L, List.of("fresh 0")));
          same(numbers, List.of("Next[]"));
        });

    test("ensuring, an interceptor at a place that leaves with plain `k.unwind()`: it is gone, as under `handle`",
        () -> {
          Comp<Integer, Eff<State<Integer>, ?>> body = State.put(1).then(() -> said("not run").map(done -> 0));
          Supplier<Comp<Void, Eff<State<Integer>, ?>>> closing = () ->
              Properties.<Eff<State<Integer>, ?>>said("closing").then(() -> State.<Integer>get())
                  .flatMap(s -> said("read " + s).then(() -> State.put(s + 9))).ensuring(() -> said("inner"));
          Comp<Integer, Eff<State<Integer>, ?>> p = body.ensuring(closing).ensuring(() -> said("outer"));
          // nobody answers the finalizer's get: it ends there, through its own finalizer, and the unwinding goes
          // on. The state outside is not written; the interceptor, after the unwinding, reads it
          List<String> seen = new ArrayList<>();
          same(told(() -> run(State.run(5, leaving(false, seen, p)))),
              new Pair<>(new State.Final<>(5, 105), List.of("closing", "inner", "outer")));
          same(seen, List.of("Put[value=1]"));
          // the same program, left with `K.unwindKeeping`: the get is answered outside, and the finalizer ends
          List<String> kept = new ArrayList<>();
          same(told(() -> run(State.run(5, leaving(true, kept, p)))),
              new Pair<>(new State.Final<>(14, 114), List.of("closing", "read 5", "inner", "outer")));
          same(kept, List.of("Put[value=1]"));
        });

    test("ensuring, a finalizer stopped at an operation of the handler that left: its own finalizer still runs",
        () -> {
          // the finalizer fails as the program did; that handler has left, under `handle`: nobody answers
          Comp<Integer, Eff<Throws<String>, ?>> p = raised()
              .ensuring(() -> closing().then(() -> said("not run"))).ensuring(() -> said("outer"));
          // it ends at its operation, through its own finalizer; the unwinding goes on, and the first failure stands
          same(told(() -> run(Throws.run(p))),
              new Pair<>(new Throws.Err<>("no"), List.of("closing", "inner", "outer")));
          // the same effect at another place of the row, its handler outside the one that left: answered there
          same(told(() -> run(Throws.run(Throws.run(closingAtTheNext())))),
              new Pair<>(new Throws.Err<>("other"), List.of("outer")));
        });

    test("ensuring, `k` unwound twice, then resumed, a handler in place inside it: each from the state `k` took",
        () -> {
          Comp<State.Final<Integer, Integer>, Pure> handled = account((deposit, k) ->
              k.unwind().then(() -> k.unwind()).then(() -> k.resume(deposit, deposit.amount() + 1)),
              State.run(0, depositedOver()));
          // each finalizer saw the 5 that `k` holds, and wrote its 6 in a state of its own path; the last path's
          // is kept
          same(told(() -> run(handled)), new Pair<>(new State.Final<>(6, 7), List.of("saw 5", "saw 5", "saw 5")));
        });

    test("ensuring, the rest of a stepped program unwound twice, then resumed: its finalizers run each time", () -> {
      Comp<Integer, Eff<Account, ?>> p = perform(new Deposit(1))
          .flatMap(n -> said("got " + n).map(done -> n)).ensuring(() -> said("closed"));
      Step<Integer, Eff<Account, ?>> at = step(p);
      if (!(at instanceof Step.Asked<?, Integer, ?> asked)) throw new AssertionError("no operation");
      same(told(() -> step(asked.k().unwind())), new Pair<>(new Step.Done<>(null), List.of("closed")));
      same(told(() -> step(asked.k().unwind())), new Pair<>(new Step.Done<>(null), List.of("closed")));
      int amount = asked.op() instanceof Deposit deposit ? deposit.amount() : -1;
      same(told(() -> resumed(at, amount + 1)), new Pair<>(new Step.Done<>(2), List.of("got 2", "closed")));
    });

    Audits.tests();

    test("a thousand random programs: the reference machine's digest", () -> same(Tck.digest(), Tck.DIGEST));

    System.out.println(failed == 0 ? "java: all passed" : "java: " + failed + " FAILED");
    System.exit(failed == 0 ? 0 : 1);
  }
}
