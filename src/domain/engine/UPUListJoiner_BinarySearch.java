package domain.engine;

import domain.model.UtilityProbabilityList;

import java.util.HashSet;
import java.util.Set;

import static infrastructure.util.NumericalConstants.*;

/**
 * Alternative join implementation using Binary Search.
 *
 * <p>This is a research baseline for comparing against the optimized two-pointer merge.
 * It performs a binary search in list2 for each entry in list1.
 *
 * <p><b>Algorithm</b>:
 * For each entry in list1:
 * <ol>
 *   <li>Binary search for matching TID in list2</li>
 *   <li>If match found, compute joined entry and aggregates</li>
 * </ol>
 *
 * <p><b>Complexity</b>: O(|L1| × log|L2|)
 *
 * <p><b>Thread safety</b>: Stateless, may be shared across threads.
 */
public final class UPUListJoiner_BinarySearch implements UPUListJoinerInterface {

    /**
     * Joins two UPU-Lists using binary search.
     *
     * @param list1            UPU-List of the prefix itemset {@code X}
     * @param list2            UPU-List of the single-item extension {@code {j}}
     * @param extensionItem    item ID of {@code j}
     * @param threshold        current dynamic threshold for early pruning
     * @return the joined UPU-List {@code L(X ∪ {j})}, or {@code null} if pruned
     */
    public UtilityProbabilityList join(UtilityProbabilityList list1,
                                       UtilityProbabilityList list2,
                                       int extensionItem,
                                       double threshold) {
        double joinedPTWU = Math.min(list1.ptwu, list2.ptwu);

        if (joinedPTWU < threshold - EPSILON) {
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

        // Binary search for each entry in list1
        for (int i = 0; i < list1.entryCount; i++) {
            int tid1 = tids1[i];

            // Binary search for tid1 in list2
            int pos = binarySearch(tids2, 0, list2.entryCount, tid1);

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
