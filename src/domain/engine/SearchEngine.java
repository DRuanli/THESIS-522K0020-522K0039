package domain.engine;

import domain.model.UtilityProbabilityList;

/**
 * Common interface for all search/traversal strategies in Phase-5 prefix-growth.
 *
 * <p>All implementing strategies are <em>exact</em> — they explore the same search
 * space and return identical Top-K results. The difference lies in traversal order,
 * which affects:
 * <ul>
 *   <li><b>Pruning effectiveness</b>: Some orders raise the threshold faster.</li>
 *   <li><b>Memory usage</b>: DFS uses O(depth) stack; BFS uses O(frontier) queue.</li>
 *   <li><b>Cache locality</b>: Different access patterns affect CPU cache behavior.</li>
 * </ul>
 *
 * <h3>Implemented strategies</h3>
 * <ul>
 *   <li>{@link PatternGrowthEngine} — Recursive DFS (baseline, default)</li>
 *   <li>{@link BestFirstSearchEngine} — Priority queue on PUB (greedy)</li>
 *   <li>{@link BreadthFirstSearchEngine} — FIFO queue (level-order)</li>
 *   <li>{@link IterativeDeepeningEngine} — Repeated DFS with increasing depth limits</li>
 * </ul>
 *
 * @see MiningOrchestrator#createSearchEngine
 */
public interface SearchEngine {

    /**
     * Explores all extensions of the given prefix in the order defined by this strategy.
     *
     * <p>Starting from {@code prefix}, recursively or iteratively extends it with
     * all candidate items from {@code sortedItems[startIndex..]} in PTWU-ascending order.
     *
     * <p>Implementations must:
     * <ol>
     *   <li>Perform multi-tier pruning (EP, PTWU, PUB) on each candidate extension.</li>
     *   <li>Collect qualifying patterns via {@code collector.tryCollect()}.</li>
     *   <li>Refresh the dynamic threshold after successful collections.</li>
     *   <li>Recursively/iteratively explore non-pruned extensions.</li>
     * </ol>
     *
     * <p><b>Thread safety</b>: This method is called by multiple threads in parallel
     * (one thread per prefix in Phase-5). The {@code collector} is thread-safe, so
     * concurrent calls to {@code tryCollect()} are safe. However, each thread explores
     * its own subtree independently, so no synchronization is needed within this method.
     *
     * @param prefix     the UPU-List of the current prefix itemset
     * @param startIndex the first item rank to consider for extension (PTWU order)
     */
    void exploreExtensions(UtilityProbabilityList prefix, int startIndex);
}
