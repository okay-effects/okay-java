package okay.rows.std;

import static okay.rows.Effects.*;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import okay.rows.Cell;
import okay.rows.Comp;
import okay.rows.Eff;
import okay.rows.Handler;
import okay.rows.InPlace;
import okay.rows.K;
import okay.rows.Pure;
import okay.rows.Op;

/** EMIT: a stream of values, possibly endless. The producer is a program that sends; what a stream is made into —
 * a list, a loop, another stream, a lazy sequence pulled a value at a time — is the handler's choice. A handler
 * that makes a stream of a stream sends to the stream next in the row. */
public sealed interface Emit<T> {
  record Send<T>(T value) implements Emit<T>, Op<Emit<T>, Void, Send<T>> {}

  static <T> Comp<Void, Eff<Emit<T>, ?>> send(T value) { return perform(new Send<>(value)); }

  /** every value of a sequence, which may be lazy and endless */
  static <T> Comp<Void, Eff<Emit<T>, ?>> sendAll(Iterable<T> values) { return sendAll(Lazy.of(values.iterator())); }

  private static <T> Comp<Void, Eff<Emit<T>, ?>> sendAll(Lazy<T> values) {
    return defer(() -> {
      Lazy.Next<T> next = values.next();
      return next == null ? pure(null) : send(next.value()).then(() -> sendAll(next.rest()));
    });
  }

  /** the values sent, with the program's own */
  record Sent<T, A>(List<T> values, A value) {}

  static <T, A, R> Comp<Sent<T, A>, R> collect(Comp<A, ? super Eff<Emit<T>, R>> p) {
    return handle(Emit.class, new InPlace<Emit<T>, Chain<T>, A, Sent<T, A>, R>() {
      public Comp<Sent<T, A>, R> ret(Chain<T> sent, A a) { return pure(new Sent<>(Chain.list(sent), a)); }
      public Cell.Answered answer(Cell<Chain<T>> sent, Emit<T> op) {
        return switch (op) {
          case Send<T> send -> { sent.value = new Chain<>(send.value(), sent.value); yield sent.answer(send, null); }
        };
      }
    }, null, p);
  }

  /** {@code f} run for each value, as it is sent */
  static <T, A, R> Comp<A, R> each(Function<T, ? extends Comp<Void, ? super R>> f, Comp<A, ? super Eff<Emit<T>, R>> p) {
    return handle(Emit.class, new Handler<Emit<T>, A, A, R>() {
      public Comp<A, R> ret(A a) { return pure(a); }
      public Comp<A, R> on(Emit<T> op, K<A, R> k) {
        return switch (op) {
          case Send<T> send -> f.apply(send.value()).flatMap(done -> k.resume(send, done));
        };
      }
    }, p);
  }

  /** a stream of the values, each through {@code f} */
  static <T, U, A, R> Comp<A, Eff<Emit<U>, R>> map(
      Function<T, U> f, Comp<A, ? super Eff<Emit<T>, Eff<Emit<U>, R>>> p) {
    return handle(Emit.class, new Handler<Emit<T>, A, A, Eff<Emit<U>, R>>() {
      public Comp<A, Eff<Emit<U>, R>> ret(A a) { return pure(a); }
      public Comp<A, Eff<Emit<U>, R>> on(Emit<T> op, K<A, Eff<Emit<U>, R>> k) {
        return switch (op) {
          case Send<T> send -> send(f.apply(send.value())).flatMap(done -> k.resume(send, done));
        };
      }
    }, p);
  }

  /** a stream of the values that {@code keep} */
  static <T, A, R> Comp<A, Eff<Emit<T>, R>> filter(
      Predicate<T> keep, Comp<A, ? super Eff<Emit<T>, Eff<Emit<T>, R>>> p) {
    return handle(Emit.class, new Handler<Emit<T>, A, A, Eff<Emit<T>, R>>() {
      public Comp<A, Eff<Emit<T>, R>> ret(A a) { return pure(a); }
      public Comp<A, Eff<Emit<T>, R>> on(Emit<T> op, K<A, Eff<Emit<T>, R>> k) {
        return switch (op) {
          case Send<T> send -> keep.test(send.value())
              ? send(send.value()).flatMap(done -> k.resume(send, done))
              : k.resume(send, null);
        };
      }
    }, p);
  }

  /** a stream of the first {@code n} values; the producer is not resumed after them: it is left there, unwound.
   * What its finalizers send then goes to the stream's handler, past this cut. */
  static <T, A, R> Comp<Void, Eff<Emit<T>, R>> take(int n, Comp<A, ? super Eff<Emit<T>, Eff<Emit<T>, R>>> p) {
    return taken(n, p);
  }

