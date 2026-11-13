/** THE EFFECT AND ITS IMPLEMENTATION in one module: the effect, its operations and the way to install its handler
 * exported; the bank — the real client — and the handler that calls it not. A module of programs reads this one and
 * performs the effect; it cannot name the bank: javac refuses it (JpmsTest) */
module payments {
  requires transitive okay.java;
  requires transitive okay.java.resumable;
  exports payments;
}
