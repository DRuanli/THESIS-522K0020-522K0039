package application;

import domain.collection.TopKCollectorInterface;
import domain.engine.SearchEngine;
import domain.engine.UPUListJoinerInterface;
import domain.model.*;
import infrastructure.builder.UPUListBuilder;
import infrastructure.computation.PTWUCalculator;
import infrastructure.parallel.PrefixMiningTask;

import java.util.*;
import java.util.concurrent.ForkJoinPool;

import static application.OrchestratorConfiguration.*;
import static infrastructure.util.NumericalConstants.EPSILON;

/**
 * Orchestrates the three-phase PTK-HUIM mining workflow.
 *
 * <h3>Phase sequence</h3>
 * <ol>
 *   <li><b>PHASE 1: PREPROCESSING</b> — Compute PTWU and EP for all items, rank items,
 *       and build single-item UPU-Lists.</li>
 *   <li><b>PHASE 2: INITIALIZATION</b> — Evaluate 1-itemsets and capture initial threshold.</li>
 *   <li><b>PHASE 3: MINING</b> — Parallel/sequential prefix-growth with selected search strategy.</li>
 * </ol>
 *
 * <h3>Refactoring notes</h3>
 * <p>This class has been refactored to improve maintainability:
 * <ul>
 *   <li>Constants moved to {@link OrchestratorConfiguration}</li>
 *   <li>Engine creation moved to {@link SearchEngineFactory}</li>
 *   <li>UPU-List building moved to {@link UPUListBuilder}</li>
 *   <li>Sequential/parallel modes unified with conditional dispatching</li>
 * </ul>
 *
 * @see MiningConfiguration
 * @see MiningContext
 * @see SearchEngineFactory
 */
public final class MiningOrchestrator {

    private final MiningConfiguration config;
    private final PTWUCalculator ptwuCalculator;
    private final ForkJoinPool executorPool;

    /**
     * Constructs an orchestrator for the given configuration.
     *
     * <p>Creates a dedicated {@link ForkJoinPool} for parallel phases.
     * The pool is reused across all parallel operations (Phase 1a, 1d, 3).
     *
     * @param config      immutable mining parameters
     * @param profitTable item profit lookup (used for PTWU and utility computation)
     */
    public MiningOrchestrator(MiningConfiguration config, ProfitTable profitTable) {
        this.config = config;
        this.ptwuCalculator = new PTWUCalculator(profitTable);
        this.executorPool = new ForkJoinPool(DEFAULT_PARALLELISM);
    }

    /**
     * Executes the three-phase PTK-HUIM algorithm.
     *
     * @param profitTable profit table (same instance as passed to constructor)
     * @param database    list of uncertain transactions to mine
     * @return top-k patterns ordered by Expected Utility descending
     */
    public List<HighUtilityPattern> mine(ProfitTable profitTable, List<Transaction> database) {
        MiningContext context = new MiningContext(config, profitTable, database);

        long startTime = System.currentTimeMillis();

        // PHASE 1: Preprocessing
        executePhase1(context);
        logPhaseCompletion(1, startTime);

        // PHASE 2: Initialization
        long phase2Start = System.currentTimeMillis();
        executePhase2(context);
        logPhaseCompletion(2, phase2Start);

        // PHASE 3: Mining
        long phase3Start = System.currentTimeMillis();
        executePhase3(context);
        logPhaseCompletion(3, phase3Start);

        if (config.isDebugMode()) {
            long totalTime = System.currentTimeMillis() - startTime;
            System.err.printf("[TOTAL] Time: %d ms%n", totalTime);
        }

        return context.getCollector().getCollectedPatterns();
    }

    // =========================================================================
    // Phase 1: Preprocessing
    // =========================================================================

    /**
     * Phase 1: Computes PTWU, EP, item ranking, and builds UPU-Lists.
     *
     * @param context the mining context
     */
    private void executePhase1(MiningContext context) {
        if (config.isDebugMode()) {
            System.err.println("[Phase 1: PREPROCESSING] Computing PTWU, EP, ranking items, " +
                "and building UPU-Lists...");
        }

        // Step 1a: Compute PTWU (upper bound for pruning) and EP (existential probability)
        // Uses parallel or sequential mode based on configuration
        PTWUCalculator.PTWUComputationResult phase1Result = computePTWUAndEP(context);

        // Step 1b: Store PTWU/EP arrays in context and filter out low-EP items
        // Only items with EP >= minProbability are considered "valid" for mining
        storePhase1Results(context, phase1Result);

        // Step 1c: Rank valid items by PTWU ascending for canonical pattern growth order
        // This ordering enables both pruning (low PTWU items first) and duplicate prevention
        ItemRanking ranking = ItemRanking.fromPTWUArray(
            phase1Result.ptwu, context.getValidItems(), phase1Result.maxItemId);
        context.setItemRanking(ranking);

        // Step 1d: Build UPU-Lists for all valid single items (Phase 1d-a + 1d-b)
        // These lists store transaction entries sorted by item ranking for efficient joins
        Map<Integer, UtilityProbabilityList> singleItemLists = buildUPULists(context);
        context.setSingleItemLists(singleItemLists);

        if (config.isDebugMode()) {
            System.err.printf("  Valid items after EP filter: %d%n", context.getValidItems().size());
            System.err.printf("  UPU-Lists created: %d%n", singleItemLists.size());
        }
    }

