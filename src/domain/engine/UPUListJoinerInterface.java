package domain.engine;

import domain.model.UtilityProbabilityList;

/**
 * Interface for UPU-List join operations.
 *
 * <p>All implementations must produce identical results, differing only in
 * performance characteristics based on the intersection algorithm used.
 *
 * <p>Joins include early pruning: if the joined PTWU falls below the dynamic
 * threshold, the join is aborted and {@code null} is returned. This is safe
 * because PTWU is an upper bound on EU, so if PTWU &lt; threshold, then
 * EU ≤ PTWU &lt; threshold, meaning the pattern cannot qualify.
 */
public interface UPUListJoinerInterface {

    /**
     * Joins two UPU-Lists to produce the UPU-List of their union itemset.
     *
     * @param list1            UPU-List of the prefix itemset {@code X}
     * @param list2            UPU-List of the single-item extension {@code {j}}
     * @param extensionItem    item ID of {@code j}
     * @param threshold        current dynamic admission threshold (for early pruning)
     * @return the joined UPU-List {@code L(X ∪ {j})}, or {@code null} if pruned
     */
    UtilityProbabilityList join(UtilityProbabilityList list1,
                                UtilityProbabilityList list2,
                                int extensionItem,
                                double threshold);
}
