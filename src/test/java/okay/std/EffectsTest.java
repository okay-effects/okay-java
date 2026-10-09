package okay.std;

import static okay.core.Eff.pure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import okay.core.Backend;
import okay.core.Eff;
import okay.core.Eff.Top;
import okay.core.Prompt;
import okay.std.State.Final;
import okay.std.Writer.Log;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.EnumSource;

/** okay-core's TestEff on {@link Eff}: an effect an interface at a level, a handler a prompt and an implementation of
 * it, its body at a fresh level — and what a row cannot say: two handlers of one effect at once. Run on the machine
 * and on the reference, {@link Backend} */
@ParameterizedClass
@EnumSource(Backend.class)
class EffectsTest {

  /** the machine or the reference: each test on both */
  @Parameter
  Backend backend;

  <A> A run(Eff<Top, A> p) {
    return backend.run(p);
  }

  /** effects of the test's own: an interface each, at a level */
  interface Ask<L> {
    Eff<L, Integer> number();

    @SuppressWarnings("unchecked")
    default <N extends L> Ask<N> at() {
      return (Ask<N>) (Ask<?>) this;
    }
  }

  interface Say<L> {
    Eff<L, Void> line(String s);

    @SuppressWarnings("unchecked")
    default <N extends L> Say<N> at() {
      return (Say<N>) (Say<?>) this;
    }
  }

  /** a body given an {@code Ask}, at a fresh level */
  interface Asked<L, A> {
    <M extends L> Eff<M, A> in(Ask<M> a);
  }

  interface Said<L, A> {
    <M extends L> Eff<M, A> in(Say<M> s);
  }

  /** every {@code number} answered {@code n}: a general handler, a capture each */
  static <L, A> Eff<L, A> asking(int n, Asked<L, A> body) {
    return Eff.handle(new Eff.Body<L, A, A>() {
      public <M extends L> Eff<M, A> in(Prompt<L, M, A> p) {
        return body.in(() -> p.shift0(k -> k.apply(n)));
      }
    }, Eff::pure);
  }

  /** the same answered in place: the capability its own handler, no prompt */
  static <L, A> Eff<L, A> answering(int n, Asked<L, A> body) {
    Eff<L, Integer> v = pure(n);
    return body.<L>in(() -> v);
  }

  /** the lines said, in order */
  static <L, A> Eff<L, Log<String, A>> saying(Said<L, A> body) {
    return Writer.writer(new Writer.Body<L, String, A>() {
      public <M extends L> Eff<M, A> in(Writer<M, String> w) {
        return body.in(w::tell);
      }
    });
  }

  @Test
  void anOperationAnsweredByItsHandler() {
    assertEquals((Integer) 42, run(asking(41, new Asked<Top, Integer>() {
      public <M extends Top> Eff<M, Integer> in(Ask<M> a) {
        return a.number().map(x -> x + 1);
      }
    })));
    assertEquals((Integer) 42, run(answering(41, new Asked<Top, Integer>() {
      public <M extends Top> Eff<M, Integer> in(Ask<M> a) {
        return a.number().map(x -> x + 1);
      }
    })));
  }

  /** a program needing both effects: at any level the two capabilities are at */
  static <L> Eff<L, Integer> twoEffects(Ask<L> a, Say<L> s) {
    return a.number().flatMap(n -> s.line(n.toString()).map(_ -> n + 1));
  }

  @Test
  void twoEffectsTheHandlersInEitherOrder() {
    assertEquals(new Log<>(List.of("41"), 42), run(saying(new Said<Top, Integer>() {
      public <M extends Top> Eff<M, Integer> in(Say<M> s) {
        return asking(41, new Asked<M, Integer>() {
          public <N extends M> Eff<N, Integer> in(Ask<N> a) {
            return twoEffects(a, s.at());
          }
        });
      }
    })));
    assertEquals(new Log<>(List.of("41"), 42), run(asking(41, new Asked<Top, Log<String, Integer>>() {
      public <M extends Top> Eff<M, Log<String, Integer>> in(Ask<M> a) {
        return saying(new Said<M, Integer>() {
          public <N extends M> Eff<N, Integer> in(Say<N> s) {
            return twoEffects(a.at(), s);
          }
        });
      }
    })));
  }

