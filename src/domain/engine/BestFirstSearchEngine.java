package domain.engine;

import domain.model.ItemRanking;
import domain.model.UtilityProbabilityList;
import domain.collection.TopKCollectorInterface;

import java.util.*;

import static infrastructure.util.NumericalConstants.EPSILON;

/**
 * Best-First Search engine: expands the node with the highest Positive Upper Bound first.
 *
 * <p>Maintains a max-priority queue ordered by {@code positiveUpperBound} descending.
 * By always expanding the most promising node, this strategy raises the admission
 * threshold as fast as possible, enabling aggressive pruning of low-potential nodes
 * waiting in the queue.
 *
 * <p>At dequeue time, the dynamic threshold is re-checked: if a node's PUB or PTWU
 * has fallen below the current threshold (due to other threads or the same thread
 * having collected better patterns), the node is discarded without expanding.
 *
 * <p><b>Correctness guarantee:</b> EXACT — all non-pruned nodes are eventually expanded.
 * <br><b>Memory:</b> O(frontier size) — may hold many nodes for dense search spaces.
 * <br><b>Classification:</b> greedy best-first; common in A*-like data mining approaches.
 */
public final class BestFirstSearchEngine implements SearchEngine {

    private final double minProbability;
    private final boolean ptwuPruningEnabled;
    private final UPUListJoinerInterface joiner;
    private final TopKCollectorInterface collector;
    private final List<Integer> sortedItems;
    private final Map<Integer, UtilityProbabilityList> singleItemLists;
    private final double initialThreshold;

    /**
     * Constructs a Best-First Search engine with the specified mining parameters.
     *
     * @param minProbability      minimum EP threshold (patterns with EP < this are pruned)
     * @param ptwuPruningEnabled  whether to enable PTWU-based pruning (true for better performance)
     * @param joiner              UPU-List join strategy (TwoPointer, ExponentialSearch, BinarySearch)
     * @param collector           Top-K pattern collector (maintains K best patterns, admission threshold)
     * @param itemRanking         item ranking by PTWU ascending (defines canonical extension order)
     * @param singleItemLists     UPU-Lists for all valid single items (used for joins)
     * @param initialThreshold    initial admission threshold (0.0 at startup, raised as patterns collected)
     */
    public BestFirstSearchEngine(double minProbability,
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
        // Max-heap on positiveUpperBound — most promising patterns expanded first
        PriorityQueue<SearchNode> pq = new PriorityQueue<>(
            Comparator.comparingDouble((SearchNode n) -> n.list.positiveUpperBound).reversed()
        );
        pq.offer(new SearchNode(prefix, startIndex));

        while (!pq.isEmpty()) {
            SearchNode node = pq.poll();
            UtilityProbabilityList current = node.list;

            // Read dynamic threshold once per node — prune stale nodes immediately
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

                pq.offer(new SearchNode(joined, i + 1));
            }
        }
    }
}
