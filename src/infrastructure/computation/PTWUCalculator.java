package infrastructure.computation;

import domain.model.ProfitTable;
import domain.model.Transaction;

import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;

import static infrastructure.util.NumericalConstants.LOG_ZERO;

/**
 * Computes PTWU (Positive Transaction-Weighted Utility) and EP (Existential Probability)
 * for all items in Phase 1 of the PTK-HUIM algorithm.
 *
 * <h3>What is PTWU?</h3>
 * <p><b>PTWU (Positive Transaction-Weighted Utility)</b> is a critical upper bound used
 * for pruning low-utility itemsets during pattern growth. It represents the sum of transaction
 * utilities across all transactions where an item <i>might</i> appear.
 *
 * <p><b>Formal definition:</b>
 * <pre>
 *   PTWU(item) = Σ PTU(T)   for all T where item ∈ T
 *
 *   where PTU(T) = Σ profit(i) × quantity(i, T)   for all i ∈ T with profit(i) > 0
 * </pre>
 *
 * <h3>Why PTWU is Essential</h3>
 * <p>PTWU serves as a <b>monotone upper bound</b> for Expected Utility (EU):
 * <ul>
 *   <li>If PTWU(item) < threshold, then EU(item) < threshold</li>
 *   <li>If PTWU(itemset X) < threshold, then EU(any superset of X) < threshold</li>
 *   <li>This allows early pruning without computing expensive EU calculations</li>
 * </ul>
 *
 * <h3>Example</h3>
 * <p>Consider a database with two transactions:
 * <pre>
 *   Transaction T₁: {A: qty=2, prob=0.9}, {B: qty=1, prob=0.8}
 *   Transaction T₂: {A: qty=3, prob=0.7}, {C: qty=2, prob=0.6}
 *
 *   Profits: profit(A)=10, profit(B)=15, profit(C)=5
 *
 *   Step 1 - Compute PTU for each transaction:
 *     PTU(T₁) = profit(A)×qty(A,T₁) + profit(B)×qty(B,T₁)
 *             = 10×2 + 15×1 = 35
 *
 *     PTU(T₂) = profit(A)×qty(A,T₂) + profit(C)×qty(C,T₂)
 *             = 10×3 + 5×2 = 40
 *
 *   Step 2 - Sum PTU for transactions containing each item:
 *     PTWU(A) = PTU(T₁) + PTU(T₂) = 35 + 40 = 75  (A appears in both)
 *     PTWU(B) = PTU(T₁)           = 35            (B appears in T₁ only)
 *     PTWU(C) = PTU(T₂)           = 40            (C appears in T₂ only)
 *
 *   Usage in pruning:
 *     If mining threshold = 50, we can immediately prune:
 *     - Item B (PTWU=35 < 50) and all supersets {A,B}, {B,C}, etc.
 *     - Item C (PTWU=40 < 50) and all supersets
 *     Only Item A (PTWU=75 ≥ 50) needs further exploration
 * </pre>
 *
 * <h3>What is EP?</h3>
 * <p><b>EP (Existential Probability)</b> measures the likelihood that an item appears
 * in at least one transaction in the entire database:
 * <pre>
 *   EP(item) = 1 - ∏(1 - P(item, T))   for all T where item appears
 *
 *   Intuition: The probability that item does NOT appear in any transaction
 *              is ∏(1 - P(item, T)). EP is the complement of this.
 * </pre>
 *
 * <p>EP is computed simultaneously with PTWU by accumulating log-complements:
 * <pre>
 *   logComp(item) = Σ log(1 - P(item, T))
 *   EP(item) = 1 - exp(logComp(item))
 * </pre>
 *
 * <h3>Two-Pass Computation Strategy</h3>
 * <p>For each transaction, we use a two-pass approach:
 * <ol>
 *   <li><b>First pass</b> — Compute PTU(T) and accumulate EP log-complements</li>
 *   <li><b>Second pass</b> — Distribute PTU(T) to PTWU of all items in T</li>
 * </ol>
 * <p>The two passes must be separate because PTU(T) must be fully computed before
 * distributing it to item PTWU values.
 *
 * <h3>Usage</h3>
 * <pre>
 *   PTWUCalculator calculator = new PTWUCalculator(profitTable);
 *
 *   // Sequential computation
 *   Phase1Result result = calculator.computePhase1_Sequential(database);
 *   double ptwu_A = result.getPTWU(itemA);
 *   double ep_A = result.getEP(itemA);
 *
 *   // Parallel computation (2.5-3× faster)
 *   ForkJoinPool executor = new ForkJoinPool();
 *   Phase1Result result = calculator.computePhase1_Parallel(database, executor);
 * </pre>
 *
 * <h3>Thread Safety</h3>
 * <p>The calculator itself is thread-safe after construction (profit cache is immutable).
 * The parallel computation uses thread-local accumulators with no shared mutable state
 * during processing; only the final merge operates on shared data (sequential phase).
 *
 * @see ItemRanking
 * @see Transaction
 * @see ProfitTable
 */