    /**
     * Computes PTWU and EP using configured parallel/sequential mode.
     *
     * @param context the mining context
     * @return PTWU computation result with PTWU, log-complement, and max item ID
     */
    private PTWUCalculator.PTWUComputationResult computePTWUAndEP(MiningContext context) {
        if (config.useParallelPhase1a()) {
            return ptwuCalculator.computePhase1_Parallel(context.getDatabase(), executorPool);
        } else {
            return ptwuCalculator.computePhase1_Sequential(context.getDatabase());
        }
    }

    /**
     * Stores Phase 1 results in context and filters valid items.
     *
     * @param context       the mining context
     * @param phase1Result  PTWU computation result from Phase 1
     */
    private void storePhase1Results(MiningContext context, PTWUCalculator.PTWUComputationResult phase1Result) {
        // Store PTWU array (used for pruning during pattern growth)
        context.setItemPTWU(phase1Result.ptwu);

        // Store log-complement array (used for on-demand EP computation)
        context.setItemLogComp(phase1Result.logComp);

        // Store max item ID (determines array sizes throughout mining)
        context.setMaxItemId(phase1Result.maxItemId);

        // Filter items by minimum EP threshold
        // Items with EP < minProbability are unreliable (unlikely to appear in any transaction)
        // and are excluded from all subsequent mining phases
        Set<Integer> validItems = new HashSet<>();
        for (int item : context.getProfitTable().getAllItems()) {
            // Use EPSILON for floating-point comparison tolerance
            if (phase1Result.getEP(item) >= config.getMinProbability() - EPSILON) {
                validItems.add(item);
            }
        }
        context.setValidItems(validItems);
    }

    /**
     * Builds UPU-Lists for all valid single items.
     *
     * @param context the mining context
     * @return map of item ID to UPU-List
     */
    private Map<Integer, UtilityProbabilityList> buildUPULists(MiningContext context) {
        UPUListBuilder builder = new UPUListBuilder(
            context, config.getMinProbability(), executorPool);
        return builder.buildSingleItemLists();
    }

    // =========================================================================
    // Phase 2: Initialization
    // =========================================================================

    /**
     * Phase 2: Evaluates 1-itemsets and captures initial threshold.
     *
     * @param context the mining context
     */
    private void executePhase2(MiningContext context) {
        if (config.isDebugMode()) {
            System.err.println("[Phase 2: INITIALIZATION] Evaluating 1-itemsets and " +
                "capturing initial threshold...");
        }

        // Step 2a: Evaluate and collect 1-itemsets
        TopKCollectorInterface collector = context.getCollector();
        for (int item : context.getItemRanking().getSortedItems()) {
            UtilityProbabilityList itemList = context.getSingleItemLists().get(item);
            if (itemList == null) continue;

            double eu = itemList.expectedUtility;
            double ep = itemList.existentialProbability;

            if (ep >= config.getMinProbability() - EPSILON &&
                eu >= collector.getAdmissionThreshold() - EPSILON) {
                collector.tryCollect(itemList);
            }
        }

        // Step 2b: Capture initial threshold for mining phase
        context.getThresholdCoordinator().captureInitialThreshold();

        if (config.isDebugMode()) {
            System.err.printf("  Initial threshold captured: %.4f%n",
                context.getThresholdCoordinator().getInitialThreshold());
        }
    }

    // =========================================================================
    // Phase 3: Mining
    // =========================================================================

    /**
     * Phase 3: Executes prefix-growth mining with configured search strategy.
     *
     * @param context the mining context
     */
    private void executePhase3(MiningContext context) {
        if (config.isDebugMode()) {
            String mode = config.useParallelMining() ? "parallel" : "sequential";
            String strategy = config.getSearchStrategy().toString();
            System.err.printf("[Phase 3: MINING] Starting %s prefix-growth mining (strategy: %s)...%n",
                mode, strategy);
        }

        if (config.useParallelMining()) {
            executeMiningParallel(context);
        } else {
            executeMiningSequential(context);
        }
    }

