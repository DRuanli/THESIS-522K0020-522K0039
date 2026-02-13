package infrastructure.parallel;

import domain.engine.SearchEngine;
import domain.model.ItemRanking;
import domain.model.UtilityProbabilityList;

import java.util.*;
import java.util.concurrent.RecursiveAction;


/**
 * {@link RecursiveAction} that implements ForkJoin prefix-based pattern mining.
 *
 * <p>The full item range {@code [0, n)} is recursively bisected until each leaf
 * task handles a single prefix item.  Work balancing uses PTWU-weighted splitting
 * ({@link WorkBalancedSplitter}) so that high-utility prefixes (which have more
 * transaction-intersection work) are isolated into their own subtasks.
 *
 * <h3>Decomposition strategy</h3>
 * <ol>
 *   <li>If the range has only one item, execute {@link #mineFromPrefix(int)} directly.</li>
 *   <li>If the range size {@code ≤ fineGrainThreshold}, split into one task per item
 *       to maximise work-stealing granularity.</li>
 *   <li>Otherwise, use PTWU-weighted binary split and recurse.</li>
 * </ol>
 *
 * <h3>Prefix pruning</h3>
 * Before invoking the search engine on a prefix, its PTWU is checked against the
 * <em>initial</em> threshold (from {@link TwoThresholdCoordinator}) to avoid
 * starting work on subtrees that cannot contribute to the top-k result.
 *
 * <p>The {@code globalCutoff} pre-computed index ensures only items ranked above
 * the initial threshold are used as extensions (O(1) vs O(log n) binary search
 * per prefix).
 *
 * <p><b>Search strategy polymorphism:</b> This task works with any {@link SearchEngine}
 * implementation (DFS, BFS, Best-First, IDDFS, etc.) via the {@code SearchEngine} interface.
 */
public final class PrefixMiningTask extends RecursiveAction {

    private static final long serialVersionUID = 1L;

    private final SearchEngine engine;
    private final ItemRanking itemRanking;
    private final Map<Integer, UtilityProbabilityList> singleItemLists;
    private final TwoThresholdCoordinator thresholdCoordinator;
    /** Inclusive start of the item-index range this task is responsible for. */
    private final int rangeStart;
    /** Exclusive end of the item-index range this task is responsible for. */
    private final int rangeEnd;
    /** Pre-computed first item index whose PTWU exceeds the initial threshold. */
    private final int globalCutoff;
    private final int fineGrainThreshold;

    /**
     * Constructs a root or child {@code PrefixMiningTask} for the given item range.
     *
     * @param engine               search engine executing the prefix-growth exploration (any strategy)
     * @param itemRanking          total item order (PTWU ascending)
     * @param singleItemLists      UPU-Lists for single-item prefixes
     * @param thresholdCoordinator two-threshold state shared across all tasks
     * @param rangeStart           inclusive start index in {@code itemRanking.getSortedItems()}
     * @param rangeEnd             exclusive end index
     * @param globalCutoff         first item index whose PTWU exceeds the initial threshold
     * @param fineGrainThreshold   range size at which per-item task decomposition begins
     */
    public PrefixMiningTask(SearchEngine engine,
                           ItemRanking itemRanking,
                           Map<Integer, UtilityProbabilityList> singleItemLists,
                           TwoThresholdCoordinator thresholdCoordinator,
                           int rangeStart, int rangeEnd,
                           int globalCutoff,
                           int fineGrainThreshold) {
        this.engine = engine;
        this.itemRanking = itemRanking;
        this.singleItemLists = singleItemLists;
        this.thresholdCoordinator = thresholdCoordinator;
        this.rangeStart = rangeStart;
        this.rangeEnd = rangeEnd;
        this.globalCutoff = globalCutoff;
        this.fineGrainThreshold = fineGrainThreshold;
    }

    /**
     * Executes this task's portion of the parallel mining workload.
     *
     * <p>This method implements a three-way recursive decomposition strategy:
     *
     * <h3>Strategy 1: Base Case (rangeSize ≤ 1)</h3>
     * <p>When the task is responsible for a single prefix item, mine it directly
     * using {@link #mineFromPrefix(int)}. No further decomposition needed.
     *
     * <h3>Strategy 2: Fine-Grain Decomposition (rangeSize ≤ fineGrainThreshold)</h3>
     * <p>When the range is small (≤ 16 items by default), create one subtask per item
     * and submit them all for work-stealing. This maximizes parallelism by allowing
     * idle worker threads to steal individual prefix tasks.
     *
     * <p><b>Rationale:</b> Near the leaves of the recursion tree, we want maximum
     * granularity to keep all CPU cores busy. Creating individual tasks per prefix
     * allows fine-grained load balancing.
     *
     * <h3>Strategy 3: PTWU-Weighted Bisection (rangeSize > fineGrainThreshold)</h3>
     * <p>For large ranges, use {@link WorkBalancedSplitter} to find a PTWU-weighted
     * split point that balances computational work (not just item count). High-PTWU
     * items have more transactions and require more join operations, so naive midpoint
     * splitting would create imbalanced workloads.
     *
     * <p><b>Example:</b> If items [0-7] have PTWU [10, 10, 10, 10, 100, 100, 100, 100],
     * the split point will be around index 4 (not 3) to balance total PTWU across
     * left and right subtasks.
     *
     * @implNote This method is called by the ForkJoin framework. Never call directly.
     */
    @Override
    protected void compute() {
        int rangeSize = rangeEnd - rangeStart;

        // STRATEGY 1: Base case - mine single prefix directly
        if (rangeSize <= 1) {
            // Only one prefix in this task's range - execute mining directly
            if (rangeStart < itemRanking.getSortedItems().size()) {
                mineFromPrefix(rangeStart);
            }
            return;
        }

        // STRATEGY 2: Fine-grain decomposition - create one task per prefix
        if (rangeSize <= fineGrainThreshold) {
            // Small range: maximize work-stealing opportunities by creating individual tasks
            // Each worker thread can steal a single-prefix task for fine-grained load balancing
            List<PrefixMiningTask> subtasks = new ArrayList<>(rangeSize);
            for (int i = rangeStart; i < rangeEnd; i++) {
                subtasks.add(createSubtask(i, i + 1));
            }
            // Submit all subtasks for parallel execution (ForkJoin work-stealing)
            invokeAll(subtasks);
        } else {
            // STRATEGY 3: PTWU-weighted bisection - balance computational work, not item count
            // Find split point that balances total PTWU (proxy for computational cost)
            // instead of naive midpoint splitting
            int splitPoint = WorkBalancedSplitter.findSplit(
                itemRanking, singleItemLists, rangeStart, rangeEnd);

            // Recursively process left and right halves in parallel
            invokeAll(
                createSubtask(rangeStart, splitPoint),
                createSubtask(splitPoint, rangeEnd)
            );
        }
    }

