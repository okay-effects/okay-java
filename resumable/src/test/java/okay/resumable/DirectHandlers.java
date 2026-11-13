package okay.resumable;

import static okay.rows.Effects.handle;
import static okay.rows.Effects.intercept;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import okay.rows.Cell;
import okay.rows.Comp;
import okay.rows.Eff;
import okay.rows.std.State.Final;
import okay.rows.std.Throws;
import okay.rows.std.Writer;

/** The test effects' handlers, written in direct style: plain methods, their results values, the rest {@code k} a
 * direct-style continuation */
public final class DirectHandlers {
  private DirectHandlers() {}

  /** a state in place: each operation answered from the cell, on the body's stack */
  public static <A, R> Comp<Final<Integer, A>, R> counted(int s0, Comp<A, ? super Eff<Counter, R>> p) {
    return handle(Counter.class, Direct.inPlace(new Direct.InPlace<Counter, Integer, A, Final<Integer, A>>() {
      public Final<Integer, A> ret(Integer s, A a) { return new Final<>(s, a); }
      public Cell.Answered answer(Cell<Integer> state, Counter op) {
        return switch (op) {
          case Counter.Get get -> state.answer(get, state.value);
          case Counter.Put put -> {
            state.value = put.value;
            yield state.answer(put, null);
          }
        };
      }
    }), s0, p);
  }

  /** a state written generally: a field, every clause resuming in tail position */
  public static <A, R> Comp<Final<Integer, A>, R> general(int s0, Comp<A, ? super Eff<Counter, R>> p) {
    final class State implements Direct.Handler0<Counter, A, Final<Integer, A>> {
      int s = s0;

      public Final<Integer, A> ret(A a) { return new Final<>(s, a); }

      public Final<Integer, A> on(Counter op, Direct.K<Final<Integer, A>> k) throws Direct.Resumes {
        return switch (op) {
          case Counter.Get get -> k.resume(get, s);
          case Counter.Put put -> {
            s = put.value;
            yield k.resume(put, null);
          }
        };
      }
    }
    return handle(Counter.class, Direct.handler(new State()), p);
  }

  /** the same state, its clause resuming in tail position through a local: {@code var r = k.resume(..); return r} */
  public static <A, R> Comp<Final<Integer, A>, R> generalThroughALocal(int s0, Comp<A, ? super Eff<Counter, R>> p) {
    final class State implements Direct.Handler0<Counter, A, Final<Integer, A>> {
      int s = s0;

      public Final<Integer, A> ret(A a) { return new Final<>(s, a); }

      public Final<Integer, A> on(Counter op, Direct.K<Final<Integer, A>> k) throws Direct.Resumes {
        if (op instanceof Counter.Put put) {
          s = put.value;
          Final<Integer, A> r = k.resume(put, null);
          return r;
        }
        var r = k.resume((Counter.Get) op, s);
        return r;
      }
    }
    return handle(Counter.class, Direct.handler(new State()), p);
  }

  /** through a cast of a local, and an arm of a ternary */
  public static <A, R> Comp<Final<Integer, A>, R> generalThroughACastAndATernary(int s0,
      Comp<A, ? super Eff<Counter, R>> p) {
    final class State implements Direct.Handler0<Counter, A, Final<Integer, A>> {
      int s = s0;

      public Final<Integer, A> ret(A a) { return new Final<>(s, a); }

      @SuppressWarnings("unchecked")
      public Final<Integer, A> on(Counter op, Direct.K<Final<Integer, A>> k) throws Direct.Resumes {
        if (op instanceof Counter.Put put) {
          s = put.value;
          Object r = k.resume(put, null);
          return (Final<Integer, A>) r;
        }
        Counter.Get get = (Counter.Get) op;
        return s >= 0 ? k.resume(get, s) : k.resume(get, -s);
      }
    }
    return handle(Counter.class, Direct.handler(new State()), p);
  }

  /** through a switch's value kept in a local */
  public static <A, R> Comp<Final<Integer, A>, R> generalThroughASwitchsLocal(int s0,
      Comp<A, ? super Eff<Counter, R>> p) {
    final class State implements Direct.Handler0<Counter, A, Final<Integer, A>> {
      int s = s0;

      public Final<Integer, A> ret(A a) { return new Final<>(s, a); }

      public Final<Integer, A> on(Counter op, Direct.K<Final<Integer, A>> k) throws Direct.Resumes {
        Final<Integer, A> r = switch (op) {
          case Counter.Get get -> k.resume(get, s);
          case Counter.Put put -> {
            s = put.value;
            yield k.resume(put, null);
          }
        };
        return r;
      }
    }
    return handle(Counter.class, Direct.handler(new State()), p);
  }

  /** a count of the program's value: an int unboxed into a local and boxed again to be returned */
  public static <R> Comp<Integer, R> counting(Comp<Integer, ? super Eff<Counter, R>> p) {
    final class State implements Direct.Handler0<Counter, Integer, Integer> {
      int s;

      public Integer ret(Integer a) { return a; }

      public Integer on(Counter op, Direct.K<Integer> k) throws Direct.Resumes {
        if (op instanceof Counter.Put put) {
          s = put.value;
          int r = k.resume(put, null);
          return r;
        }
        int r = k.resume((Counter.Get) op, s);
        return r;
      }
    }
    return handle(Counter.class, Direct.handler(new State()), p);
  }