    /**
     * Phase 3 (Parallel): ForkJoin work-stealing across all prefixes.
     *
     * @param context the mining context
     */
    private void executeMiningParallel(MiningContext context) {
        // Create search engine with configured strategy (DFS, BestFirst, BFS, IDDFS)
        // and join strategy (TwoPointer, ExponentialSearch, BinarySearch)
        SearchEngine engine = createEngine(context);

        // Compute global cutoff: first index where PTWU >= initialThreshold
        // All items with rank < globalCutoff have PTWU below threshold and can be safely pruned
        // This optimization skips low-utility items entirely (no work stealing needed for them)
        int globalCutoff = context.getItemRanking().findFirstIndexAboveThreshold(
            context.getThresholdCoordinator().getInitialThreshold());

        // Create root ForkJoin task that will recursively split the prefix range
        // Task splits until reaching FINE_GRAIN_THRESHOLD prefixes (16 by default)
        // Each worker thread steals tasks from the queue using work-stealing algorithm
        PrefixMiningTask rootTask = new PrefixMiningTask(
            engine,                                         // Search strategy to use
            context.getItemRanking(),                       // Item ranking (PTWU ascending)
            context.getSingleItemLists(),                   // Single-item UPU-Lists
            context.getThresholdCoordinator(),              // Thread-safe threshold coordinator
            0,                                              // Start index (first prefix)
            context.getItemRanking().size(),                // End index (last prefix + 1)
            globalCutoff,                                   // Skip prefixes below this index
            FINE_GRAIN_THRESHOLD                            // Stop splitting at 16 prefixes
        );

        // Submit root task and wait for completion (blocking call)
        executorPool.invoke(rootTask);
    }

    /**
     * Phase 3 (Sequential): Sequential loop over all prefixes.
     *
     * @param context the mining context
     */
    private void executeMiningSequential(MiningContext context) {
        // Create search engine with configured strategy and joiner
        SearchEngine engine = createEngine(context);

        // Compute global cutoff: first index where PTWU >= initialThreshold
        // Items ranked below this cutoff can never form high-utility patterns
        int globalCutoff = context.getItemRanking().findFirstIndexAboveThreshold(
            context.getThresholdCoordinator().getInitialThreshold());

        // Cache frequently accessed context data for performance
        List<Integer> sortedItems = context.getItemRanking().getSortedItems();
        Map<Integer, UtilityProbabilityList> singleItemLists = context.getSingleItemLists();
        double initialThreshold = context.getThresholdCoordinator().getInitialThreshold();

        // Process each prefix sequentially in PTWU-ascending order
        // Starting from globalCutoff skips low-utility items entirely
        for (int i = globalCutoff; i < sortedItems.size(); i++) {
            int prefixItem = sortedItems.get(i);
            UtilityProbabilityList prefixList = singleItemLists.get(prefixItem);

            // Skip if UPU-List wasn't created (item was filtered in Phase 1d)
            if (prefixList == null || prefixList.entryCount == 0) continue;

            // PTWU pruning: if prefix PTWU < threshold, all supersets also have PTWU < threshold
            // This is the monotone upper bound property of PTWU
            if (prefixList.ptwu < initialThreshold - EPSILON) continue;

            // Evaluate this 1-itemset as a potential high-utility pattern
            // Check both EP threshold (reliability) and EU threshold (profitability)
            if (prefixList.existentialProbability >= config.getMinProbability() - EPSILON) {
                context.getCollector().tryCollect(prefixList);
            }

            // Recursively explore extensions of this prefix
            // Only consider items at positions > i to maintain canonical order and avoid duplicates
            engine.exploreExtensions(prefixList, i + 1);
        }
    }

    /**
     * Creates the search engine with configured strategy and joiner.
     *
     * @param context the mining context
     * @return the configured search engine
     */
    private SearchEngine createEngine(MiningContext context) {
        UPUListJoinerInterface joiner = SearchEngineFactory.createJoiner(config.getJoinStrategy());

        return SearchEngineFactory.createSearchEngine(
            config,
            joiner,
            context.getCollector(),
            context.getItemRanking(),
            context.getSingleItemLists(),
            context.getThresholdCoordinator().getInitialThreshold()
        );
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    /**
     * Logs phase completion time if debug mode is enabled.
     *
     * @param phaseNumber the phase number (1, 2, or 3)
     * @param startTime   phase start time in milliseconds
     */
    private void logPhaseCompletion(int phaseNumber, long startTime) {
        if (config.isDebugMode()) {
            long duration = System.currentTimeMillis() - startTime;
            System.err.printf("[Phase %d] Time: %d ms%n", phaseNumber, duration);
        }
    }
}