public class PTWUCalculator {

    private final ProfitTable profitTable;
    private final int maxItemId;

    // Dense index mapping for sparse item IDs
    private final int denseSize;                    // Number of actual items
    private final int[] itemIdToDenseIndex;         // sparse[maxItemId+1] -> dense index or -1
    private final int[] denseIndexToItemId;         // dense[denseSize] -> item ID

    private final double[] profitCache;             // NOW: dense[denseSize]

    public PTWUCalculator(ProfitTable profitTable) {
        this.profitTable = profitTable;

        // Step 1: Compute maxItemId (unchanged for backward compatibility)
        int max = 0;
        for (int id: profitTable.getAllItems()) {
            if (id > max) {
                max = id;
            }
        }
        this.maxItemId = max;

        // Step 2: Build dense index mapping
        Set<Integer> allItems = profitTable.getAllItems();
        this.denseSize = allItems.size();
        this.itemIdToDenseIndex = new int[maxItemId + 1];
        Arrays.fill(itemIdToDenseIndex, -1);  // -1 = not in profit table
        this.denseIndexToItemId = new int[denseSize];

        int denseIdx = 0;
        for (int itemId : allItems) {
            itemIdToDenseIndex[itemId] = denseIdx;
            denseIndexToItemId[denseIdx] = itemId;
            denseIdx++;
        }

        // Step 3: Build dense profit cache
        this.profitCache = new double[denseSize];
        for (int denseIndex = 0; denseIndex < denseSize; denseIndex++) {
            int itemId = denseIndexToItemId[denseIndex];
            profitCache[denseIndex] = profitTable.getProfit(itemId);
        }
    }

    /**
     * Returns the maximum item ID in the profit table.
     *
     * @return maximum item ID
     */
    public int getMaxItemId() {
        return maxItemId;
    }

    /**
     * Returns the number of items in the profit table (dense size).
     *
     * @return number of actual items
     */
    public int getDenseSize() {
        return denseSize;
    }

    /**
     * Result container for Phase 1 PTWU and EP computation.
     *
     * <p>Encapsulates the three key outputs from Phase 1 preprocessing:
     * <ul>
     *   <li>PTWU array — Positive Transaction-Weighted Utility per item</li>
     *   <li>Log-complement array — For on-demand EP (Existential Probability) calculation</li>
     *   <li>Maximum item ID — Determines array sizes throughout mining</li>
     * </ul>
     *
     * <p>This immutable value object replaces the generic "Phase1Result" name with
     * a descriptive class name that clearly communicates its purpose.
     */
    public static final class PTWUComputationResult {
        /**
         * PTWU per item: dense array indexed by dense index (size = denseSize)
         */
        public final double[] ptwu;

        /**
         * Log-complement for EP computation: dense array indexed by dense index (size = denseSize)
         */
        public final double[] logComp;

        /**
         * Maximum item ID in the dataset (kept for backward compatibility)
         */
        public final int maxItemId;

        /**
         * Sparse-to-dense mapping: sparse item ID -> dense index or -1
         */
        public final int[] itemIdToDenseIndex;

        /**
         * Dense-to-sparse mapping: dense index -> sparse item ID
         */
        public final int[] denseIndexToItemId;

        /**
         * Number of actual items (dense array size)
         */
        public final int denseSize;

