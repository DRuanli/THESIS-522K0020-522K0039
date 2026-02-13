package domain.engine;

import domain.model.UtilityProbabilityList;

import java.util.HashSet;
import java.util.Set;

import static infrastructure.util.NumericalConstants.*;

/**
 * Joins two UPU-Lists using optimal two-pointer merge with inline aggregation.
 *
 * <p>This is the default and optimal join strategy for PTK-HUIM. Given prefix itemset
 * {@code X} with UPU-List {@code L(X)} and extension item {@code j} with UPU-List
 * {@code L({j})}, this class computes {@code L(X ∪ {j})} in a single
 * O(|L(X)| + |L({j})|) two-pointer merge over TID-sorted arrays.
 *
 * <p><b>Merge rule:</b> a transaction {@code T} appears in {@code L(X ∪ {j})} only
 * if it appears in <em>both</em> {@code L(X)} and {@code L({j})} (i.e., {@code X ∪ {j} ⊆ T}).
 * For each such transaction:
 * <pre>
 *   utility(X ∪ {j}, T)          = utility(X, T) + utility({j}, T)
 *   remainingUtility(X ∪ {j}, T) = min(remaining(X, T), remaining({j}, T))
 *   logProbability(X ∪ {j}, T)   = logP(X, T) + logP({j}, T)    [log-space product]
 * </pre>
 *
 * <p><b>PTWU of joined list:</b> {@code PTWU(X ∪ {j}) = min(PTWU(X), PTWU({j}))}
 * because the joined PTWU is the sum of PTU(T) over the transaction intersection,
 * and {@code min} is a valid (conservative) upper bound.
 *
 * <p><b>Aggregate computation:</b> EU, EP, and PUB are computed inline during the
 * merge scan — one pass avoids a second traversal. EP uses the same log-space
 * accumulation as {@link oop.domain.model.UtilityProbabilityList.Builder} for
 * consistency:
 * <pre>
 *   EP(X ∪ {j}) = 1 − exp(Σ_T log(1 − P(X ∪ {j}, T)))
 * </pre>
 *
 * <p><b>Early pruning:</b> if the joined PTWU falls below {@code initialThreshold},
 * the join is aborted immediately and {@code null} is returned.
 *
 * <p><b>Thread safety:</b> instances are stateless and may be shared across threads.
 * Input UPU-Lists are read-only (immutable after construction).
 */
public final class UPUListJoiner implements UPUListJoinerInterface {

    /**
     * Joins two UPU-Lists with inline aggregate computation.
     *
     * @param list1            UPU-List of the prefix itemset {@code X}
     * @param list2            UPU-List of the single-item extension {@code {j}}
     * @param extensionItem    item ID of {@code j}; passed directly to avoid iterating {@code list2.itemset}
     * @param initialThreshold the static Phase 2 threshold; join is pruned if joined PTWU &lt; threshold
     * @return the joined UPU-List {@code L(X ∪ {j})}, or {@code null} if the result is empty
     *         or pruned by the initial threshold
     */
    public UtilityProbabilityList join(UtilityProbabilityList list1,
                                       UtilityProbabilityList list2,
                                       int extensionItem,
                                       double initialThreshold) {
        double joinedPTWU = Math.min(list1.ptwu, list2.ptwu);

        if (joinedPTWU < initialThreshold - EPSILON) {
            return null;
        }

        // Pre-allocate output arrays at the maximum possible intersection size
        int maxCount = Math.min(list1.entryCount, list2.entryCount);
        int[] tids = new int[maxCount];
        double[] utils = new double[maxCount];
        double[] remainings = new double[maxCount];
        double[] logProbs = new double[maxCount];

        // ========================================================================
        // INLINE AGGREGATE ACCUMULATORS
        // ========================================================================
        // Accumulate EU (expected utility), PUB (positive upper bound), and EP
        // (existential probability) during the merge scan to avoid a second pass
        double tempSumEU = 0.0;      // Sum of (utility × probability)
        double tempPosUB = 0.0;       // Sum of (positive_total × probability)
        double logComplement = 0.0;   // Log-space EP accumulator: Σ log(1 - P)

        int[] tids1 = list1.transactionIds;
        int[] tids2 = list2.transactionIds;
        double[] utils1 = list1.utilities;
        double[] utils2 = list2.utilities;
        double[] rem1 = list1.remainingUtilities;
        double[] rem2 = list2.remainingUtilities;
        double[] logProbs1 = list1.logProbabilities;
        double[] logProbs2 = list2.logProbabilities;

        int count = 0;     // Number of matched transactions written to output
        int i = 0, j = 0;  // Two-pointer indices for list1 and list2

        // ========================================================================
        // TWO-POINTER MERGE ALGORITHM
        // ========================================================================
        // Classic two-pointer merge on TID-sorted arrays (like merge-sort merge step)
        //
        // High-level algorithm:
        //   1. Compare tids1[i] with tids2[j]
        //   2. If equal → transaction appears in BOTH lists:
        //      - Compute joined entry (utility, remaining, logProb)
        //      - Accumulate EU, PUB, EP contributions
        //      - Advance both pointers (i++, j++)
        //   3. If tids1[i] < tids2[j] → transaction only in list1:
        //      - Skip this entry (i++)
        //   4. If tids1[i] > tids2[j] → transaction only in list2:
        //      - Skip this entry (j++)
        //   5. Repeat until one list exhausted
        //
        // Complexity: O(|list1| + |list2|) — linear scan, each entry examined once
        //
        // Correctness: Only transactions appearing in BOTH input lists appear in
        // the output (intersection property: X ∪ {j} ⊆ T iff X ⊆ T AND j ∈ T)
        while (i < list1.entryCount && j < list2.entryCount) {
            int tid1 = tids1[i];
            int tid2 = tids2[j];

            if (tid1 == tid2) {
                // Matched transaction: compute joined values
                double utility = utils1[i] + utils2[j];
                double remaining = Math.min(rem1[i], rem2[j]);
                double logProb = logProbs1[i] + logProbs2[j];

                tids[count] = tid1;
                utils[count] = utility;
                remainings[count] = remaining;
                logProbs[count] = logProb;
                count++;

                // Inline EU accumulation
                double prob = Math.exp(logProb);
                tempSumEU += utility * prob;

                // Inline PUB accumulation (only positive total)
                double totalPotential = utility + remaining;
                if (totalPotential > 0) {
                    tempPosUB += prob * totalPotential;
                }

                // Inline EP accumulation in log-space
                if (logProb > LOG_ONE_MINUS_EPSILON) {
                    // P ≈ 1: complement collapses to 0 → EP = 1
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

                i++;
                j++;
            } else if (tid1 < tid2) {
                i++;
            } else {
                j++;
            }
        }

        if (count == 0) {
            return null;
        }

        double ep = (logComplement <= LOG_ZERO) ? 1.0 : 1.0 - Math.exp(logComplement);

        // Build joined itemset with proper capacity to avoid rehashing
        // Final size will be list1.itemset.size() + 1, so capacity = (size + 1) * 4/3 + 1
        int finalSize = list1.itemset.size() + 1;
        int initialCapacity = (finalSize * 4) / 3 + 1;
        Set<Integer> joinedItemset = new HashSet<>(initialCapacity);
        joinedItemset.addAll(list1.itemset);
        joinedItemset.add(extensionItem);

        return new UtilityProbabilityList(joinedItemset, tids, utils, remainings, logProbs,
                                         count, joinedPTWU, tempSumEU, ep, tempPosUB);
    }
}
