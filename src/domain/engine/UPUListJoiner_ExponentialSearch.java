package domain.engine;

import domain.model.UtilityProbabilityList;

import java.util.HashSet;
import java.util.Set;

import static infrastructure.util.NumericalConstants.*;

/**
 * Alternative join implementation using Exponential Search (Galloping).
 *
 * <p>This is a research baseline for comparing against the optimized two-pointer merge.
 * It uses exponential search followed by binary search to find matches.
 *
 * <p><b>Algorithm</b>:
 * For each entry in list1:
 * <ol>
 *   <li>Exponential search: Jump 1, 2, 4, 8, 16... positions until overshoot</li>
 *   <li>Binary search: Search in identified range [lastPos, lastPos + bound]</li>
 *   <li>If match found, compute joined entry and aggregates</li>
 * </ol>
 *
 * <p><b>Thread safety</b>: Stateless, may be shared across threads.
 */
public final class UPUListJoiner_ExponentialSearch implements UPUListJoinerInterface {

    /**
     * Joins two UPU-Lists using exponential search.
     *
     * @param list1            UPU-List of the prefix itemset {@code X}
     * @param list2            UPU-List of the single-item extension {@code {j}}
     * @param extensionItem    item ID of {@code j}
     * @param initialThreshold the static Phase 3 threshold
     * @return the joined UPU-List {@code L(X ∪ {j})}, or {@code null} if pruned
     */
    public UtilityProbabilityList join(UtilityProbabilityList list1,
                                       UtilityProbabilityList list2,
                                       int extensionItem,
                                       double initialThreshold) {
        double joinedPTWU = Math.min(list1.ptwu, list2.ptwu);

        if (joinedPTWU < initialThreshold - EPSILON) {
            return null;
        }

        // Pre-allocate output arrays at maximum possible intersection size
        int maxCount = Math.min(list1.entryCount, list2.entryCount);
        int[] tids = new int[maxCount];
        double[] utils = new double[maxCount];
        double[] remainings = new double[maxCount];
        double[] logProbs = new double[maxCount];

        // Inline aggregate accumulators
        double tempSumEU = 0.0;
        double tempPosUB = 0.0;
        double logComplement = 0.0;

        // Cache array references
        int[] tids1 = list1.transactionIds;
        int[] tids2 = list2.transactionIds;
        double[] utils1 = list1.utilities;
        double[] utils2 = list2.utilities;
        double[] rem1 = list1.remainingUtilities;
        double[] rem2 = list2.remainingUtilities;
        double[] logProbs1 = list1.logProbabilities;
        double[] logProbs2 = list2.logProbabilities;

        int count = 0;
        int lastPos = 0;  // Remember last position in list2

        // Exponential search for each entry in list1
        for (int i = 0; i < list1.entryCount; i++) {
            int tid1 = tids1[i];

            // PHASE 1: Exponential search to find range
            int bound = 1;
            while (lastPos + bound < list2.entryCount &&
                   tids2[lastPos + bound] < tid1) {
                bound *= 2;  // Double the jump
            }

            // PHASE 2: Binary search in range [lastPos, min(lastPos + bound + 1, entryCount)]
            // Include the boundary position where tids2[lastPos + bound] >= tid1
            int low = lastPos;
            int high = Math.min(lastPos + bound + 1, list2.entryCount);

            int pos = binarySearch(tids2, low, high, tid1);

            if (pos >= 0) {
                // Match found - compute joined entry
                double utility = utils1[i] + utils2[pos];
                double remaining = Math.min(rem1[i], rem2[pos]);
                double logProb = logProbs1[i] + logProbs2[pos];

                tids[count] = tid1;
                utils[count] = utility;
                remainings[count] = remaining;
                logProbs[count] = logProb;
                count++;

                // Inline EU accumulation
                double prob = Math.exp(logProb);
                tempSumEU += utility * prob;

                // Inline PUB accumulation
                double totalPotential = utility + remaining;
                if (totalPotential > 0) {
                    tempPosUB += prob * totalPotential;
                }

                // Inline EP accumulation
                if (logProb > LOG_ONE_MINUS_EPSILON) {
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

                lastPos = pos + 1;  // Advance for next search
            } else {
                // Not found - update lastPos to start of last search range
                lastPos = low;
            }
        }

        if (count == 0) {
            return null;
        }

        double ep = (logComplement <= LOG_ZERO) ? 1.0 : 1.0 - Math.exp(logComplement);

        // Build joined itemset
        int finalSize = list1.itemset.size() + 1;
        int initialCapacity = (finalSize * 4) / 3 + 1;
        Set<Integer> joinedItemset = new HashSet<>(initialCapacity);
        joinedItemset.addAll(list1.itemset);
        joinedItemset.add(extensionItem);

        return new UtilityProbabilityList(joinedItemset, tids, utils, remainings, logProbs,
                                         count, joinedPTWU, tempSumEU, ep, tempPosUB);
    }

    /**
     * Binary search for target in array[low..high).
     *
     * @param arr    sorted array
     * @param low    start index (inclusive)
     * @param high   end index (exclusive)
     * @param target value to find
     * @return index if found, -1 otherwise
     */
    private int binarySearch(int[] arr, int low, int high, int target) {
        while (low < high) {
            int mid = low + (high - low) / 2;
            if (arr[mid] == target) {
                return mid;
            } else if (arr[mid] < target) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return -1;
    }
}