  static <L> Eff<L, Integer> chosen(Choose<L> ch, Say<L> s) {
    return ch.among(List.of(1, 2)).flatMap(x -> s.line("x=" + x).map(_ -> x * 10));
  }

  @Test
  void aSayCrossesChooseChooseResumesSaysHandlerInEachPath() {
    assertEquals(new Log<>(List.of("x=1", "x=2"), List.of(10, 20)), run(saying(new Said<Top, List<Integer>>() {
      public <M extends Top> Eff<M, List<Integer>> in(Say<M> s) {
        return Choose.all(new Choose.Body<M, Integer>() {
          public <N extends M> Eff<N, Integer> in(Choose<N> ch) {
            return chosen(ch, s.at());
          }
        });
      }
    })));
    assertEquals(List.of(new Log<>(List.of("x=1"), 10), new Log<>(List.of("x=2"), 20)),
        run(Choose.all(new Choose.Body<Top, Log<String, Integer>>() {
          public <M extends Top> Eff<M, Log<String, Integer>> in(Choose<M> ch) {
            return saying(new Said<M, Integer>() {
              public <N extends M> Eff<N, Integer> in(Say<N> s) {
                return chosen(ch.at(), s);
              }
            });
          }
        })));
  }

  static <L> Eff<L, Integer> loop(Ask<L> a, int n, int acc) {
    return n == 0 ? pure(acc) : a.number().flatMap(x -> loop(a, n - 1, acc + x));
  }

  /** the body under two writers, the ask's handler outside both */
  static <L> Eff<L, Log<String, Log<String, Integer>>> twoWriters(Ask<L> a, int n) {
    return saying(new Said<L, Log<String, Integer>>() {
      public <M extends L> Eff<M, Log<String, Integer>> in(Say<M> s1) {
        return saying(new Said<M, Integer>() {
          public <N extends M> Eff<N, Integer> in(Say<N> s2) {
            return loop(a.at(), n, 0);
          }
        });
      }
    });
  }

  @Test
  void operationsUnderAHandlerAndCrossingTwoInConstantStack() {
    assertEquals((Integer) 100_000, run(asking(1, new Asked<Top, Integer>() {
      public <M extends Top> Eff<M, Integer> in(Ask<M> a) {
        return loop(a, 100_000, 0);
      }
    })));
    Log<String, Log<String, Integer>> nothingSaid = new Log<>(List.of(), new Log<>(List.of(), 100_000));
    assertEquals(nothingSaid, run(asking(1, new Asked<Top, Log<String, Log<String, Integer>>>() {
      public <M extends Top> Eff<M, Log<String, Log<String, Integer>>> in(Ask<M> a) {
        return twoWriters(a, 100_000);
      }
    })));
    assertEquals(nothingSaid, run(answering(1, new Asked<Top, Log<String, Log<String, Integer>>>() {
      public <M extends Top> Eff<M, Log<String, Log<String, Integer>>> in(Ask<M> a) {
        return twoWriters(a, 100_000);
      }
    })));
  }

  static <L> Eff<L, Integer> deep(Ask<L> a, int d) {
    return d == 0 ? loop(a, 1000, 0) : saying(new Said<L, Integer>() {
      public <M extends L> Eff<M, Integer> in(Say<M> s) {
        return deep(a.at(), d - 1);
      }
    }).map(Log::value);
  }

  @Test
  void operationsEachCrossingFiveHundredHandlersOutAndBack() {
    assertEquals((Integer) 1000, run(asking(1, new Asked<Top, Integer>() {
      public <M extends Top> Eff<M, Integer> in(Ask<M> a) {
        return deep(a, 500);
      }
    })));
  }

