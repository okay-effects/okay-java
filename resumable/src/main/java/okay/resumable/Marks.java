package okay.resumable;

/** THE NAME OF AN EFFECT IN A {@code throws}, for an effect written as a GADT: a final subclass of {@link Suspend}
 * that says which effect it stands for —
 * {@code final class Fx extends Suspend implements Marks<Prices<?>> {}}, inside the effect's interface. Nothing is
 * made of it or thrown: it is there for javac, which checks that a method lists the effects it uses, and for
 * {@code Direct.effects}, which makes the row of them. */
public interface Marks<F> {}
