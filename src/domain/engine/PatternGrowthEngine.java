package domain.engine;

import domain.collection.TopKCollectorInterface;
import domain.model.ItemRanking;
import domain.model.UtilityProbabilityList;

import java.util.List;
import java.util.Map;

import static infrastructure.util.NumericalConstants.EPSILON;

/**
 * Baseline recursive depth-first search engine (standard DFS).
 *
 * <p>Implements the core prefix-growth algorithm as described in the PTK-HUIM paper.
 * Starting from a prefix UPU-List, recursively extends it with each candidate item
 * in PTWU-ascending order, pruning branches via three-tier checks:
 * <ol>
 *   <li><b>EP pruning:</b> {@code EP(X) < minProb} — prune entire subtree (antimonotone).</li>
 *   <li><b>PTWU pruning:</b> {@code PTWU(X) < threshold} — prune subtree (monotone UB).</li>
 *   <li><b>PUB pruning:</b> {@code PUB(X) < threshold} — prune subtree (tighter UB).</li>
 * </ol>
 *
 * <p><b>Dynamic threshold pruning:</b>
 * <ul>
 *   <li>{@code collector.getAdmissionThreshold()} (dynamic, volatile) is read once per
 *       recursive call and used for all pruning decisions within that call.</li>
 *   <li>The threshold increases monotonically as better patterns are discovered.</li>
 *   <li>More aggressive pruning over time - provably safe due to PTWU upper bound property.</li>
 *   <li>If PTWU &lt; threshold, then EU ≤ PTWU &lt; threshold, so pattern cannot qualify.</li>
 * </ul>
 *
 * <p><b>Correctness guarantee:</b> EXACT. All non-pruned nodes are visited. No false negatives.
 * <br><b>Memory:</b> O(depth) stack — most efficient among all engines.
 * <br><b>Traversal order:</b> lexicographic by PTWU-rank (no reordering).
 */
public final class PatternGrowthEngine implements SearchEngine {

    private final double minProbability;
    private final UPUListJoinerInterface joiner;
    private final TopKCollectorInterface collector;
    private final List<Integer> sortedItems;
    private final Map<Integer, UtilityProbabilityList> singleItemLists;

    /**
     * Constructs a DFS engine.
     *
     * @param minProbability   minimum EP threshold for pattern qualification
     * @param joiner           UPU-List join operator (any implementation)
     * @param collector        thread-safe Top-K pattern collector
     * @param itemRanking      PTWU-ascending item order
     * @param singleItemLists  single-item UPU-Lists (Phase 2 output)
     */
    public PatternGrowthEngine(double minProbability,
                               UPUListJoinerInterface joiner,
                               TopKCollectorInterface collector,
                               ItemRanking itemRanking,
                               Map<Integer, UtilityProbabilityList> singleItemLists) {
        this.minProbability = minProbability;
        this.joiner = joiner;
        this.collector = collector;
        this.sortedItems = itemRanking.getSortedItems();
        this.singleItemLists = singleItemLists;
    }

    /**
     * Recursively explores all extensions of {@code prefix} in PTWU order.
     *
     * <p>Uses the dynamic {@code admissionThreshold} for all pruning and admission decisions,
     * refreshing it after each successful collection to exploit rising thresholds.
     *
     * @param prefix     UPU-List of the current prefix itemset
     * @param startIndex first item rank to consider as extension
     */
    @Override
    public void exploreExtensions(UtilityProbabilityList prefix, int startIndex) {
        // Read dynamic threshold once at start to minimize volatile reads
        double currentThreshold = collector.getAdmissionThreshold();

        // Dynamic threshold guard: prune subtree if prefix PTWU cannot beat current threshold.
        // Safe within a single DFS call tree (threshold only rises, single-threaded path).
        if (prefix.ptwu < currentThreshold - EPSILON) {
            return;
        }

        // Cache loop invariants to avoid repeated field access and method calls
        List<Integer> items = sortedItems;
        int itemCount = items.size();
        Map<Integer, UtilityProbabilityList> lists = singleItemLists;

        for (int i = startIndex; i < itemCount; i++) {
            int extItem = items.get(i);
            UtilityProbabilityList extList = lists.get(extItem);
            if (extList == null) continue;  // Item filtered out by EP < minProb in Phase 1

            // Join prefix with extension item to create candidate itemset
            UtilityProbabilityList joinedList = joiner.join(prefix, extList, extItem, currentThreshold);
            if (joinedList == null || joinedList.entryCount == 0) continue;

            // ========================================================================
            // THREE-TIER PRUNING STRATEGY (order matters for performance!)
            // ========================================================================
            // The pruning tests are ordered from cheapest to most expensive:
            //
            // 1. EP check (cheapest) — single field access, no computation
            // 2. PTWU check (cheap) — single field access, already computed
            // 3. PUB check (most expensive) — requires scanning joined list entries
            //
            // We test in this order to fail fast on cheap checks before doing
            // expensive PUB computation. All three are necessary because they prune
            // different parts of the search space with different tightness bounds.

            // TIER 1: Existential Probability (EP) Pruning
            // If EP < minProbability, pattern is unreliable (low chance of appearing)
            // Property: Anti-monotone (EP can only decrease with itemset growth)
            // Cost: O(1) — single field access
            if (joinedList.existentialProbability < minProbability - EPSILON) continue;

            // TIER 2: PTWU (Probabilistic Transaction-Weighted Utility) Pruning
            // If PTWU < threshold, all supersets also have PTWU < threshold
            // Property: Monotone upper bound on EU (looser than PUB but cheaper)
            // Cost: O(1) — single field access (computed during join)
            if (joinedList.ptwu < currentThreshold - EPSILON) continue;

            // TIER 3: PUB (Positive Upper Bound) Pruning
            // Tightest upper bound: PUB ≥ EU, and if PUB < threshold, EU < threshold guaranteed
            // Property: Monotone upper bound on EU (tighter than PTWU, closer to actual EU)
            // Cost: O(1) — field access (computed during list construction)
            // Note: PUB is tested AFTER PTWU because PTWU is equally cheap but prunes more aggressively
            //       in early search tree levels (PTWU overestimates more than PUB for large itemsets)
            if (joinedList.positiveUpperBound < currentThreshold - EPSILON) continue;

            // ========================================================================
            // ADMISSION AND THRESHOLD MANAGEMENT
            // ========================================================================
            // If candidate passes all pruning checks AND has EU ≥ threshold, collect it
            // After collection, REFRESH threshold since collector may have evicted a weaker pattern
            // and raised the bar (this is why we use local variable currentThreshold)
            if (joinedList.expectedUtility >= currentThreshold - EPSILON) {
                collector.tryCollect(joinedList);
                // CRITICAL: Refresh threshold immediately after collection
                // If tryCollect() admitted this pattern and evicted a weaker one,
                // admissionThreshold has increased. Using the new (higher) threshold
                // for subsequent iterations prunes more aggressively.
                currentThreshold = collector.getAdmissionThreshold();
            }

            // ========================================================================
            // RECURSIVE EXTENSION
            // ========================================================================
            // Recursively explore supersets of this candidate (extend with items i+1, i+2, ...)
            // Canonical ordering prevents duplicates: only extend with higher-ranked items
            exploreExtensions(joinedList, i + 1);

            // CRITICAL: Refresh threshold again after recursion returns
            // The recursive subtree may have collected many patterns and raised the threshold
            // Using the updated threshold for next loop iteration (i+1, i+2, ...) enables
            // more aggressive pruning of remaining extension candidates
            currentThreshold = collector.getAdmissionThreshold();
        }
    }
}