  /** NOT in tail position: the local is read for something else before it is returned */
  public static <A, R> Comp<Final<Integer, A>, R> generalLookingAtTheLocal(int s0, List<Object> seen,
      Comp<A, ? super Eff<Counter, R>> p) {
    final class State implements Direct.Handler0<Counter, A, Final<Integer, A>> {
      int s = s0;

      public Final<Integer, A> ret(A a) { return new Final<>(s, a); }

      public Final<Integer, A> on(Counter op, Direct.K<Final<Integer, A>> k) throws Direct.Resumes {
        if (op instanceof Counter.Put put) s = put.value;
        var r = op instanceof Counter.Get get ? k.resume(get, s) : k.resume((Counter.Put) op, null);
        seen.add(r);
        return r;
      }
    }
    return handle(Counter.class, Direct.handler(new State()), p);
  }

  /** every value the program comes to: the rest resumed once for each value, a choice of none unwinding it */
  public static <A, R> Comp<List<A>, R> all(Comp<A, ? super Eff<Pick, R>> p) {
    return handle(Pick.class, Direct.handler(new Direct.Handler0<Pick, A, List<A>>() {
      public List<A> ret(A a) { return List.of(a); }
      public List<A> on(Pick op, Direct.K<List<A>> k) throws Direct.Resumes {
        return switch (op) {
          case Pick.Among among -> {
            List<A> found = new ArrayList<>();
            for (Integer v : among.values) found.addAll(k.resume(among, v));
            if (among.values.isEmpty()) k.unwind();
            yield found;
          }
        };
      }
    }), p);
  }

  /** the first value the program comes to, the values tried in order: a path that comes to none goes on with the
   * next */
  public static <A, R> Comp<Optional<A>, R> first(Comp<A, ? super Eff<Pick, R>> p) {
    return handle(Pick.class, Direct.handler(new Direct.Handler0<Pick, A, Optional<A>>() {
      public Optional<A> ret(A a) { return Optional.of(a); }
      public Optional<A> on(Pick op, Direct.K<Optional<A>> k) throws Direct.Resumes {
        return switch (op) {
          case Pick.Among among -> {
            for (Integer v : among.values) {
              Optional<A> got = k.resume(among, v);
              if (got.isPresent()) yield got;
            }
            k.unwind();
            yield Optional.empty();
          }
        };
      }
    }), p);
  }

  /** a failure: the rest left, its finalizers run, the reason the result */
  public static <A, R> Comp<Throws.Result<String, A>, R> failing(Comp<A, ? super Eff<Fail, R>> p) {
    return handle(Fail.class, Direct.handler(new Direct.Handler0<Fail, A, Throws.Result<String, A>>() {
      public Throws.Result<String, A> ret(A a) { return new Throws.Ok<>(a); }
      public Throws.Result<String, A> on(Fail op, Direct.K<Throws.Result<String, A>> k) throws Direct.Resumes {
        return switch (op) {
          case Fail.Raise raise -> {
            k.unwind();
            yield new Throws.Err<>(raise.why);
          }
        };
      }
    }), p);
  }

  /** a log kept by a general handler: the lines told, after the rest — so each path of a choice outside it has its
   * own */
  public static <A, R> Comp<Writer.Logged<String, A>, R> logged(Comp<A, ? super Eff<Log, R>> p) {
    return handle(Log.class, Direct.handler(new Direct.Handler0<Log, A, Writer.Logged<String, A>>() {
      public Writer.Logged<String, A> ret(A a) { return new Writer.Logged<>(List.of(), a); }
      public Writer.Logged<String, A> on(Log op, Direct.K<Writer.Logged<String, A>> k) throws Direct.Resumes {
        return switch (op) {
          case Log.Tell tell -> {
            Writer.Logged<String, A> rest = k.resume(tell, null);
            List<String> lines = new ArrayList<>();
            lines.add(tell.line);
            lines.addAll(rest.log());
            yield new Writer.Logged<>(lines, rest.value());
          }
        };
      }
    }), p);
  }

  /** an interceptor of the counter that tells each operation to the log outside, and asks it again of the counter's
   * handler outside: its clause performs both effects of the row outside it, in direct style */
  public static <A, T> Comp<A, Eff<Counter, Eff<Log, T>>> told(Comp<A, ? super Eff<Counter, Eff<Log, T>>> p) {
    return intercept(Counter.class, Direct.row(Counter.class, Log.class).<Counter, A, A, T>handler(
        new Direct.Handler2<Counter, A, A, Counter, Log>() {
          public A ret(A a) { return a; }
          public A on(Counter op, Direct.K<A> k) throws Counter, Log, Direct.Resumes {
            Log.tell("saw " + op);
            return switch (op) {
              case Counter.Get get -> k.resume(get, Counter.get());
              case Counter.Put put -> {
                Counter.put(put.value);
                yield k.resume(put, null);
              }
            };
          }
        }), p);
  }

  /** an interceptor of the input that asks each operation again of the input's handler outside, from its clause */
  public static <A, T> Comp<A, Eff<Input, T>> relayed(Comp<A, ? super Eff<Input, T>> p) {
    return intercept(Input.class, Direct.row(Input.class).<Input, A, A, T>handler(
        new Direct.Handler1<Input, A, A, Input>() {
          public A ret(A a) { return a; }
          public A on(Input op, Direct.K<A> k) throws Input, Direct.Resumes {
            return switch (op) {
              case Input.Next next -> k.resume(next, Input.next());
            };
          }
        }), p);
  }

  /** the same, declared Once */
  public static <A, T> Comp<A, Eff<Input, T>> relayedOnce(Comp<A, ? super Eff<Input, T>> p) {
    final class Relay implements Direct.Handler1<Input, A, A, Input>, okay.rows.Once {
      public A ret(A a) { return a; }
      public A on(Input op, Direct.K<A> k) throws Input, Direct.Resumes {
        return switch (op) {
          case Input.Next next -> k.resume(next, Input.next());
        };
      }
    }
    return intercept(Input.class, Direct.row(Input.class).<Input, A, A, T>handler(new Relay()), p);
  }
}
