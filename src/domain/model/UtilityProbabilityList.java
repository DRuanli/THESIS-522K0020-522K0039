package domain.model;

import java.util.List;
import java.util.Set;

import static infrastructure.util.NumericalConstants.LOG_ONE_MINUS_EPSILON;
import static infrastructure.util.NumericalConstants.LOG_ZERO;

/**
 * Central data structure: transactional projection of one itemset onto the database.
 *
 * <p>A <em>UPU-List</em> (Utility-Probability-Utility List) for itemset {@code X}
 * stores, for each transaction {@code T} in which {@code X} appears:
 * <ul>
 *   <li>{@code transactionId} — the unique TID of {@code T}.</li>
 *   <li>{@code utility} — {@code u(X, T) = Σ_{i ∈ X} profit(i) × q(i, T)}; may be negative.</li>
 *   <li>{@code remainingUtility} — {@code ru(X, T) = Σ_{j: rank(j) > max_rank(X), profit(j) > 0} profit(j) × q(j, T)},
 *       the positive suffix sum over higher-ranked items. Used in the Positive Upper Bound (PUB).</li>
 *   <li>{@code logProbability} — {@code log P(X, T) = Σ_{i ∈ X} log P(i, T)};
 *       stored in log-space to enable numerically stable joins (addition instead of multiplication).</li>
 * </ul>
 *
 * <p>The following pre-aggregated statistics are computed in a single linear pass at
 * construction time and cached for O(1) access during pruning:
 * <ul>
 *   <li>{@code ptwu} — Positive Transaction-Weighted Utility: an upper bound on EU.
 *       For a single item, computed by {@link oop.infrastructure.computation.PTWUCalculator};
 *       for a joined itemset, {@code PTWU(X ∪ Y) = min(PTWU(X), PTWU(Y))}.</li>
 *   <li>{@code expectedUtility} — {@code EU(X) = Σ_T P(X, T) × u(X, T)}.</li>
 *   <li>{@code existentialProbability} — {@code EP(X) = 1 − Π_T (1 − P(X, T))},
 *       computed in log-space as {@code 1 − exp(Σ_T log(1 − P(X, T)))}.</li>
 *   <li>{@code positiveUpperBound} — {@code PUB(X) = Σ_T P(X, T) × (u(X, T) + ru(X, T))};
 *       the tightest transaction-based upper bound on EU of any superset of X.</li>
 * </ul>
 *
 * <p><b>Structural invariants:</b>
 * <ol>
 *   <li>{@code transactionIds[]} is sorted ascending — required for O(n) two-pointer join
 *       in {@link oop.domain.engine.UPUListJoiner}.</li>
 *   <li>Valid data occupies indices {@code [0, entryCount)}; arrays may be pre-allocated
 *       with spare capacity (join uses {@code Math.min(list1.entryCount, list2.entryCount)}).</li>
 *   <li><b>Immutable after construction</b> — safe for concurrent reads across ForkJoin threads
 *       during Phase 5 parallel mining.</li>
 * </ol>
 *
 * @see oop.domain.engine.UPUListJoiner
 * @see oop.infrastructure.computation.PTWUCalculator
 * @see oop.infrastructure.computation.SuffixSumCalculator
 */
public final class UtilityProbabilityList {

    // --- Per-transaction arrays (indexed 0..entryCount-1) ---

    /** Transaction IDs, sorted ascending for two-pointer join. */
    public final int[]    transactionIds;

    /** u(X, T) for each transaction. May be negative. */
    public final double[] utilities;

    /** ru(X, T) — positive suffix sum over higher-ranked items. */
    public final double[] remainingUtilities;

    /** log P(X, T) — sum of per-item log-probabilities; clamped at LOG_ZERO. */
    public final double[] logProbabilities;

    /** Number of valid entries in the arrays. */
    public final int      entryCount;

    // --- Pre-aggregated statistics ---

    /** PTWU upper bound. For joined lists: min(PTWU(prefix), PTWU(extension)). */
    public final double ptwu;

    /** EU(X): probability-weighted utility sum across all transactions. */
    public final double expectedUtility;

    /** EP(X): probability of X existing in at least one transaction. */
    public final double existentialProbability;

    /** PUB(X): tighter upper bound using actual transaction intersection. */
    public final double positiveUpperBound;

    // --- Itemset identity ---

    /** The itemset this UPU-List represents. */
    public final Set<Integer> itemset;