        PTWUComputationResult(double[] ptwu, double[] logComp, int maxItemId,
                              int[] itemIdToDenseIndex, int[] denseIndexToItemId, int denseSize) {
            this.ptwu = ptwu;
            this.logComp = logComp;
            this.maxItemId = maxItemId;
            this.itemIdToDenseIndex = itemIdToDenseIndex;
            this.denseIndexToItemId = denseIndexToItemId;
            this.denseSize = denseSize;
        }

        /**
         * Computes EP for an item on demand from log-complement.
         *
         * @param itemId item ID
         * @return EP(item) ∈ [0, 1]
         */
        public double getEP(int itemId) {
            if (itemId < 0 || itemId > maxItemId) return 0.0;
            // CHANGE: Translate item ID to dense index before array access
            int denseIdx = itemIdToDenseIndex[itemId];
            if (denseIdx < 0) return 0.0;  // item not in profit table

            double lc = logComp[denseIdx];  // CHANGE: use denseIdx
            return (lc <= LOG_ZERO) ? 1.0 : 1.0 - Math.exp(lc);
        }

        /**
         * Gets PTWU for an item.
         *
         * @param itemId item ID
         * @return PTWU(item)
         */
        public double getPTWU(int itemId) {
            if (itemId < 0 || itemId > maxItemId) return 0.0;
            // CHANGE: Translate item ID to dense index before array access
            int denseIdx = itemIdToDenseIndex[itemId];
            return (denseIdx >= 0) ? ptwu[denseIdx] : 0.0;  // CHANGE: use denseIdx
        }
    }

    /**
     * Thread-local accumulator for parallel Phase-1 tasks.
     *
     * <p>Uses primitive {@code double[]} arrays indexed by item ID for
     * cache-friendly, boxing-free accumulation; merged via simple array
     * addition after all tasks complete.
     */
    private static final class Phase1Accumulator {
        final double[] ptwu;
        final double[] logComp;

        Phase1Accumulator(int size) {
            this.ptwu = new double[size];
            this.logComp = new double[size];
        }
    }

    /**
     * Computes both PTWU and EP in a single sequential database scan.
     *
     * <p>Mirrors the logic of without threading
     * overhead.  Used when {@code ParallelizationScope} is {@code SEQUENTIAL}
     * or {@code PARALLEL_MINING}.
     *
     * @param database list of all transactions
     * @return combined PTWU and EP computation result for all items
     */
    public PTWUComputationResult computePhase1_Sequential(List<Transaction> database) {
        // CHANGE: Use dense size instead of sparse maxItemId+1
        int arraySize = denseSize;
        double[] ptwu = new double[arraySize];
        double[] logComp = new double[arraySize];

        for (Transaction trans : database) {
            Set<Integer> items = trans.getItems();
            double ptu = 0.0;

            // First pass: compute PTU and accumulate log-complement
            for (int itemId : items) {  // renamed: item -> itemId for clarity
                if (itemId < 0 || itemId > maxItemId) continue;

                // CHANGE: Translate item ID to dense index
                int denseIdx = itemIdToDenseIndex[itemId];
                if (denseIdx < 0) continue;  // item not in profit table

                // CHANGE: Use denseIdx for array access
                if (profitCache[denseIdx] > 0) {
                    ptu += profitCache[denseIdx] * trans.getQuantity(itemId); // PTU formula, DEFINITION 7
                }

                // CHANGE: Use denseIdx for logComp accumulation
                logComp[denseIdx] += ProbabilityModel.logComplement(trans.getProbability(itemId)); // DEFINITION 3b
            }

            // Second pass: distribute PTU to all valid items
            // Note: Must be separate loop since we need complete PTU value first
            for (int itemId : items) {
                if (itemId < 0 || itemId > maxItemId) continue;

                // CHANGE: Translate and check before array access
                int denseIdx = itemIdToDenseIndex[itemId];
                if (denseIdx >= 0) {
                    ptwu[denseIdx] += ptu; // PTWU, DEFINITION 7
                }
            }
        }

        // CHANGE: Pass dense arrays and mapping to result
        return new PTWUComputationResult(ptwu, logComp, maxItemId,
                                          itemIdToDenseIndex, denseIndexToItemId, denseSize);
    }

