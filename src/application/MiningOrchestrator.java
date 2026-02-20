package application;

import domain.collection.TopKCollectorInterface;
import domain.engine.SearchEngine;
import domain.engine.UPUListJoinerInterface;
import domain.model.*;
import infrastructure.builder.UPUListBuilder;
import infrastructure.computation.PTWUCalculator;
import infrastructure.parallel.PrefixMiningTask;
import infrastructure.parallel.ThresholdCoordinator;

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
 * @see MiningConfiguration
 * @see MiningContext
 * @see SearchEngineFactory
 */
public final class MiningOrchestrator {

    private final MiningConfiguration config; // Global config
    private final PTWUCalculator ptwuCalculator; // Compute both ptwu + ep
    private final ForkJoinPool executorPool; // Parallel

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
            phase1Result.ptwu,
            context.getValidItems(),
            phase1Result.itemIdToDenseIndex,
            phase1Result.denseIndexToItemId,
            phase1Result.maxItemId);
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
        // Store dense PTWU array (used for pruning during pattern growth)
        context.setItemPTWU(phase1Result.ptwu);

        // Store dense log-complement array (used for on-demand EP computation)
        context.setItemLogComp(phase1Result.logComp);

        // Store max item ID (determines array sizes throughout mining)
        context.setMaxItemId(phase1Result.maxItemId);

        // Store dense index mapping (NEW)
        context.setItemIdToDenseIndex(phase1Result.itemIdToDenseIndex);
        context.setDenseIndexToItemId(phase1Result.denseIndexToItemId);
        context.setDenseSize(phase1Result.denseSize);

        // Filter items by minimum EP threshold
        // Items with EP < minProbability are unreliable 
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
        // Set the topK
        TopKCollectorInterface collector = context.getCollector();

        // Process with single-item in PTWU-ascending order
        for (int item : context.getItemRanking().getSortedItems()) {

            // Get the UPUList of single item (Phase 1 output)
            UtilityProbabilityList itemList = context.getSingleItemLists().get(item);

            // Double check
            if (itemList == null) continue;

            // Access pre-computed (it is already computed in Phase 1)
            double eu = itemList.expectedUtility;
            double ep = itemList.existentialProbability;

            // Try to put in topK
            if (ep >= config.getMinProbability() - EPSILON &&
                eu >= collector.getAdmissionThreshold() - EPSILON) {
                collector.tryCollect(itemList);
            }
        }

        if (config.isDebugMode()) {
            System.err.printf("  Initial admission threshold: %.4f%n",
                collector.getAdmissionThreshold());
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

        // Create root ForkJoin task that will recursively split the prefix range
        // Task splits until reaching FINE_GRAIN_THRESHOLD prefixes (16 by default)
        // Each worker thread steals tasks from the queue using work-stealing algorithm
        // Dynamic threshold pruning occurs at each prefix check - more aggressive as mining progresses
        PrefixMiningTask rootTask = new PrefixMiningTask(
            engine,                                         // Search strategy to use
            context.getItemRanking(),                       // Item ranking (PTWU ascending)
            context.getSingleItemLists(),                   // Single-item UPU-Lists
            context.getThresholdCoordinator(),              // Threshold coordinator (dynamic)
            0,                                              // Start index (first prefix)
            context.getItemRanking().size(),                // End index (last prefix + 1)
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

        // Cache frequently accessed context data for performance
        List<Integer> sortedItems = context.getItemRanking().getSortedItems();
        Map<Integer, UtilityProbabilityList> singleItemLists = context.getSingleItemLists();
        ThresholdCoordinator thresholdCoordinator = context.getThresholdCoordinator();

        // Process each prefix sequentially in PTWU-ascending order
        for (int i = 0; i < sortedItems.size(); i++) {
            int prefixItem = sortedItems.get(i);

            // Access pre-computed UPU-List for the current item
            UtilityProbabilityList prefixList = singleItemLists.get(prefixItem);

            // Skip if UPU-List wasn't created (item was filtered in Phase 1d)
            if (prefixList == null || prefixList.entryCount == 0) continue;

            // PTWU pruning: if prefix PTWU < current dynamic threshold, all supersets also fail
            // This is the monotone upper bound property of PTWU
            // Threshold increases as better patterns are discovered, enabling more aggressive pruning
            if (thresholdCoordinator.shouldPrunePrefix(prefixList.ptwu)) continue;

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
            context.getSingleItemLists()
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