    /**
     * Executes pattern mining for a single prefix item.
     *
     * <p>This method is called from the base case of {@link #compute()} when a task
     * is responsible for mining a single prefix. It performs several optimizations
     * and pruning checks before delegating to the search engine.
     *
     * <h3>Execution Steps</h3>
     * <ol>
     *   <li><b>Retrieve prefix UPU-List</b> — Get the single-item list for this prefix</li>
     *   <li><b>Null/empty check</b> — Skip if list wasn't created (filtered in Phase 1d)</li>
     *   <li><b>PTWU pruning</b> — Check if prefix PTWU is below initial threshold
     *       (if so, all supersets will also be below threshold due to monotone property)</li>
     *   <li><b>Compute extension start index</b> — Use max(prefixIndex+1, globalCutoff)
     *       to skip low-PTWU items (O(1) optimization vs. binary search)</li>
     *   <li><b>Delegate to search engine</b> — Execute configured search strategy
     *       (DFS, BFS, BestFirst, IDDFS) on extensions of this prefix</li>
     * </ol>
     *
     * <h3>Global Cutoff Optimization</h3>
     * <p>The {@code globalCutoff} index is pre-computed once at the root task and
     * represents the first item whose PTWU exceeds the initial threshold. By using
     * {@code Math.max(prefixIndex + 1, globalCutoff)}, we ensure that:
     * <ul>
     *   <li>Extensions only consider items with rank > prefixIndex (canonical ordering)</li>
     *   <li>Extensions only consider items with PTWU ≥ initial threshold (pruning)</li>
     *   <li>No binary search needed per prefix (O(1) vs. O(log n))</li>
     * </ul>
     *
     * @param prefixIndex index of the prefix item in {@code itemRanking.getSortedItems()}
     */
    private void mineFromPrefix(int prefixIndex) {
        // Retrieve the sorted item list and get the prefix item ID
        List<Integer> sortedItems = itemRanking.getSortedItems();
        int prefixItem = sortedItems.get(prefixIndex);
        UtilityProbabilityList prefixList = singleItemLists.get(prefixItem);

        // Skip if UPU-List doesn't exist or is empty (filtered out in Phase 1d)
        if (prefixList == null || prefixList.entryCount == 0) return;

        // PTWU pruning: if prefix PTWU < initial threshold, all supersets also fail
        // (monotone upper bound property - no point exploring extensions)
        if (thresholdCoordinator.shouldPrunePrefix(prefixList.ptwu)) {
            return;
        }

        // Compute extension start index using global cutoff optimization
        // max(prefixIndex + 1, globalCutoff) ensures:
        //   1. Only extend with items ranked higher (canonical order)
        //   2. Only extend with items having PTWU >= initial threshold (pruning)
        int startIndex = Math.max(prefixIndex + 1, globalCutoff);

        // Delegate to search engine if there are valid extension candidates
        if (startIndex < sortedItems.size()) {
            engine.exploreExtensions(prefixList, startIndex);
        }
    }

    /**
     * Creates a child subtask for a sub-range of this task's item range.
     *
     * <p>All immutable parameters (engine, itemRanking, singleItemLists, thresholdCoordinator,
     * globalCutoff, fineGrainThreshold) are shared across all subtasks. Only the range
     * boundaries (start, end) differ for each child task.
     *
     * <p><b>Performance note:</b> This method is called frequently during decomposition.
     * All shared references are passed by reference (no copying), so task creation overhead
     * is minimal (< 100 bytes per task object).
     *
     * @param start inclusive start index for the child task's item range
     * @param end   exclusive end index for the child task's item range
     * @return new {@code PrefixMiningTask} responsible for items [start, end)
     */
    private PrefixMiningTask createSubtask(int start, int end) {
        return new PrefixMiningTask(engine, itemRanking, singleItemLists,
                                   thresholdCoordinator, start, end, globalCutoff,
                                   fineGrainThreshold);
    }
}
