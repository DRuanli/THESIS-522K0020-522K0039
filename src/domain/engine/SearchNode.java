package domain.engine;

import domain.model.UtilityProbabilityList;

/**
 * Represents a node in the search frontier for non-recursive traversal strategies.
 *
 * <p>Used by {@link BestFirstSearchEngine}, {@link BreadthFirstSearchEngine}, and
 * {@link IterativeDeepeningEngine} to maintain the exploration frontier in a
 * priority queue or FIFO queue.
 *
 * <p>Each node encapsulates:
 * <ul>
 *   <li><b>list</b> — the UPU-List of the current prefix itemset</li>
 *   <li><b>startIndex</b> — the first item rank to consider for extension (PTWU order)</li>
 * </ul>
 *
 * <p>Nodes are expanded by enumerating all candidate extensions {@code sortedItems[startIndex..]}
 * and applying multi-tier pruning to each candidate.
 *
 * <p>This class is immutable and package-private — only used internally by search engines.
 */
final class SearchNode {
    /** The UPU-List of the current prefix itemset. */
    final UtilityProbabilityList list;

    /** The first item rank to consider for extension (in PTWU-ascending order). */
    final int startIndex;

    /**
     * Constructs a search node.
     *
     * @param list       the UPU-List of the prefix
     * @param startIndex the first item rank to extend from
     */
    SearchNode(UtilityProbabilityList list, int startIndex) {
        this.list = list;
        this.startIndex = startIndex;
    }
}