  @Test
  void answeredInPlaceUnderMultiShotChooseAndSay() {
    assertEquals(List.of(new Log<>(List.of("15"), 5), new Log<>(List.of("25"), 10)),
        run(answering(5, new Asked<Top, List<Log<String, Integer>>>() {
          public <M extends Top> Eff<M, List<Log<String, Integer>>> in(Ask<M> a) {
            return Choose.all(new Choose.Body<M, Log<String, Integer>>() {
              public <N extends M> Eff<N, Log<String, Integer>> in(Choose<N> ch) {
                return saying(new Said<N, Integer>() {
                  public <O extends N> Eff<O, Integer> in(Say<O> s) {
                    // an outer capability's operation in a bind's function as it is: a level out runs in; at the
                    // head of a chain, moved in by at()
                    return ch.among(List.of(1, 2)).<O>at().flatMap(x -> a.number().<O>at().flatMap(n -> s.line("" + x + n).map(_ -> x * n)));
                  }
                });
              }
            });
          }
        })));
  }

  /** a choice, the state moved by it, the state read */
  static <L> Eff<L, Integer> step(State<L, Integer> st, Choose<L> ch) {
    return ch.among(List.of(1, 2)).flatMap(x -> st.modify(s -> s + x)).then(st.get());
  }

  @Test
  void stateAndChooseTheOrderIsTheMeaning() {
    // state outside: one state threaded through the paths, in turn
    assertEquals(new Final<>(3, List.of(1, 3)), run(State.state(0, new State.Body<Top, Integer, List<Integer>>() {
      public <M extends Top> Eff<M, List<Integer>> in(State<M, Integer> st) {
        return Choose.all(new Choose.Body<M, Integer>() {
          public <N extends M> Eff<N, Integer> in(Choose<N> ch) {
            return step(st.at(), ch);
          }
        });
      }
    })));
    // choose outside: a state per path, k resuming the state of its time
    assertEquals(List.of(new Final<>(1, 1), new Final<>(2, 2)), run(Choose.all(new Choose.Body<Top, Final<Integer, Integer>>() {
      public <M extends Top> Eff<M, Final<Integer, Integer>> in(Choose<M> ch) {
        return State.state(0, new State.Body<M, Integer, Integer>() {
          public <N extends M> Eff<N, Integer> in(State<N, Integer> st) {
            return step(st, ch.at());
          }
        });
      }
    })));
  }

  @Test
  void twoHandlersOfOneEffectAtOnceEachOperationToItsOwn() {
    // a row picks the nearest State % Int; a capability names its handler
    Final<Integer, Final<Integer, Integer>> r = run(State.state(0, new State.Body<Top, Integer, Final<Integer, Integer>>() {
      public <M extends Top> Eff<M, Final<Integer, Integer>> in(State<M, Integer> outer) {
        return State.state(100, new State.Body<M, Integer, Integer>() {
          public <N extends M> Eff<N, Integer> in(State<N, Integer> inner) {
            return outer.put(1).<N>at().then(inner.modify(x -> x + 1)).then(outer.get()).flatMap(a -> inner.get().map(b -> a + b));
          }
        });
      }
    }));
    assertEquals(new Final<>(1, new Final<>(101, 102)), r);
  }

  @Test
  void aClauseRunsOutsideItsHandlerARaiseInARecoveryGoesOut() {
    assertEquals("a!", run(Throws.catching(new Throws.Body<Top, String, String>() {
      public <M extends Top> Eff<M, String> in(Throws<M, String> outer) {
        return Throws.catching(new Throws.Body<M, String, String>() {
          public <N extends M> Eff<N, String> in(Throws<N, String> inner) {
            return inner.raise("a");
          }
        }, e -> outer.raise(e + "!"));
      }
    }, e -> pure(e))));
  }

