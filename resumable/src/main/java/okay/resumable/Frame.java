package okay.resumable;

/** A saved frame of a transformed method, as data: the call it was suspended at and the values of its locals then —
 * and of the operand stack under that call. Never changed: a resumption copies the values back into a fresh Java
 * frame, so a continuation can be resumed many times. The objects the values refer to are not copied. For
 * transformed code only. */
public final class Frame {
  final Resumer resumer;
  final int site;
  final Object[] values;

  Frame(Resumer resumer, int site, Object[] values) {
    this.resumer = resumer;
    this.site = site;
    this.values = values;
  }

  /** the suspended call, an index into the method's calls that may suspend */
  public int site() { return site; }

  /** the saved values, in the order the transformation wrote them */
  public Object[] values() { return values; }

  boolean finalizes() { return resumer.sites[site].covered(); }

  @Override
  public String toString() { return resumer.where(site); }
}
