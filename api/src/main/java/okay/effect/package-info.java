/** THE VOCABULARY OF EFFECTS IN JAVA, of no implementation and no dependency: an operation ({@link okay.effect.Op}),
 * the row ({@link okay.effect.Eff}, {@link okay.effect.Pure}), the marks of sensitive effects
 * ({@link okay.effect.Sensitive}, {@link okay.effect.Audited}). Written once, shared by every Java library of the
 * family: okay-java's programs as data ({@code okay.rows.Comp<A, R>}) and okay-direct's programs in direct style
 * ({@code okay.direct.Program<A, R>}) ask the same operations in the same rows. The concept and the family's names:
 * https://github.com/okay-effects/okay-effects/blob/master/CONCEPT.md */
package okay.effect;
