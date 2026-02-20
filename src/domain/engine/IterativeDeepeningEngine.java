package domain.engine;

import domain.model.ItemRanking;
import domain.model.UtilityProbabilityList;
import domain.collection.TopKCollectorInterface;

import java.util.*;

import static infrastructure.util.NumericalConstants.EPSILON;

/**
 * Iterative Deepening DFS (IDDFS) engine: repeated DFS with increasing depth limits.
 *
 * <p>Runs DFS multiple times with limits 1, 2, 3, … until all reachable nodes have
 * been visited. At depth limit {@code d}, only patterns with at most {@code d}
 * extensions beyond the prefix are explored:
 * <ul>
 *   <li>Pass 1: all 2-itemsets (prefix + 1 extension).</li>
 *   <li>Pass 2: all 2-itemsets and 3-itemsets.</li>
 *   <li>Pass d: all patterns up to size (prefix_size + d).</li>
 * </ul>
 *
 * <p>This achieves the same level-ordering as BFS while using only O(depth) stack
 * space per pass.  The admission threshold rises from small patterns first, enabling
 * stronger pruning in subsequent passes with tighter thresholds.
 *
 * <p>The total recomputation cost is bounded by O(b × T_DFS) where b is the effective
 * branching factor after pruning — typically small — making IDDFS practical.
 *
 * <p>The loop terminates early if a pass completes without any node being cut off by the
 * depth limit ({@code !foundCutoff}), indicating the full search space has been explored.
 *
 * <p><b>Correctness guarantee:</b> EXACT — equivalent to full DFS over all reachable nodes.
 * <br><b>Memory:</b> O(depth) per pass.
 */
public final class IterativeDeepeningEngine implements SearchEngine {

    private final double minProbability;
    private final boolean ptwuPruningEnabled;
    private final UPUListJoinerInterface joiner;
    private final TopKCollectorInterface collector;
    private final List<Integer> sortedItems;
    private final Map<Integer, UtilityProbabilityList> singleItemLists;

    /**
     * Constructs an Iterative Deepening DFS engine with the specified mining parameters.
     *
     * @param minProbability      minimum EP threshold (patterns with EP < this are pruned)
     * @param ptwuPruningEnabled  whether to enable PTWU-based pruning (true for better performance)
     * @param joiner              UPU-List join strategy (TwoPointer, ExponentialSearch, BinarySearch)
     * @param collector           Top-K pattern collector (maintains K best patterns, dynamic threshold)
     * @param itemRanking         item ranking by PTWU ascending (defines canonical extension order)
     * @param singleItemLists     UPU-Lists for all valid single items (used for joins)
     */
    public IterativeDeepeningEngine(double minProbability,
                                     boolean ptwuPruningEnabled,
                                     UPUListJoinerInterface joiner,
                                     TopKCollectorInterface collector,
                                     ItemRanking itemRanking,
                                     Map<Integer, UtilityProbabilityList> singleItemLists) {
        this.minProbability = minProbability;
        this.ptwuPruningEnabled = ptwuPruningEnabled;
        this.joiner = joiner;
        this.collector = collector;
        this.sortedItems = itemRanking.getSortedItems();
        this.singleItemLists = singleItemLists;
    }

    @Override
    public void exploreExtensions(UtilityProbabilityList prefix, int startIndex) {
        int maxPossibleDepth = sortedItems.size() - startIndex;
        for (int depthLimit = 1; depthLimit <= maxPossibleDepth; depthLimit++) {
            boolean foundCutoff = dfsWithLimit(prefix, startIndex, 0, depthLimit);
            // If no node was cut off by the depth limit, all reachable nodes have been explored
            if (!foundCutoff) break;
        }
    }

    /**
     * DFS with depth limit. Returns true if any node was cut off by the depth limit
     * (indicating there may be more patterns to find in a deeper pass).
     */
    private boolean dfsWithLimit(UtilityProbabilityList current, int startIndex,
                                  int currentDepth, int depthLimit) {
        if (currentDepth >= depthLimit) {
            // Depth limit reached — signal that unexplored nodes exist
            return true;
        }

        if (ptwuPruningEnabled && current.ptwu < collector.getAdmissionThreshold() - EPSILON) {
            return false;
        }

        double currentThreshold = collector.getAdmissionThreshold();
        boolean cutoffOccurred = false;

        for (int i = startIndex; i < sortedItems.size(); i++) {
            int extItem = sortedItems.get(i);
            UtilityProbabilityList extList = singleItemLists.get(extItem);
            if (extList == null) continue;

            UtilityProbabilityList joined = joiner.join(current, extList, extItem, currentThreshold);
            if (joined == null || joined.entryCount == 0) continue;

            // Multi-tier pruning
            if (joined.existentialProbability < minProbability - EPSILON) continue;
            if (ptwuPruningEnabled && joined.ptwu < currentThreshold - EPSILON) continue;
            if (joined.positiveUpperBound < currentThreshold - EPSILON) continue;

            if (joined.expectedUtility >= currentThreshold - EPSILON) {
                collector.tryCollect(joined);
                currentThreshold = collector.getAdmissionThreshold();
            }

            cutoffOccurred |= dfsWithLimit(joined, i + 1, currentDepth + 1, depthLimit);
        }

        return cutoffOccurred;
    }
}
