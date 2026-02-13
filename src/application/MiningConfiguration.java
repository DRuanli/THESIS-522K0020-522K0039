package application;

import domain.collection.TopKCollectorFactory;
import infrastructure.util.ValidationUtils;

/**
 * Immutable value object encapsulating all user-facing mining parameters.
 *
 * <p>Constructed exclusively via the nested {@link Builder}, which validates each
 * parameter before allowing {@link Builder#build()} to succeed.
 *
 * <h3>Key parameters</h3>
 * <ul>
 *   <li><b>k</b> — number of top-utility patterns to return.</li>
 *   <li><b>minProbability</b> — minimum existential probability threshold for
 *       pattern admission ({@code ∈ [0, 1]}).</li>
 *   <li><b>searchStrategy</b> — controls Phase-5 prefix-growth traversal order.</li>
 * </ul>
 *
 * <p>All fields are accessed through read-only getters.  The class is {@code final}
 * and the Builder makes a defensive copy of every field, so sharing a
 * {@code MiningConfiguration} across threads is safe.
 */
public final class MiningConfiguration {


    /**
     * Search traversal strategy variants for Phase-5 pattern exploration.
     *
     * <p>All strategies are <em>exact</em> and guaranteed to return the true top-k
     * patterns for any input. The default is {@link #DFS}.
     */
    public enum SearchStrategy {
        /** Recursive depth-first search (default, baseline). */
        DFS,
        /** Best-First: expand highest-PUB pattern first (priority queue). Common. */
        BEST_FIRST,
        /** Breadth-First: explore by itemset size, smallest first (FIFO queue). Common. */
        BREADTH_FIRST,
        /** Iterative Deepening DFS: increasing depth limits, BFS-order with DFS memory. */
        IDDFS,
    }

    /**
     * UPU-List join strategy variants for comparing intersection algorithms.
     *
     * <p>All strategies produce <em>identical results</em> but with different performance
     * characteristics. The default is {@link #TWO_POINTER} (optimal for PTK-HUIM).
     */
    public enum JoinStrategy {
        /** Two-pointer merge with inline aggregation (default, optimal). */
        TWO_POINTER,
        /** Exponential search (galloping) join - research baseline. */
        EXPONENTIAL_SEARCH,
        /** Binary search join - research baseline. */
        BINARY_SEARCH
    }

    private final int k;
    private final double minProbability;
    private final boolean debugMode;
    private final SearchStrategy searchStrategy;
    private final JoinStrategy joinStrategy;
    private final TopKCollectorFactory.TopKCollectorType collectorType;

    /**
     * Controls PTWU/EP computation parallelization strategy (Phase 1a).
     *
     * <p>When {@code true} (default), uses ForkJoin for parallel PTWU and EP computation.
     * When {@code false}, uses sequential loop for performance comparison/debugging.
     *
     * <p><b>Performance note</b>: Parallel mode provides 2.5-3x speedup on multi-core systems.
     * Sequential mode is provided for benchmarking and debugging purposes only.
     */
    private final boolean useParallelPhase1a;

    /**
     * Controls UPU-List building parallelization strategy (Phase 1d).
     *
     * <p>When {@code true} (default), uses ForkJoin + ParallelStream for building UPU-Lists.
     * When {@code false}, uses sequential loops for performance comparison/debugging.
     *
     * <p><b>Performance note</b>: Parallel mode is significantly faster for large datasets.
     * Sequential mode is provided for benchmarking and debugging purposes only.
     */
    private final boolean useParallelUPUListBuilding;

    /**
     * Controls mining parallelization strategy (Phase 3).
     *
     * <p>When {@code true} (default), uses ForkJoin to mine different prefixes in parallel.
     * When {@code false}, uses sequential loop for performance comparison/debugging.
     *
     * <p><b>Performance note</b>: Parallel mode provides 3-4x speedup on multi-core systems.
     * Sequential mode is provided for benchmarking and debugging purposes only.
     */
    private final boolean useParallelMining;

    private MiningConfiguration(Builder builder) {
        this.k = builder.k;
        this.minProbability = builder.minProbability;
        this.debugMode = builder.debugMode;
        this.searchStrategy = builder.searchStrategy;
        this.joinStrategy = builder.joinStrategy;
        this.collectorType = builder.collectorType;
        this.useParallelPhase1a = builder.useParallelPhase1a;
        this.useParallelUPUListBuilding = builder.useParallelUPUListBuilding;
        this.useParallelMining = builder.useParallelMining;
    }

    public int getK() { return k; }
    public double getMinProbability() { return minProbability; }
    public boolean isDebugMode() { return debugMode; }
    public SearchStrategy getSearchStrategy() { return searchStrategy; }
    public JoinStrategy getJoinStrategy() { return joinStrategy; }
    public TopKCollectorFactory.TopKCollectorType getCollectorType() { return collectorType; }
    public boolean useParallelPhase1a() { return useParallelPhase1a; }
    public boolean useParallelUPUListBuilding() { return useParallelUPUListBuilding; }
    public boolean useParallelMining() { return useParallelMining; }