  /** the effects of {@code f} pass through it, whatever they are, at whatever level */
  static <L, X, Y> Eff<L, List<Y>> traverse(List<X> xs, Function<X, Eff<L, Y>> f) {
    Eff<L, List<Y>> acc = pure(new ArrayList<>());
    for (X x : xs) acc = acc.flatMap(l -> f.apply(x).map(y -> { List<Y> r = new ArrayList<>(l); r.add(y); return r; }));
    return acc;
  }

  @Test
  void anEffectPolymorphicFunctionKnowsNothingOfTheEffects() {
    assertEquals(List.of(-1), run(Throws.catching(new Throws.Body<Top, String, List<Integer>>() {
      public <M extends Top> Eff<M, List<Integer>> in(Throws<M, String> t) {
        return traverse(List.of(1, 2, 3, 4), x -> x == 3 ? t.raise("three") : pure(x * 10));
      }
    }, _ -> pure(List.of(-1)))));
    assertEquals(new Log<>(List.of("1", "2", "3"), List.of(1, 2, 3)), run(Writer.writer(new Writer.Body<Top, String, List<Integer>>() {
      public <M extends Top> Eff<M, List<Integer>> in(Writer<M, String> w) {
        return traverse(List.of(1, 2, 3), x -> w.tell(x.toString()).map(_ -> x));
      }
    })));
  }

  @Test
  void aCapabilityForcedOutByAnUncheckedCastHasNoneToReach() {
    // the types do not let it out; a raw cast does, and then the prompt catches its use at run time still
    State<?, Integer> leaked = run(State.state(0, new State.Body<Top, Integer, State<?, Integer>>() {
      public <M extends Top> Eff<M, State<?, Integer>> in(State<M, Integer> st) {
        return pure(st);
      }
    })).value();
    @SuppressWarnings("unchecked")
    State<Top, Integer> forced = (State<Top, Integer>) (State<?, ?>) leaked;
    IllegalStateException e = assertThrows(IllegalStateException.class, () -> run(forced.get()));
    assertTrue(e.getMessage().contains("outside the handler"));
  }

  @Test
  void aStateWhoseTypeChangesTheCapabilityItsType() {
    // an Integer, then a String: the capability put by is stale, the one given in force
    Final<String, Integer> r = run(PState.pstate(1, new PState.Body<Top, Integer, String, Integer>() {
      public <M extends Top> Eff<M, Final<String, Integer>> in(PState<M, Integer> st) {
        return st.get().flatMap(n -> st.put("s" + n)).flatMap(st2 -> st2.get().flatMap(s -> st2.done(s.length())));
      }
    }));
    assertEquals(new Final<>("s1", 2), r);
  }

  @Test
  void aStaleStateCapabilityFailsAtItsUse() {
    IllegalStateException e = assertThrows(IllegalStateException.class, () -> run(PState.pstate(1,
        new PState.Body<Top, Integer, String, Integer>() {
          public <M extends Top> Eff<M, Final<String, Integer>> in(PState<M, Integer> st) {
            return st.put("s").flatMap(st2 -> st.get().flatMap(n -> st2.done(n)));
          }
        })));
    assertTrue(e.getMessage().contains("another capability"));
  }

  @Test
  void aStateWhoseTypeChangesUnderChoiceAPathEach() {
    // choose outside: each path its own state, its own generations
    assertEquals(List.of(new Final<>("1!", 1), new Final<>("2!", 2)), run(Choose.all(new Choose.Body<Top, Final<String, Integer>>() {
      public <M extends Top> Eff<M, Final<String, Integer>> in(Choose<M> ch) {
        return PState.pstate(0, new PState.Body<M, Integer, String, Integer>() {
          public <N extends M> Eff<N, Final<String, Integer>> in(PState<N, Integer> st) {
            return ch.among(List.of(1, 2)).<N>at().flatMap(x -> st.put(x + "!").flatMap(st2 -> st2.done(x)));
          }
        });
      }
    })));
  }

