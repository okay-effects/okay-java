package okay.rows;

import static okay.rows.Effects.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import okay.rows.std.Choose;

/** THE FAMILY'S TCK (okay-kernel, tck/Tck.scala): a thousand random programs — handlers of logs, states, failures and
 * choices nested in any order, each effect under two identities — and the digest of their outcomes. The digest is the
 * reference machine's; the Scala and the Rust kernels of this repository come to the same number. */
final class Tck {
  sealed interface T {}
  record Pure(int n) implements T {}
  record Tell(int e, int n) implements T {}
  record Get(int e) implements T {}
  record Put(int e, int n) implements T {}
  record Among(List<Integer> ns) implements T {}
  record Raise(int e, int n) implements T {}
  record Seq(T a, T b) implements T {}
  record Add(T a, T b) implements T {}
  record WriterH(int e, T body) implements T {}
  record StateH(int e, int s0, T body) implements T {}
  record ThrowsH(int e, T body) implements T {}
  record ChooseH(T body) implements T {}

  static T gen(Random r, int depth) {
    if (depth <= 0 || r.nextInt(4) == 0) {
      return switch (r.nextInt(6)) {
        case 0 -> new Pure(r.nextInt(10));
        case 1 -> new Tell(r.nextInt(2), r.nextInt(10));
        case 2 -> new Get(r.nextInt(2));
        case 3 -> new Put(r.nextInt(2), r.nextInt(10));
        case 4 -> {
          List<Integer> ns = new ArrayList<>();
          for (int i = 1 + r.nextInt(2); i > 0; i--) ns.add(r.nextInt(10));
          yield new Among(ns);
        }
        default -> new Raise(r.nextInt(2), r.nextInt(10));
      };
    }
    return switch (r.nextInt(6)) {
      case 0 -> new Seq(gen(r, depth - 1), gen(r, depth - 1));
      case 1 -> new Add(gen(r, depth - 1), gen(r, depth - 1));
      case 2 -> new WriterH(r.nextInt(2), gen(r, depth - 1));
      case 3 -> new StateH(r.nextInt(2), r.nextInt(10), gen(r, depth - 1));
      case 4 -> new ThrowsH(r.nextInt(2), gen(r, depth - 1));
      default -> new ChooseH(gen(r, depth - 1));
    };
  }

  // the effects: an identity `e` in the operation — a handler answers its own (`mine`), any other passes outside
  sealed interface Log {}
  record TellOp(int e, int n) implements Log, Op<Log, Void, TellOp> {}
  sealed interface Store {}
  record GetOp(int e) implements Store, Op<Store, Integer, GetOp> {}
  record PutOp(int e, int n) implements Store, Op<Store, Void, PutOp> {}
  sealed interface Fail {}
  record RaiseOp(int e, int n) implements Fail, Op<Fail, Integer, RaiseOp> {}

  /** THE ROW of every program: the four effects stay in it whatever handlers the program is under — a handler
   * here is an interceptor: it answers its own operations, found by their class, and lets the others pass. Named,
   * so that it is written out once; the empty row by its full name, for `Pure` is here a term of the TCK. */
  interface Row extends Eff<Log, Eff<Store, Eff<Fail, Eff<Choose, okay.rows.Pure>>>> {}

  record Summed(int sum, int value) {}

  static Comp<Integer, Row> writer(int e, Comp<Integer, Row> p) {
    return intercept(Log.class, new Handler<Log, Integer, Summed, Row>() {
      public boolean mine(Log op) {
        return switch (op) {
          case TellOp tell -> tell.e() == e;
        };
      }
      public Comp<Summed, Row> ret(Integer a) { return pure(new Summed(0, a)); }
      public Comp<Summed, Row> on(Log op, K<Summed, Row> k) {
        return switch (op) {
          case TellOp tell -> k.resume(tell, null).map(l -> new Summed(l.sum() + tell.n(), l.value()));
        };
      }
    }, p).map(l -> l.value() * 100 + l.sum());
  }

