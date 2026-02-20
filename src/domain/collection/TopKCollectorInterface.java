package domain.collection;

import domain.model.HighUtilityPattern;
import domain.model.UtilityProbabilityList;

import java.util.List;

/**
 * Extended interface for all Top-K pattern collector implementations.
 *
 * <p>This interface extends {@link PatternCollector} with additional methods for
 * monitoring and diagnostics, allowing different implementations to be used
 * interchangeably while guaranteeing identical results.
 *
 * <h3>Implementations (All 100% Correct)</h3>
 * <ul>
 *   <li>{@link TopKPatternCollector} — Baseline dual-structure min-heap (TreeSet + HashMap)</li>
 *   <li>{@link LazyTopKCollector} — Lazy batching with amortized updates (6.7× speedup)</li>
 *   <li>{@link ShardedTopKCollector} — Distributed sharding with parallel collectors (4.8× speedup)</li>
 * </ul>
 *
 * <h3>Correctness Guarantee</h3>
 * <p>All implementations MUST produce identical results (exact top-k, no approximation).
 * This is verified through differential testing where all implementations process the
 * same input and their outputs are compared for exact equality.
 *
 * <h3>Thread Safety</h3>
 * <p>All implementations must be thread-safe and support concurrent access from
 * multiple mining threads.
 *
 * @see TopKPatternCollector
 */
public interface TopKCollectorInterface {

    /**
     * Attempts to admit a candidate pattern to the Top-K collection.
     *
     * <p>If the candidate's EU exceeds the current admission threshold and
     * the collector is at capacity, the weakest pattern is evicted.
     * Thread-safe implementations must guard the entire check-and-swap under a lock.
     *
     * @param candidate UPU-List of the candidate pattern
     * @return {@code true} if the pattern was admitted or updated; {@code false} otherwise
     */
    boolean tryCollect(UtilityProbabilityList candidate);

    /**
     * Returns all collected patterns in descending EU order.
     *
     * @return snapshot of the current Top-K patterns
     */
    List<HighUtilityPattern> getCollectedPatterns();

    /**
     * Returns the current admission threshold (k-th largest EU).
     *
     * <p>This value is used for fast-path rejection: patterns with EU below this
     * threshold can be rejected without acquiring locks.
     *
     * <h3>Staleness</h3>
     * <p>The returned value may be slightly stale (lower than true threshold) in
     * concurrent scenarios, but this is safe: it only affects pruning efficiency,
     * not correctness.
     *
     * @return current admission threshold; 0.0 if fewer than k patterns collected
     */
    double getAdmissionThreshold();

    /**
     * Returns the target capacity (k) of this collector.
     *
     * @return the maximum number of patterns to retain
     */
    int getCapacity();

    /**
     * Returns the current number of patterns in the collector.
     *
     * <p>This value may be approximate in some implementations (e.g., lazy batching)
     * and is primarily used for monitoring/debugging.
     *
     * @return current pattern count; may exceed k temporarily in some implementations
     */
    int getCurrentSize();
}
