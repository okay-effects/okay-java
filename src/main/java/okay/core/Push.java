package okay.core;

/** {@code m} with the rest as a stack already: what applying a captured {@code k} makes — {@code Bind(m, k)},
 * where the loop takes {@code k} as its stack as it is, not as a function to push. Only {@link Frames} make it */
record Push<I, T, O, A, B>(Cont<T, O, A> m, Frames k) implements Cont<I, O, B> {}
