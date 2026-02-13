package domain.engine;

import domain.model.ItemRanking;
import domain.model.UtilityProbabilityList;
import domain.collection.TopKCollectorInterface;

import java.util.*;

import static infrastructure.util.NumericalConstants.EPSILON;

/**
 * Breadth-First Search engine: explores patterns level by level (by itemset size).
 *
 * <p>Uses a FIFO queue. Because each extension adds exactly one item,
 * BFS naturally processes all 2-itemsets before any 3-itemset, all 3-itemsets
 * before any 4-itemset, and so on.
 *
 * <p><b>Advantage:</b> if many high-utility patterns are small (e.g., individual
 * items or pairs with high profit × probability), BFS finds them early and raises
 * the threshold faster than DFS would.
 *
 * <p>At dequeue time, the dynamic threshold is re-checked against PUB and PTWU
 * to discard stale nodes that no longer satisfy the raised threshold.
 *
 * <p><b>Correctness guarantee:</b> EXACT — all non-pruned nodes are visited.
 * <br><b>Memory:</b> O(frontier) — can be large for dense databases.
 */
public final class BreadthFirstSearchEngine implements SearchEngine {

    private final double minProbability;
    private final boolean ptwuPruningEnabled;
    private final UPUListJoinerInterface joiner;
    private final TopKCollectorInterface collector;
    private final List<Integer> sortedItems;
    private final Map<Integer, UtilityProbabilityList> singleItemLists;
    private final double initialThreshold;

    /**
     * Constructs a Breadth-First Search engine with the specified mining parameters.
     *
     * @param minProbability      minimum EP threshold (patterns with EP < this are pruned)
     * @param ptwuPruningEnabled  whether to enable PTWU-based pruning (true for better performance)
     * @param joiner              UPU-List join strategy (TwoPointer, ExponentialSearch, BinarySearch)
     * @param collector           Top-K pattern collector (maintains K best patterns, admission threshold)
     * @param itemRanking         item ranking by PTWU ascending (defines canonical extension order)
     * @param singleItemLists     UPU-Lists for all valid single items (used for joins)
     * @param initialThreshold    initial admission threshold (0.0 at startup, raised as patterns collected)
     */
    public BreadthFirstSearchEngine(double minProbability,
                                     boolean ptwuPruningEnabled,
                                     UPUListJoinerInterface joiner,
                                     TopKCollectorInterface collector,
                                     ItemRanking itemRanking,
                                     Map<Integer, UtilityProbabilityList> singleItemLists,
                                     double initialThreshold) {
        this.minProbability = minProbability;
        this.ptwuPruningEnabled = ptwuPruningEnabled;
        this.joiner = joiner;
        this.collector = collector;
        this.sortedItems = itemRanking.getSortedItems();
        this.singleItemLists = singleItemLists;
        this.initialThreshold = initialThreshold;
    }

    @Override
    public void exploreExtensions(UtilityProbabilityList prefix, int startIndex) {
        // FIFO queue: level-by-level (BFS) order
        Deque<SearchNode> queue = new ArrayDeque<>();
        queue.offer(new SearchNode(prefix, startIndex));

        while (!queue.isEmpty()) {
            SearchNode node = queue.poll();
            UtilityProbabilityList current = node.list;

            // Dynamic threshold at dequeue: prune nodes made stale by threshold growth
            double currentThreshold = collector.getAdmissionThreshold();
            if (ptwuPruningEnabled && current.ptwu < currentThreshold - EPSILON) continue;
            if (current.positiveUpperBound < currentThreshold - EPSILON) continue;

            for (int i = node.startIndex; i < sortedItems.size(); i++) {
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

                queue.offer(new SearchNode(joined, i + 1));
            }
        }
    }
}
