package okay.std;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** a sequence joined in constant time, a handler's answer while it is built: a list once, at the end */
sealed interface Rope<A> {
  record Empty<A>() implements Rope<A> {}

  record Leaf<A>(A value) implements Rope<A> {}

  record Cat<A>(Rope<A> left, Rope<A> right) implements Rope<A> {}

  /** an element in front of the rest: a writer's tell, one object */
  record Cons<A>(A head, Rope<A> tail) implements Rope<A> {}

  Rope<?> EMPTY = new Empty<>();

  @SuppressWarnings("unchecked")
  static <A> Rope<A> empty() {
    return (Rope<A>) EMPTY;
  }

  static <A> Rope<A> cat(Rope<A> l, Rope<A> r) {
    return l instanceof Empty ? r : r instanceof Empty ? l : new Cat<>(l, r);
  }

  /** the elements in order, in constant stack */
  default List<A> toList() {
    List<A> out = new ArrayList<>();
    Deque<Rope<A>> todo = new ArrayDeque<>();
    todo.push(this);
    while (!todo.isEmpty()) {
      switch (todo.pop()) {
        case Empty<A> _ -> {}
        case Leaf<A>(var a) -> out.add(a);
        case Cons<A>(var h, var t) -> {
          out.add(h);
          todo.push(t);
        }
        case Cat<A>(var l, var r) -> {
          todo.push(r);
          todo.push(l);
        }
      }
    }
    return out;
  }
}
