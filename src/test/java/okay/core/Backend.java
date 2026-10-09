package okay.core;

import java.util.concurrent.atomic.AtomicReference;

/** what runs a program: the machine, {@link Eff#run}, or the reference, {@link Ref#run} — on a thread of a large
 * stack, recursive as it is. The tests written once run on each, and must agree */
public enum Backend {
  MACHINE {
    public <A> A run(Eff<A> p) {
      return Eff.run(p);
    }
  },
  REF {
    public <A> A run(Eff<A> p) {
      return onLargeStack(() -> Ref.run(p));
    }
  };

  public abstract <A> A run(Eff<A> p);

  interface Body<A> {
    A get();
  }

  static <A> A onLargeStack(Body<A> body) {
    AtomicReference<A> value = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread t = new Thread(null, () -> {
      try {
        value.set(body.get());
      } catch (Throwable e) {
        failure.set(e);
      }
    }, "ref", 2L << 30);
    t.start();
    try {
      t.join();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
    if (failure.get() instanceof RuntimeException e) throw e;
    if (failure.get() instanceof Error e) throw e;
    return value.get();
  }
}
