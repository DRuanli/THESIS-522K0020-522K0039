package domain.engine;

import domain.model.HighUtilityPattern;
import domain.model.UtilityProbabilityList;

import java.util.List;

/**
 * Interface for pattern collection strategies during Phase 5 mining.
 *
 * <p>A {@code PatternCollector} maintains the Top-K result set and exposes
 * the current <em>admission threshold</em> — the minimum EU a candidate must
 * have to displace the weakest pattern in the collection.
 *
 * <p>The admission threshold is the primary dynamic pruning signal:
 * engines read it via {@link oop.domain.collection.TopKPatternCollector#admissionThreshold}
 * (a {@code volatile} field for lock-free fast-path checks) and prune any
 * candidate whose upper bound falls below it.
 *
 * <p>The only production implementation is {@link oop.domain.collection.TopKPatternCollector},
 * which uses a {@link java.util.TreeSet} min-heap of capacity {@code k} protected by a
 * {@link java.util.concurrent.locks.ReentrantLock}.
 *
 * @see domain.collection.TopKPatternCollector
 */
public interface PatternCollector {

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
}