  /** the TCK's state is a cell made where the handler is installed: one for every resumption taken past it —
   * not the state of {@code std.State}, which each path has its own */
  static Comp<Integer, Row> state(int e, int s0, Comp<Integer, Row> p) {
    int[] s = {s0};
    return intercept(Store.class, new InPlace<Store, Void, Integer, Integer, Row>() {
      public boolean mine(Store op) {
        return switch (op) {
          case GetOp get -> get.e() == e;
          case PutOp put -> put.e() == e;
        };
      }
      public Comp<Integer, Row> ret(Void state, Integer a) { return pure(a * 10 + s[0]); }
      public Cell.Answered answer(Cell<Void> state, Store op) {
        return switch (op) {
          case GetOp get -> state.answer(get, s[0]);
          case PutOp put -> { s[0] = put.n(); yield state.answer(put, null); }
        };
      }
    }, null, p);
  }

  static Comp<Integer, Row> fails(int e, Comp<Integer, Row> p) {
    return intercept(Fail.class, new Handler<Fail, Integer, Integer, Row>() {
      public boolean mine(Fail op) {
        return switch (op) {
          case RaiseOp raise -> raise.e() == e;
        };
      }
      public Comp<Integer, Row> ret(Integer a) { return pure(a); }
      public Comp<Integer, Row> on(Fail op, K<Integer, Row> k) {
        return switch (op) {
          case RaiseOp raise -> pure(-1 - raise.n());
        };
      }
    }, p);
  }

  /** every path of a choice, the values summed. An interceptor like the others: the row's choice is not its first
   * effect, and whatever asks a choice inside is answered here, by the class. */
  static Comp<Integer, Row> chooses(Comp<Integer, Row> p) {
    return intercept(Choose.class, new Handler<Choose, Integer, Integer, Row>() {
      public Comp<Integer, Row> ret(Integer a) { return pure(a); }
      public Comp<Integer, Row> on(Choose op, K<Integer, Row> k) {
        return switch (op) {
          case Choose.Among<?> among -> every(among, 0, 0, k);
        };
      }
    }, p);
  }

  private static <X> Comp<Integer, Row> every(Choose.Among<X> among, int i, int sum, K<Integer, Row> k) {
    if (i == among.values().size()) return pure(sum);
    return k.resume(among, among.values().get(i)).flatMap(more -> every(among, i + 1, sum + more, k));
  }

  static Comp<Integer, Row> build(T t) {
    return switch (t) {
      case Pure pure -> pure(pure.n());
      case Tell tell -> perform(new TellOp(tell.e(), tell.n())).map(done -> tell.n());
      case Get get -> fit(up(perform(new GetOp(get.e()))));
      case Put put -> up(perform(new PutOp(put.e(), put.n()))).map(done -> put.n());
      case Among among -> fit(up(up(up(perform(new Choose.Among<>(among.ns()))))));
      case Raise raise -> fit(up(up(perform(new RaiseOp(raise.e(), raise.n())))));
      case Seq seq -> build(seq.a()).then(() -> build(seq.b()));
      case Add add -> build(add.a()).flatMap(i -> build(add.b()).map(j -> i + j));
      case WriterH w -> writer(w.e(), build(w.body()));
      case StateH s -> state(s.e(), s.s0(), build(s.body()));
      case ThrowsH f -> fails(f.e(), build(f.body()));
      case ChooseH c -> chooses(build(c.body()));
    };
  }

  static int outcome(T t) {
    return switch (step(build(t))) {
      case Step.Done<Integer, Row> done -> done.value();
      case Step.Asked<?, Integer, Row> asked -> -1000000; // no handler
    };
  }

  static long digest() {
    Random r = new Random(20261010L);
    long d = 0;
    for (int i = 0; i < 1000; i++) {
      T t = gen(r, 6);
      t = new StateH(0, 0, new StateH(1, 0, new WriterH(0, new WriterH(1, t))));
      t = new ChooseH(new ThrowsH(0, new ThrowsH(1, t)));
      d = d * 31 + outcome(t);
    }
    return d;
  }

  static final long DIGEST = -8623788747993822161L;
}
