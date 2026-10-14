package okay.core;

/** what runs a {@link Cont} program: its loop, or the reference, {@link ContRef} — the tests written once on each */
public enum ContBackend {
  LOOP {
    public <A> A value(Cont<A, A, A> c) {
      return Cont.value(c);
    }
  },
  REF {
    public <A> A value(Cont<A, A, A> c) {
      return Backend.onLargeStack(() -> ContRef.value(c));
    }
  };

  public abstract <A> A value(Cont<A, A, A> c);
}