    /**
     * Direct-array constructor — used by {@link oop.domain.engine.UPUListJoiner}
     * for joined itemsets where all arrays and aggregates are pre-computed.
     *
     * <p>Arrays are trusted to be filled up to {@code entryCount}; no validation
     * or re-computation is performed.
     *
     * @param itemset                 the itemset this list represents
     * @param transactionIds          TID array (length ≥ entryCount), sorted ascending
     * @param utilities               u(X, T) array
     * @param remainingUtilities      ru(X, T) array
     * @param logProbabilities        log P(X, T) array
     * @param entryCount              number of valid entries
     * @param ptwu                    pre-computed PTWU upper bound
     * @param expectedUtility         pre-computed EU(X)
     * @param existentialProbability  pre-computed EP(X)
     * @param positiveUpperBound      pre-computed PUB(X)
     */
    public UtilityProbabilityList(Set<Integer> itemset,
                                  int[] transactionIds,
                                  double[] utilities,
                                  double[] remainingUtilities,
                                  double[] logProbabilities,
                                  int entryCount,
                                  double ptwu,
                                  double expectedUtility,
                                  double existentialProbability,
                                  double positiveUpperBound) {
        this.itemset              = itemset;
        this.transactionIds       = transactionIds;
        this.utilities            = utilities;
        this.remainingUtilities   = remainingUtilities;
        this.logProbabilities     = logProbabilities;
        this.entryCount           = entryCount;
        this.ptwu                 = ptwu;
        this.expectedUtility      = expectedUtility;
        this.existentialProbability = existentialProbability;
        this.positiveUpperBound   = positiveUpperBound;
    }

    /**
     * Returns the existential probability EP(X).
     *
     * <p>Provided as a getter for pruning strategy code that uses method-call style
     * (e.g., {@link oop.domain.pruning.ExistentialProbabilityPruner}).
     *
     * @return EP(X) ∈ [0, 1]
     */
    public double getExistentialProbability() {
        return existentialProbability;
    }

    /**
     * Returns the positive upper bound PUB(X).
     *
     * <p>Provided as a getter for pruning strategy code that uses method-call style
     * (e.g., {@link oop.domain.pruning.PositiveUpperBoundPruner}).
     *
     * @return PUB(X) ≥ EU(X) for all supersets of X
     */
    public double getPositiveUpperBound() {
        return positiveUpperBound;
    }

    // -------------------------------------------------------------------------
    // Nested: TransactionEntry (per-transaction record, used during Phase 1d construction)
    // -------------------------------------------------------------------------

    /**
     * Per-transaction contribution of an item to its UPU-List.
     *
     * <p>Each {@code TransactionEntry} represents one occurrence of an item in a specific
     * transaction, storing the utility, remaining utility (for PUB calculation), and
     * log-probability of that item within that transaction.
     *
     * <p>Instances are created during Phase 1d (UPU-List building), sorted by transaction ID,
     * and consumed by {@link Builder} to construct complete {@link UtilityProbabilityList} objects.
     * They are temporary intermediate structures never stored beyond the construction phase.
     *
     * <p><b>Naming rationale:</b> Renamed from generic "Entry" to descriptive "TransactionEntry"
     * to clarify that each entry represents a single transaction's contribution.
     */
    public static final class TransactionEntry {

        /** Transaction ID (unique identifier for the transaction). */
        public final int    transactionId;

        /** u(item, T) = profit(item) × q(item, T) — utility of item in this transaction. */
        public final double utility;

        /** ru(item, T) — positive suffix sum for higher-ranked items in this transaction. */
        public final double remainingUtility;

        /** log P(item, T) — log-probability of item in this transaction. */
        public final double logProbability;

        /**
         * Constructs a transaction entry for a single item occurrence.
         *
         * @param transactionId   unique transaction identifier
         * @param utility         u(item, T) — profit × quantity
         * @param remainingUtility ru(item, T) — suffix sum for PUB calculation
         * @param logProbability  log P(item, T) — log-space probability
         */
        public TransactionEntry(int transactionId, double utility,
                                double remainingUtility, double logProbability) {
            this.transactionId    = transactionId;
            this.utility          = utility;
            this.remainingUtility = remainingUtility;
            this.logProbability   = logProbability;
        }
    }

    // -------------------------------------------------------------------------
    // Nested: Builder  (constructs from a collected, TID-sorted list of Entry)
    // -------------------------------------------------------------------------