  @Test
  void aGeneratorAsALazyIterator() {
    int[] emitted = {0};
    Iterator<Integer> it = Emit.iterator(new Emit.Body<Top, Integer, Void>() {
      public <M extends Top> Eff<M, Void> in(Emit<M, Integer> em) {
        return naturals(em, 0, emitted);
      }
    });
    List<Integer> first = new ArrayList<>();
    for (int i = 0; i < 5; i++) first.add(it.next());
    assertEquals(List.of(0, 1, 2, 3, 4), first);
    // an infinite body, run as far as asked
    assertEquals(5, emitted[0]);
  }

  static <L> Eff<L, Void> naturals(Emit<L, Integer> em, int i, int[] emitted) {
    return Eff.<L, Void>delay(() -> { emitted[0]++; return em.emit(i); }).then(Eff.delay(() -> naturals(em, i + 1, emitted)));
  }

  /** 1, 2, 3 emitted, the value its own */
  static <L, A> Eff<L, A> oneTwoThree(Emit<L, Integer> em, A a) {
    return em.emit(1).then(em.emit(2)).then(em.emit(3)).map(_ -> a);
  }

  @Test
  void eachEmittedValueToTheContextOutsideAnsweredInPlace() {
    assertEquals(new Log<>(List.of(1, 2, 3), "done"), run(Writer.writer(new Writer.Body<Top, Integer, String>() {
      public <M extends Top> Eff<M, String> in(Writer<M, Integer> w) {
        return Emit.forEach(new Emit.Body<M, Integer, String>() {
          public <N extends M> Eff<N, String> in(Emit<N, Integer> em) {
            return oneTwoThree(em, "done");
          }
        }, w::tell);
      }
    })));
    assertEquals(List.of(1, 2, 3), run(Emit.collect(new Emit.Body<Top, Integer, Void>() {
      public <M extends Top> Eff<M, Void> in(Emit<M, Integer> em) {
        return oneTwoThree(em, null);
      }
    })));
  }

  /** every placing of the queens, by a choice per row */
  static Choose.Body<Top, List<Integer>> queens() {
    return new Choose.Body<>() {
      public <M extends Top> Eff<M, List<Integer>> in(Choose<M> ch) {
        return place(ch, 8, 0, List.of());
      }
    };
  }

  @Test
  void queensOneProgramAllOrTheFirst() {
    assertEquals(92, run(Choose.all(queens())).size());
    Optional<List<Integer>> first = run(Choose.first(queens()));
    assertEquals(Optional.of(List.of(3, 1, 6, 2, 5, 7, 4, 0)), first);
  }

  static <L> Eff<L, List<Integer>> place(Choose<L> ch, int size, int row, List<Integer> placed) {
    if (row == size) return pure(placed);
    List<Integer> safe = new ArrayList<>();
    for (int col = 0; col < size; col++) if (safe(col, placed)) safe.add(col);
    return ch.among(safe).flatMap(col -> {
      List<Integer> next = new ArrayList<>();
      next.add(col);
      next.addAll(placed);
      return place(ch, size, row + 1, next);
    });
  }

  static boolean safe(int col, List<Integer> placed) {
    for (int d = 0; d < placed.size(); d++) {
      int c = placed.get(d);
      if (c == col || Math.abs(c - col) == d + 1) return false;
    }
    return true;
  }

  @Test
  void readerAsksInPlace() {
    assertEquals(6, (int) run(Reader.reader(5, new Reader.Body<Top, Integer, Integer>() {
      public <M extends Top> Eff<M, Integer> in(Reader<M, Integer> r) {
        return r.ask().map(x -> x + 1);
      }
    })));
  }

  @Test
  void tailCallsThroughDelayInConstantStack() {
    assertEquals(false, run(even(100_001)));
  }

  static <L> Eff<L, Boolean> even(int i) {
    return i == 0 ? pure(true) : Eff.delay(() -> odd(i - 1));
  }

  static <L> Eff<L, Boolean> odd(int i) {
    return i == 0 ? pure(false) : Eff.delay(() -> even(i - 1));
  }
}
