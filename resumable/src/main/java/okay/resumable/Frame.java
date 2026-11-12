package okay.resumable;

/** A saved frame of a transformed method, as data: the call it was suspended at and the values of its locals then —
 * and of the operand stack under that call: references in one array, primitives as bits in another, so a primitive
 * is not boxed. Never changed once the boundary has linked it: a resumption copies the values back into a fresh Java
 * frame, so a continuation can be resumed many times. The objects the values refer to are not copied. For transformed
 * code only. */
public final class Frame {
  final Resumer resumer;
  final int site;
  final Object[] refs;
  final long[] prims;
  /** the frame of the caller, the next one out: set while the frames are saved, the outermost's by the boundary */
  Frame next;
  /** whether this frame or one further out has a handler at its call: an unwinding re-enters it */
  boolean finalizes;
  /** whether this frame and every one further out are suspended at a call in tail position: the rest of them only
   * returns what the call gives */
  boolean tails;

  Frame(Resumer resumer, int site, Object[] refs, long[] prims) {
    this.resumer = resumer;
    this.site = site;
    this.refs = refs;
    this.prims = prims;
  }

  /** the suspended call, an index into the method's calls that may suspend */
  public int site() { return site; }

  /** the saved references, in the order the transformation wrote them; null if none */
  public Object[] refs() { return refs; }

  /** the saved primitives as bits ({@code int}, {@code float} by their int bits), in the order the transformation
   * wrote them; null if none */
  public long[] prims() { return prims; }

  boolean covered() { return resumer.sites[site].covered(); }

  @Override
  public String toString() { return resumer.where(site); }
}