    /**
     * Builds a single-item UPU-List from pre-collected, TID-sorted transaction entries.
     *
     * <p>Computes all four aggregates (EU, EP, PUB, PTWU) in one linear pass
     * using the same log-space EP accumulation as {@link oop.domain.engine.UPUListJoiner},
     * guaranteeing consistency between single-item and joined-itemset statistics.
     *
     * <p><b>EP accumulation:</b>
     * <pre>
     *   logComplement ← 0                             // log(1) initially
     *   for each transaction T:
     *     logComplement += log(1 − P(X, T))           // log-space product
     *   EP = 1 − exp(logComplement)
     * </pre>
     * The floor {@code LOG_ZERO = −700} prevents denormalized numbers.
     * When any P(X, T) ≈ 1 (i.e., logP &gt; LOG_ONE_MINUS_EPSILON),
     * the complement collapses to 0 (i.e., EP = 1) immediately.
     *
     * <p><b>Caller guarantee:</b> entries must be sorted by {@code transactionId}
     * ascending before calling {@link #build()}.
     */
    public static final class Builder {
        private Set<Integer> itemset;
        private List<TransactionEntry>  entries;
        private double       ptwu;

        /**
         * Sets the construction parameters.
         *
         * @param itemset  the singleton itemset (contains the single item ID)
         * @param entries  TID-sorted list of per-transaction entries
         * @param ptwu     PTWU value from Phase 1 computation
         * @return this builder (fluent API)
         */
        public Builder fromEntries(Set<Integer> itemset, List<TransactionEntry> entries, double ptwu) {
            this.itemset  = itemset;
            this.entries  = entries;
            this.ptwu     = ptwu;
            return this;
        }

        /**
         * Builds the {@code UtilityProbabilityList}, computing all aggregates in one pass.
         *
         * @return fully constructed, immutable UPU-List
         */
        public UtilityProbabilityList build() {
            int n = entries.size();
            int[]    tids       = new int[n];
            double[] utils      = new double[n];
            double[] remainings = new double[n];
            double[] logProbs   = new double[n];

            double sumEU         = 0.0;
            double posUB         = 0.0;
            double logComplement = 0.0;  // accumulates log(Π(1 − P(X, T)))

            for (int i = 0; i < n; i++) {
                TransactionEntry e = entries.get(i);
                tids[i]       = e.transactionId;
                utils[i]      = e.utility;
                remainings[i] = e.remainingUtility;
                logProbs[i]   = e.logProbability;

                double prob = Math.exp(e.logProbability);
                sumEU += e.utility * prob;

                double total = e.utility + e.remainingUtility;
                if (total > 0.0) {
                    posUB += prob * total;
                }

                // EP in log-space — mirrors UPUListJoiner exactly
                if (e.logProbability > LOG_ONE_MINUS_EPSILON) {
                    logComplement = LOG_ZERO;
                } else if (logComplement >= LOG_ZERO) {
                    double log1MinusP = (prob < 0.5)
                        ? Math.log1p(-prob)
                        : Math.log(1.0 - prob);
                    logComplement += log1MinusP;
                    if (logComplement < LOG_ZERO) {
                        logComplement = LOG_ZERO;
                    }
                }
            }

            double ep = (logComplement <= LOG_ZERO) ? 1.0 : 1.0 - Math.exp(logComplement);

            return new UtilityProbabilityList(
                itemset, tids, utils, remainings, logProbs, n,
                ptwu, sumEU, ep, posUB
            );
        }
    }

    // -------------------------------------------------------------------------
    // Test Helper Factory
    // -------------------------------------------------------------------------

    /**
     * Creates a simplified UPU-List for testing/benchmarking purposes.
     *
     * <p>This factory method creates a minimal UPU-List with no transaction data,
     * only the itemset and pre-computed EU/EP values. Used by benchmarks and
     * correctness tests that only need to verify collector behavior, not mining logic.
     *
     * <p><b>NOTE:</b> This is NOT suitable for actual mining - it creates empty
     * transaction arrays. Only use for testing Top-K collector implementations.
     *
     * @param itemset the itemset this list represents
     * @param expectedUtility the EU value for this pattern
     * @param existentialProbability the EP value for this pattern
     * @return a minimal UPU-List for testing purposes
     */
    public static UtilityProbabilityList forTesting(
            Set<Integer> itemset,
            double expectedUtility,
            double existentialProbability) {

        // Create empty arrays (no actual transaction data)
        int[] emptyTids = new int[0];
        double[] emptyUtils = new double[0];
        double[] emptyRU = new double[0];
        double[] emptyLogProbs = new double[0];

        return new UtilityProbabilityList(
            itemset,
            emptyTids,
            emptyUtils,
            emptyRU,
            emptyLogProbs,
            0,  // entryCount = 0
            expectedUtility,  // Use EU as PTWU (doesn't matter for collector testing)
            expectedUtility,
            existentialProbability,
            expectedUtility   // Use EU as PUB (doesn't matter for collector testing)
        );
    }
}
