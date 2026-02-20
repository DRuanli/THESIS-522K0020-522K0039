package infrastructure.parallel;

import domain.collection.TopKCollectorInterface;

import static infrastructure.util.NumericalConstants.EPSILON;

/**
 * Provides access to the dynamic admission threshold for parallel mining.
 *
 * <h3>Thread-Safe Threshold Access</h3>
 * <p>In Phase 3, multiple ForkJoin threads mine different prefix subtrees concurrently.
 * All threads share the same {@link TopKCollectorInterface} and read the same
 * volatile {@code admissionThreshold}. The threshold increases monotonically as
 * better patterns are discovered.
 *
 * <h3>Correctness Guarantee</h3>
 * <p>Using the dynamic (rising) threshold for pruning is provably safe:
 * <ul>
 *   <li>If PTWU(P) &lt; threshold(t) at time t, then EU(P) ≤ PTWU(P) &lt; threshold(t)</li>
 *   <li>Since threshold only increases: threshold(t) ≤ threshold(final)</li>
 *   <li>Therefore: EU(P) &lt; threshold(final), so P cannot be in top-k</li>
 *   <li>Pruning P is correct - no false negatives possible</li>
 * </ul>
 *
 * <h3>Performance Benefits</h3>
 * <p>More aggressive pruning with rising threshold:
 * <ul>
 *   <li>Skips subtrees that cannot contribute to final top-k</li>
 *   <li>Reduces wasted computation on doomed branches</li>
 *   <li>Better cache locality (fewer memory accesses)</li>
 *   <li>Faster convergence to final result</li>
 * </ul>
 *
 * <p><b>Note:</b> This class provides convenient access to the collector's threshold.
 * It uses the interface {@link TopKCollectorInterface} to support multiple collector
 * implementations.
 */
public final class ThresholdCoordinator {

    /** Collector interface; provides volatile access to dynamic threshold. */
    private final TopKCollectorInterface collector;

    /**
     * Constructs a coordinator backed by the given pattern collector.
     *
     * @param collector the Top-K pattern collector whose threshold is accessed
     */
    public ThresholdCoordinator(TopKCollectorInterface collector) {
        this.collector = collector;
    }

    /**
     * Returns the current dynamic admission threshold.
     *
     * <p>This threshold increases monotonically as better patterns are collected.
     * Reading the volatile threshold is thread-safe and always returns the most
     * recent value (within Java Memory Model constraints).
     *
     * @return the current admission threshold; 0.0 if fewer than k patterns collected
     */
    public double getAdmissionThreshold() {
        return collector.getAdmissionThreshold();
    }

    /**
     * Returns {@code true} if a prefix's PTWU is below the current dynamic threshold,
     * meaning no pattern rooted at that prefix can be in the current top-k.
     *
     * <p>Uses the dynamic threshold for aggressive pruning. This is provably safe:
     * if PTWU(prefix) &lt; threshold, then all patterns in the prefix's subtree have
     * EU ≤ PTWU(prefix) &lt; threshold, so none can qualify for top-k.
     *
     * @param prefixPTWU PTWU of the prefix itemset
     * @return {@code true} if the prefix subtree should be pruned
     */
    public boolean shouldPrunePrefix(double prefixPTWU) {
        return prefixPTWU < collector.getAdmissionThreshold() - EPSILON;
    }
}
