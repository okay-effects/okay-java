package okay.rows;

/** A PLACE OF THE ROW whose sensitive effect {@code E} is AUDITED: the row outside {@link Audit#audit}. Below
 * {@code Eff<E, R>}, as {@link Sensitive} is, so no {@code handle} takes it: {@link Audit#handle} does. No
 * operation is asked at it but by the audit's {@link Pass}. */
public interface Audited<E, R> extends Eff<E, R> {}
