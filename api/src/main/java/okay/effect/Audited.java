package okay.effect;

/** A PLACE OF THE ROW whose sensitive effect {@code E} is AUDITED: the row outside {@code Audit#audit}. Below
 * {@code Eff<E, R>}, as {@link Sensitive} is, so no {@code handle} takes it: {@code Audit#handle} does. No
 * operation is asked at it but by the audit's {@code Pass}. */
public interface Audited<E, R> extends Eff<E, R> {}