    /**
     * Computes both PTWU and EP using ForkJoin parallelism.
     *
     * <p>Splits the database into chunks (LEAF_SIZE=256 transactions) and processes
     * them in parallel using work-stealing. Each thread accumulates into thread-local
     * arrays, then merges results via in-place addition (zero allocations).
     *
     * @param database list of all transactions
     * @param executor ForkJoinPool for parallel execution
     * @return combined PTWU and EP computation result for all items
     */
    public PTWUComputationResult computePhase1_Parallel(List<Transaction> database, ForkJoinPool executor) {
        // CHANGE: Use dense size instead of sparse maxItemId+1
        int arraySize = denseSize;
        Phase1ScanTask rootTask = new Phase1ScanTask(database, 0, database.size(), arraySize);
        Phase1Accumulator result = executor.invoke(rootTask);
        // CHANGE: Pass mapping to result
        return new PTWUComputationResult(result.ptwu, result.logComp, maxItemId,
                                          itemIdToDenseIndex, denseIndexToItemId, denseSize);
    }

    /**
     * ForkJoin task for parallel Phase 1 PTWU/EP computation.
     *
     * <p>Recursively splits transaction range until reaching LEAF_SIZE, then
     * processes transactions sequentially into thread-local accumulator.
     */
    private final class Phase1ScanTask extends RecursiveTask<Phase1Accumulator> {
        /**
         * Stop splitting when range ≤ 256 transactions.
         */
        private static final int LEAF_SIZE = 256;

        private final List<Transaction> database;
        private final int from;
        private final int to;
        private final int arraySize;

        Phase1ScanTask(List<Transaction> database, int from, int to, int arraySize) {
            this.database = database;
            this.from = from;
            this.to = to;
            this.arraySize = arraySize;
        }

        @Override
        protected Phase1Accumulator compute() {
            int size = to - from;

            // Base case: compute leaf sequentially
            if (size <= LEAF_SIZE) {
                return computeLeaf();
            }

            // Recursive case: split work
            int mid = from + size / 2;
            Phase1ScanTask left = new Phase1ScanTask(database, from, mid, arraySize);
            Phase1ScanTask right = new Phase1ScanTask(database, mid, to, arraySize);

            // Fork-last pattern: 50% fewer task objects
            left.fork();
            Phase1Accumulator rightResult = right.compute();  // Execute on current thread
            Phase1Accumulator leftResult = left.join();       // Wait for forked task

            // In-place merge: zero allocations
            for (int i = 0; i < arraySize; i++) {
                leftResult.ptwu[i] += rightResult.ptwu[i];
                leftResult.logComp[i] += rightResult.logComp[i];
            }

            return leftResult;
        }

        /**
         * Computes PTWU and EP for transactions[from..to) into fresh accumulator.
         *
         * <p>Identical logic to sequential version, but accumulates into
         * thread-local arrays (no contention).
         */
        private Phase1Accumulator computeLeaf() {
            Phase1Accumulator local = new Phase1Accumulator(arraySize);

            for (int idx = from; idx < to; idx++) {
                Transaction trans = database.get(idx);
                Set<Integer> items = trans.getItems();
                double ptu = 0.0;

                // First pass: compute PTU and accumulate log-complement
                for (int itemId : items) {  // renamed: item -> itemId for clarity
                    if (itemId < 0 || itemId > maxItemId) continue;

                    // CHANGE: Translate item ID to dense index
                    int denseIdx = itemIdToDenseIndex[itemId];
                    if (denseIdx < 0) continue;  // item not in profit table

                    // CHANGE: Use denseIdx for array access
                    if (profitCache[denseIdx] > 0) {
                        ptu += profitCache[denseIdx] * trans.getQuantity(itemId);
                    }

                    // CHANGE: Use denseIdx for logComp accumulation
                    local.logComp[denseIdx] += ProbabilityModel.logComplement(trans.getProbability(itemId));
                }

                // Second pass: distribute PTU to all valid items -> PTWU
                for (int itemId : items) {
                    if (itemId < 0 || itemId > maxItemId) continue;

                    // CHANGE: Translate and check before array access
                    int denseIdx = itemIdToDenseIndex[itemId];
                    if (denseIdx >= 0) {
                        local.ptwu[denseIdx] += ptu;
                    }
                }
            }

            return local;
        }
    }
}
