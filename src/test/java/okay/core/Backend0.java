package okay.core;

/** what runs a {@link Cont0} program: its loop, or the reference, {@link Ref0} — the tests written once on each */
public enum Backend0 {
  LOOP {
    public <A> A value(Cont0<A, A, A> c) {
      return Cont0.value(c);
    }
  },
  REF {
    public <A> A value(Cont0<A, A, A> c) {
      return Backend.onLargeStack(() -> Ref0.value(c));
    }
  };

  public abstract <A> A value(Cont0<A, A, A> c);
}
