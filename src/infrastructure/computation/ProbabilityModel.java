package infrastructure.computation;

import static infrastructure.util.NumericalConstants.*;

/**
 * Log-space probability arithmetic for numerical stability in the PTK-HUIM pipeline.
 *
 * <h3>Motivation</h3>
 * Per-transaction probabilities {@code P(X, T) = ∏_{i ∈ X} P(i, T)} become extremely
 * small for large itemsets ({@code P(X, T) → 0}).  Working in log-space
 * ({@code log P(X, T) = Σ log P(i, T)}) keeps values in a numerically safe range
 * and converts multiplications to additions, which are both faster and more accurate.
 *
 * <h3>EP computation</h3>
 * Existential probability requires the complement product:
 * <pre>
 *   EP(X) = 1 − ∏_T (1 − P(X, T))
 *         = 1 − exp(Σ_T log(1 − P(X, T)))
 * </pre>
 * The inner {@code log(1 − P)} term is computed via
 * {@link #logComplement(double)} which switches between {@link Math#log1p(double)}
 * and {@link Math#log(double)} depending on the argument magnitude to avoid
 * catastrophic cancellation near zero.
 *
 * <p>All methods are pure (no side effects) and stateless; the class is a static-only
 * utility.
 */
public final class ProbabilityModel {
    /**
     * Private constructor to prevent instantiation.
     *
     * <p>This is a static-only utility class - all methods are {@code static}
     * and the class holds no instance state. The private constructor ensures
     * that no instances can be created, enforcing the utility class pattern.
     *
     * <p><b>Design rationale:</b> Utility classes should never be instantiated.
     * Making the constructor private prevents both direct instantiation
     * ({@code new ProbabilityModel()}) and subclassing (subclasses cannot
     * call {@code super()}).
     */
    private ProbabilityModel(){
        // Prevent instantiation - static utility class only
    }

    /**
     * Computes {@code log(1 − P)} with catastrophic-cancellation mitigation.
     *
     * <ul>
     *   <li>When {@code P < 0.5}: uses {@link Math#log1p(double) Math.log1p(-P)}
     *       which is accurate for arguments near zero ({@code -P ≈ 0}).</li>
     *   <li>When {@code P ≥ 0.5}: uses {@link Math#log(double) Math.log(1 − P)}
     *       directly, since {@code 1 − P} is well above the cancellation danger zone.</li>
     * </ul>
     *
     * @param probability {@code P ∈ [0, 1]}
     * @return {@code log(1 − P)}, clamped to for {@code P = 1}
     */
    public static double logComplement(double probability) {
        if (probability <= 0) return 0.0;
        if (probability >= 1.0) return LOG_ZERO;

        if (probability < 0.5) {
            return Math.log1p(-probability);
        } else {
            return Math.log(1.0 - probability);
        }
    }
}
