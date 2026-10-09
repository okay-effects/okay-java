package okay.std;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import okay.core.Backend;
import okay.core.Eff;
import okay.core.Eff.Top;
import okay.core.Prompt;
import org.junit.jupiter.api.Test;

/** the machine against the reference on programs made at random: handlers nested in any order, operations of the
 * capabilities in scope, resets and shifts to them — whatever the machine does fast, the reference does by the book, and they agree */
class AgreeTest {

  enum Kind { WRITER, STATE, CELL, CHOOSE, THROWS, READER, RESET }

  /** a program, as data: made first, then built — so both runs build the same, whatever runs twice */
  sealed interface Prog {
    record Pure(int n) implements Prog {}

    record Seq(Prog a, Prog b) implements Prog {}

    /** an operation of the capability {@code index} handlers out */
    record Op(int index, int n) implements Prog {}

    record Handle(Kind kind, int n, Prog body) implements Prog {}
  }

  static Prog make(Random r, List<Kind> scope, int budget) {
    // a leaf: an operation of a capability in scope, or a value; else a sequence or a handler more
    int pick = budget <= 1 ? r.nextInt(2) : 2 + r.nextInt(4);
    if (pick < 2 || pick == 2) {
      if (scope.isEmpty() || pick == 0) return new Prog.Pure(r.nextInt(10));
      int i = r.nextInt(scope.size());
      // a choice multiplies the paths: rarely
      if (scope.get(scope.size() - 1 - i) == Kind.CHOOSE && r.nextInt(3) > 0) return new Prog.Pure(r.nextInt(10));
      return new Prog.Op(i, r.nextInt(10));
    }
    if (pick == 3) return new Prog.Seq(make(r, scope, budget / 2), make(r, scope, budget / 2));
    Kind k = Kind.values()[r.nextInt(Kind.values().length)];
    List<Kind> inner = new ArrayList<>(scope);
    inner.add(k);
    return new Prog.Handle(k, r.nextInt(10), make(r, inner, budget - 1));
  }

  /** the program built over the capabilities in scope, innermost last, at the level of the innermost handler. The
   * capabilities are kept as they come, of any level outside; each is cast back to its effect at this level — a
   * level further in, where a capability of a level outside is good: unchecked, so the generator can pick them */
  @SuppressWarnings("unchecked")
  static <L> Eff<L, Integer> build(Prog p, List<Object> caps) {
    return switch (p) {
      case Prog.Pure(var n) -> Eff.pure(n);
      case Prog.Seq(var a, var b) -> AgreeTest.<L>build(a, caps).flatMap(x -> AgreeTest.<L>build(b, caps).map(y -> x * 3 + y));
      case Prog.Op(var i, var n) -> switch (caps.get(caps.size() - 1 - i)) {
        case Writer<?, ?> w -> ((Writer<L, Integer>) w).tell(n).map(_ -> n);
        case State<?, ?> s -> ((State<L, Integer>) s).modify(x -> x + n).then(((State<L, Integer>) s).get());
        case Choose<?> c -> ((Choose<L>) c).among(List.of(n, n + 1));
        case Throws<?, ?> t -> ((Throws<L, Integer>) t).raise(n);
        case Reader<?, ?> rd -> ((Reader<L, Integer>) rd).ask().map(x -> x + n);
        case Prompt<?, ?, ?> pr -> ((Prompt<Object, L, Integer>) pr).<Integer>shift(k -> k.apply(n).flatMap(x -> k.apply(x + 1)).map(x -> x * 5).at());
        default -> throw new IllegalStateException();
      };
      case Prog.Handle(var k, var n, var body) -> switch (k) {
        case WRITER -> Writer.writer(new Writer.Body<L, Integer, Integer>() {
          public <M extends L> Eff<M, Integer> in(Writer<M, Integer> w) {
            return build(body, with(caps, w));
          }
        }).map(l -> l.value() * 31 + l.log().stream().mapToInt(Integer::intValue).sum() * 7 + l.log().size());
        case STATE -> State.state(n, new State.Body<L, Integer, Integer>() {
          public <M extends L> Eff<M, Integer> in(State<M, Integer> s) {
            return build(body, with(caps, s));
          }
        }).map(f -> f.state() * 31 + f.value());
        case CELL -> State.cell(n, new State.Body<L, Integer, Integer>() {
          public <M extends L> Eff<M, Integer> in(State<M, Integer> s) {
            return build(body, with(caps, s));
          }
        }).map(f -> f.state() * 31 + f.value());
        case CHOOSE -> Choose.all(new Choose.Body<L, Integer>() {
          public <M extends L> Eff<M, Integer> in(Choose<M> c) {
            return build(body, with(caps, c));
          }
        }).map(l -> l.stream().reduce(17, (h, x) -> h * 31 + x));
        case THROWS -> Throws.catching(new Throws.Body<L, Integer, Integer>() {
          public <M extends L> Eff<M, Integer> in(Throws<M, Integer> t) {
            return build(body, with(caps, t));
          }
        }, e -> Eff.pure(-e));
        case READER -> Reader.reader(n, new Reader.Body<L, Integer, Integer>() {
          public <M extends L> Eff<M, Integer> in(Reader<M, Integer> rd) {
            return build(body, with(caps, rd));
          }
        });
        case RESET -> Eff.reset(new Eff.Body<L, Integer, Integer>() {
          public <M extends L> Eff<M, Integer> in(Prompt<L, M, Integer> pr) {
            return build(body, with(caps, pr));
          }
        }).map(x -> x + n);
      };
    };
  }

  private static List<Object> with(List<Object> caps, Object c) {
    List<Object> r = new ArrayList<>(caps);
    r.add(c);
    return r;
  }

  @Test
  void aThousandProgramsTheMachineAndTheReferenceAgree() {
    Random r = new Random(20261009);
    int handlers = 0;
    int crossing = 0;
    for (int i = 0; i < 1000; i++) {
      Prog p = make(r, List.of(), 32);
      handlers += count(p);
      crossing += crossing(p);
      Eff<Top, Integer> program = build(p, List.of());
      assertEquals(Backend.REF.run(program), Backend.MACHINE.run(program), p.toString());
    }
    // the programs are not trivial: handlers nested, operations crossing them
    assert handlers > 3000 && crossing > 500 : handlers + " handlers, " + crossing + " operations crossing one";
  }

  /** the operations of a handler further out than the nearest */
  private static int crossing(Prog p) {
    return switch (p) {
      case Prog.Pure _ -> 0;
      case Prog.Op(var i, var n) -> i > 0 ? 1 : 0;
      case Prog.Seq(var a, var b) -> crossing(a) + crossing(b);
      case Prog.Handle(var k, var n, var body) -> crossing(body);
    };
  }

  private static int count(Prog p) {
    return switch (p) {
      case Prog.Pure _, Prog.Op _ -> 0;
      case Prog.Seq(var a, var b) -> count(a) + count(b);
      case Prog.Handle(var k, var n, var body) -> 1 + count(body);
    };
  }
}