  /** {@code take}; the row under the stream cut, the stream it sends to first, has a name here: {@code R} */
  private static <T, A, R extends Eff<Emit<T>, ?>> Comp<Void, R> taken(int n, Comp<A, ? super Eff<Emit<T>, R>> p) {
    // the program's answer is a function of how many values are left to take
    Comp<Function<Integer, Comp<Void, R>>, R> taking = handle(
        Emit.class, new Handler<Emit<T>, A, Function<Integer, Comp<Void, R>>, R>() {
          public Comp<Function<Integer, Comp<Void, R>>, R> ret(A a) { return pure(left -> pure(null)); }
          public Comp<Function<Integer, Comp<Void, R>>, R> on(Emit<T> op, K<Function<Integer, Comp<Void, R>>, R> k) {
            return switch (op) {
              case Send<T> send -> pure(left -> left <= 0 ? K.unwindKeeping(k, send) : send(send.value()).then(() ->
                  left == 1 ? K.unwindKeeping(k, send) : k.resume(send, null).flatMap(rest -> rest.apply(left - 1))));
            };
          }
        }, p);
    return taking.flatMap(rest -> rest.apply(n));
  }

  /** the values, pulled as they are needed: the producer runs no further than was asked. NOT READ TO ITS END, the
   * sequence keeps the rest of the producer and nobody leaves it: its finalizers ({@code ensuring}) do not run. A
   * producer that holds something is read to its end, or cut by {@code take} before it is pulled:
   * {@code pull(take(n, p))}. The same holds of {@code zip} and {@code merge}, which pull: of the stream that is
   * not read to its end. */
  static <T, A> Iterator<T> pull(Comp<A, ? super Eff<Emit<T>, Pure>> p) {
    Lazy<T> values = new Lazy<>(() -> runIsolated(handle(Emit.class, new Handler<Emit<T>, A, Lazy.Next<T>, Pure>() {
      public Comp<Lazy.Next<T>, Pure> ret(A a) { return pure(null); }
      public Comp<Lazy.Next<T>, Pure> on(Emit<T> op, K<Lazy.Next<T>, Pure> k) {
        return switch (op) {
          case Send<T> send -> pure(new Lazy.Next<>(send.value(), new Lazy<>(() -> runIsolated(k.resume(send, null)))));
        };
      }
    }, p)));
    return new Lazy.Values<>(values);
  }

  /** two streams, value by value in pairs, as long as both last */
  static <T, U, A, B> Comp<Void, Eff<Emit<Pair<T, U>>, ?>> zip(
      Comp<A, ? super Eff<Emit<T>, Pure>> a, Comp<B, ? super Eff<Emit<U>, Pure>> b) {
    return sendAll(() -> {
      Iterator<T> first = pull(a);
      Iterator<U> second = pull(b);
      return new Iterator<>() {
        public boolean hasNext() { return first.hasNext() && second.hasNext(); }
        public Pair<T, U> next() { return new Pair<>(first.next(), second.next()); }
      };
    });
  }

  /** two streams in turn, a value of each; then what is left of the longer */
  static <T, A, B> Comp<Void, Eff<Emit<T>, ?>> merge(
      Comp<A, ? super Eff<Emit<T>, Pure>> a, Comp<B, ? super Eff<Emit<T>, Pure>> b) {
    return sendAll(() -> new Iterator<>() {
      private Iterator<T> first = pull(a);
      private Iterator<T> second = pull(b);
      public boolean hasNext() { return first.hasNext() || second.hasNext(); }
      public T next() {
        if (!first.hasNext()) return second.next();
        T value = first.next();
        Iterator<T> other = first;
        first = second;
        second = other;
        return value;
      }
    });
  }
}

/** A lazy sequence: a value is made once, when it is first asked for, and kept — so a program resumed twice from
 * the same place is sent the same values twice. */
final class Lazy<T> {
  /** a value and what follows it; {@code null} is the end */
  record Next<T>(T value, Lazy<T> rest) {}

  private Supplier<Next<T>> make;
  private Next<T> made;

  Lazy(Supplier<Next<T>> make) { this.make = make; }

  static <T> Lazy<T> of(Iterator<T> values) {
    return new Lazy<>(() -> values.hasNext() ? new Next<>(values.next(), of(values)) : null);
  }

  Next<T> next() {
    if (make != null) {
      made = make.get();
      make = null;
    }
    return made;
  }

  /** the values from a place on; those passed are not kept */
  static final class Values<T> implements Iterator<T> {
    private Lazy<T> left;

    Values(Lazy<T> from) { left = from; }

    public boolean hasNext() { return left.next() != null; }

    public T next() {
      Next<T> next = left.next();
      if (next == null) throw new NoSuchElementException();
      left = next.rest();
      return next.value();
    }
  }
}
