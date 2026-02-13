package infrastructure.util;

/**
 * Shared numerical constants for floating-point stability throughout the mining pipeline.
 *
 * <h3>Design rationale</h3>
 * The PTK-HUIM algorithm accumulates probabilities over large transaction databases.
 * Naive product-of-probabilities would underflow to 0.0 for databases with thousands
 * of transactions; log-space arithmetic avoids this.  Comparisons use an epsilon
 * tolerance so that rounding errors at boundary values do not cause spurious pruning.
 *
 * <p>All constants are {@code public static final} — accessible via static import
 * in hot-path classes without boxing overhead.
 */
public final class NumericalConstants {

    /**
     * Epsilon tolerance for floating-point threshold comparisons.
     *
     * <p>Used as: {@code value < threshold - EPSILON} to guard against
     * boundary misclassification due to IEEE 754 rounding.
     * Value {@code 1e-10} is small relative to typical EU magnitudes (tens to thousands).
     */
    public static final double EPSILON = 1e-10;

    /**
     * Log-space floor to prevent denormalized-number underflow.
     *
     * <p>Any log-probability that would be more negative than {@code LOG_ZERO}
     * is clamped to {@code -700.0}, corresponding to roughly {@code e^{-700} ≈ 10^{-304}},
     * which is well above the {@code double} minimum normal {@code 2.2e-308}.
     */
    public static final double LOG_ZERO = -700.0;

    /**
     * Pre-computed {@code log(1 − ε)} to avoid repeated recomputation in the
     * EP accumulation inner loop.
     */
    public static final double LOG_ONE_MINUS_EPSILON = Math.log(1.0 - EPSILON);

    // FINE_GRAIN_THRESHOLD and PARALLEL_THRESHOLD removed - defined in MiningOrchestrator only

    private NumericalConstants() {
        // Prevent instantiation — static constants only
    }
}
