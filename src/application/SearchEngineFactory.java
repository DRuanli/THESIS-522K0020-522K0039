package application;

import domain.collection.TopKCollectorInterface;
import domain.engine.*;
import domain.model.ItemRanking;
import domain.model.UtilityProbabilityList;

import java.util.Map;

/**
 * Factory for creating {@link SearchEngine} instances based on configuration.
 *
 * <p>Centralizes all engine creation logic in a single class, following the Factory
 * pattern. This decouples {@link MiningOrchestrator} from concrete engine implementations
 * and makes it easy to add new traversal strategies without modifying the orchestrator.
 *
 * <h3>Supported strategies</h3>
 * <ul>
 *   <li><b>DFS</b> — Recursive depth-first search (default, baseline)</li>
 *   <li><b>BEST_FIRST</b> — Priority queue on PUB (greedy, raises threshold fast)</li>
 *   <li><b>BREADTH_FIRST</b> — FIFO queue (level-order, good for small patterns)</li>
 *   <li><b>IDDFS</b> — Iterative deepening DFS (BFS-order with DFS memory)</li>
 * </ul>
 *
 * <h3>Future strategies</h3>
 * <ul>
 *   <li><b>SORTED_DFS</b> — Branch-and-bound best-child-first ordering</li>
 *   <li><b>TWO_PHASE_DFS</b> — Eager look-ahead pass then DFS with raised threshold</li>
 *   <li><b>RANDOM_DFS</b> — Shuffled extension order per level</li>
 *   <li><b>WORST_FIRST</b> — Min-PUB priority queue (inverse of Best-First, baseline)</li>
 * </ul>
 *
 * @see SearchEngine
 * @see MiningConfiguration.SearchStrategy
 */
public final class SearchEngineFactory {

    /**
     * Creates a {@link UPUListJoinerInterface} based on the configured join strategy.
     *
     * <p>All three strategies produce identical results but with different performance
     * characteristics:
     * <ul>
     *   <li><b>TWO_POINTER</b> — Optimal for PTK-HUIM (default)</li>
     *   <li><b>EXPONENTIAL_SEARCH</b> — Research baseline for skewed lists</li>
     *   <li><b>BINARY_SEARCH</b> — Research baseline for very unbalanced lists</li>
     * </ul>
     *
     * @param strategy the join strategy from configuration
     * @return the configured joiner implementation
     * @throws IllegalStateException if strategy is unknown
     */
    public static UPUListJoinerInterface createJoiner(MiningConfiguration.JoinStrategy strategy) {
        switch (strategy) {
            case TWO_POINTER:
                return new UPUListJoiner();

            case EXPONENTIAL_SEARCH:
                return new UPUListJoiner_ExponentialSearch();

            case BINARY_SEARCH:
                return new UPUListJoiner_BinarySearch();

            default:
                throw new IllegalStateException(
                    "Unknown join strategy: " + strategy + ". " +
                    "This indicates a configuration validation bug.");
        }
    }

    /**
     * Creates a {@link SearchEngine} based on the configured search strategy.
     *
     * <p>All strategies are <em>exact</em> — they explore the same search space and
     * return identical Top-K results. The difference lies in traversal order, which
     * affects pruning effectiveness, memory usage, and cache locality.
     *
     * @param config           mining configuration with strategy selection
     * @param joiner           UPU-List join operator (any implementation)
     * @param collector        thread-safe Top-K pattern collector
     * @param ranking          PTWU-ascending item order
     * @param singleItemLists  single-item UPU-Lists (Phase 2 output)
     * @param initialThreshold Phase 4 snapshot threshold used for subtree pruning
     * @return the configured search engine
     * @throws UnsupportedOperationException if strategy is not yet implemented
     * @throws IllegalStateException if strategy is unknown
     */
    public static SearchEngine createSearchEngine(
            MiningConfiguration config,
            UPUListJoinerInterface joiner,
            TopKCollectorInterface collector,
            ItemRanking ranking,
            Map<Integer, UtilityProbabilityList> singleItemLists,
            double initialThreshold) {

        double minProb = config.getMinProbability();
        boolean ptwuPruningEnabled = true;  // Always enabled for all strategies

        switch (config.getSearchStrategy()) {
            case DFS:
                return new PatternGrowthEngine(
                    minProb, joiner, collector, ranking, singleItemLists, initialThreshold);

            case BEST_FIRST:
                return new BestFirstSearchEngine(
                    minProb, ptwuPruningEnabled, joiner, collector, ranking,
                    singleItemLists, initialThreshold);

            case BREADTH_FIRST:
                return new BreadthFirstSearchEngine(
                    minProb, ptwuPruningEnabled, joiner, collector, ranking,
                    singleItemLists, initialThreshold);

            case IDDFS:
                return new IterativeDeepeningEngine(
                    minProb, ptwuPruningEnabled, joiner, collector, ranking,
                    singleItemLists, initialThreshold);

            default:
                // All valid SearchStrategy enum values are handled above
                throw new IllegalStateException(
                    "Unknown search strategy: " + config.getSearchStrategy() + ". " +
                    "This indicates a configuration validation bug.");
        }
    }

    /**
     * Private constructor to prevent instantiation.
     *
     * <p>This is a pure utility class with only static factory methods.
     */
    private SearchEngineFactory() {
        throw new AssertionError("Factory class — do not instantiate");
    }
}