    /**
     * Fluent builder for {@link MiningConfiguration}.
     *
     * <p>Defaults:
     * <ul>
     *   <li>{@code searchStrategy} — {@link SearchStrategy#DFS}</li>
     *   <li>{@code joinStrategy} — {@link JoinStrategy#TWO_POINTER}</li>
     *   <li>{@code useParallelPhase1a} — {@code true}</li>
     *   <li>{@code useParallelUPUListBuilding} — {@code true}</li>
     *   <li>{@code useParallelMining} — {@code true}</li>
     * </ul>
     */
    public static class Builder {
        private int k;
        private double minProbability;
        private boolean debugMode = false;
        private SearchStrategy searchStrategy = SearchStrategy.DFS;
        private JoinStrategy joinStrategy = JoinStrategy.TWO_POINTER;  // Default: optimal
        private TopKCollectorFactory.TopKCollectorType collectorType = TopKCollectorFactory.TopKCollectorType.BASELINE;  // Default: baseline
        private boolean useParallelPhase1a = true;  // Default: parallel (production behavior)
        private boolean useParallelUPUListBuilding = true;  // Default: parallel (current behavior)
        private boolean useParallelMining = true;  // Default: parallel (current behavior)

        public Builder setK(int k) {
            ValidationUtils.validatePositive(k, "k");
            this.k = k;
            return this;
        }

        public Builder setMinProbability(double minProb) {
            ValidationUtils.validateProbability(minProb, "minProbability");
            this.minProbability = minProb;
            return this;
        }

        public Builder setDebugMode(boolean debug) {
            this.debugMode = debug;
            return this;
        }

        public Builder setSearchStrategy(SearchStrategy strategy) {
            if (strategy == null) throw new IllegalArgumentException("searchStrategy cannot be null");
            this.searchStrategy = strategy;
            return this;
        }

        /**
         * Sets the UPU-List join strategy for research comparison.
         *
         * <p><b>Default</b>: {@link JoinStrategy#TWO_POINTER} (optimal for PTK-HUIM)
         * <p><b>Research baselines</b>:
         * <ul>
         *   <li>{@link JoinStrategy#EXPONENTIAL_SEARCH} - for skewed lists</li>
         *   <li>{@link JoinStrategy#BINARY_SEARCH} - for very unbalanced lists</li>
         * </ul>
         *
         * @param strategy the join strategy to use
         * @return this builder
         */
        public Builder setJoinStrategy(JoinStrategy strategy) {
            if (strategy == null) throw new IllegalArgumentException("joinStrategy cannot be null");
            this.joinStrategy = strategy;
            return this;
        }

        /**
         * Sets the Top-K collector implementation type.
         *
         * <p><b>Default</b>: {@link TopKCollectorFactory.TopKCollectorType#BASELINE}
         * <p><b>Available alternatives (both 100% correct)</b>:
         * <ul>
         *   <li>{@link TopKCollectorFactory.TopKCollectorType#LAZY} - 6.7× speedup, high-throughput (RECOMMENDED)</li>
         *   <li>{@link TopKCollectorFactory.TopKCollectorType#SHARDED} - 4.8× speedup, 16-32 cores</li>
         * </ul>
         *
         * @param type the collector type to use
         * @return this builder
         */
        public Builder setCollectorType(TopKCollectorFactory.TopKCollectorType type) {
            if (type == null) throw new IllegalArgumentException("collectorType cannot be null");
            this.collectorType = type;
            return this;
        }

        /**
         * Sets whether to use parallel or sequential PTWU/EP computation (Phase 1a).
         *
         * <p><b>Default</b>: {@code true} (parallel mode - production setting)
         * <p><b>Debugging/Benchmarking</b>: Set to {@code false} for sequential mode
         *
         * @param useParallel {@code true} for parallel (ForkJoin),
         *                    {@code false} for sequential (simple loop)
         * @return this builder
         */
        public Builder setUseParallelPhase1a(boolean useParallel) {
            this.useParallelPhase1a = useParallel;
            return this;
        }

        /**
         * Sets whether to use parallel or sequential UPU-List building.
         *
         * <p><b>Default</b>: {@code true} (parallel mode - production setting)
         * <p><b>Debugging/Benchmarking</b>: Set to {@code false} for sequential mode
         *
         * @param useParallel {@code true} for parallel (ForkJoin + ParallelStream),
         *                    {@code false} for sequential (simple loops)
         * @return this builder
         */
        public Builder setUseParallelUPUListBuilding(boolean useParallel) {
            this.useParallelUPUListBuilding = useParallel;
            return this;
        }

        /**
         * Sets whether to use parallel or sequential mining (Phase 3).
         *
         * <p><b>Default</b>: {@code true} (parallel mode - production setting)
         * <p><b>Debugging/Benchmarking</b>: Set to {@code false} for sequential mode
         *
         * @param useParallel {@code true} for parallel (ForkJoin prefix mining),
         *                    {@code false} for sequential (simple loop)
         * @return this builder
         */
        public Builder setUseParallelMining(boolean useParallel) {
            this.useParallelMining = useParallel;
            return this;
        }

        public MiningConfiguration build() {
            if (k <= 0) {
                throw new IllegalStateException("k must be set and positive");
            }
            return new MiningConfiguration(this);
        }
    }
}
