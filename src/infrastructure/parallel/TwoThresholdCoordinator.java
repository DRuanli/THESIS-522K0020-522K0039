package infrastructure.parallel;

import domain.collection.TopKCollectorInterface;

import static infrastructure.util.NumericalConstants.EPSILON;

/**
 * Encapsulates the two-threshold correctness mechanism for parallel mining.
 *
 * <h3>Problem</h3>
 * In Phase 5, multiple ForkJoin threads mine different prefix subtrees concurrently.
 * If Thread A raises the dynamic admission threshold while Thread B is deciding
 * whether to explore a subtree, Thread B might skip a subtree that would have
 * produced a valid top-k pattern under the threshold at the time Thread B started.
 *
 * <h3>Solution: two distinct thresholds</h3>
 * <ol>
 *   <li><b>{@code initialThreshold}</b> — {@code volatile} snapshot taken in Phase 4
 *       <em>before</em> any mining thread starts.  Used for pruning prefix subtrees
 *       ({@link #shouldPrunePrefix(double)}).  Immutable once set.</li>
 *   <li><b>Dynamic threshold</b> — {@link TopKPatternCollector#admissionThreshold},
 *       continuously updated as patterns are admitted.  Used only for evaluating
 *       individual itemsets within a thread's own subtree.</li>
 * </ol>
 *
 * <p>This guarantees that no subtree is incorrectly pruned due to a threshold
 * raised by another thread, while still benefiting from tighter thresholds
 * within each subtree's local exploration.
 *
 * <p><b>Performance note:</b> Uses the interface {@link TopKCollectorInterface}
 * to support multiple collector implementations. Calls {@code getAdmissionThreshold()}
 * method instead of direct field access.
 */
public final class TwoThresholdCoordinator {

    /**
     * Static threshold captured before parallel Phase 5 begins.
     * {@code volatile} so all threads see the latest value after
     * {@link #captureInitialThreshold()}.
     */
    private volatile double initialThreshold;

    /** Collector interface; uses getAdmissionThreshold() for threshold access. */
    private final TopKCollectorInterface collector;

    /**
     * Constructs a coordinator backed by the given pattern collector.
     *
     * @param collector the Top-K pattern collector whose threshold is managed
     */
    public TwoThresholdCoordinator(TopKCollectorInterface collector) {
        this.collector = collector;
        this.initialThreshold = 0.0;
    }

    /**
     * Captures the current dynamic threshold as the immutable {@code initialThreshold}.
     *
     * <p><b>CRITICAL:</b> Must be called in Phase 4, after all single-item patterns
     * have been evaluated but <em>before</em> any ForkJoin mining task is submitted.
     */
    public void captureInitialThreshold() {
        this.initialThreshold = collector.getAdmissionThreshold();  // Interface method call
    }

    /**
     * Returns the static initial threshold (Phase 4 snapshot).
     *
     * @return the captured initial mining threshold; {@code 0.0} if not yet captured
     */
    public double getInitialThreshold() {
        return initialThreshold;
    }

    /**
     * Returns {@code true} if a prefix's PTWU is below the initial threshold,
     * meaning no pattern rooted at that prefix can ever be a top-k result.
     *
     * <p>Uses {@link #initialThreshold} (not the dynamic threshold) to prevent
     * race conditions in which another thread has already raised the threshold.
     *
     * @param prefixPTWU PTWU of the prefix itemset
     * @return {@code true} if the prefix subtree should be pruned
     */
    public boolean shouldPrunePrefix(double prefixPTWU) {
        return prefixPTWU < initialThreshold - EPSILON;
    }
}
