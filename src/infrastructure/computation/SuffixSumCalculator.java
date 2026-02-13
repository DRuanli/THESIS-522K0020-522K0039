package infrastructure.computation;

import domain.model.ItemInfo;

import java.util.List;

/**
 * Computes per-position positive-utility suffix sums used to build the
 * {@code remainingUtilities} field of each UPU-List entry.
 *
 * <h3>Definition</h3>
 * For a transaction with items ranked {@code [0, 1, ..., n-1]} in PTWU order:
 * <pre>
 *   suffixSum[i] = Σ_{j=i+1}^{n-1} max(0, profit(j)) × quantity(j)
 * </pre>
 * That is, {@code suffixSum[i]} is the sum of utilities of all <em>higher-ranked</em>
 * positive-profit items after position {@code i}.  When item at position {@code i}
 * is placed into a UPU-List entry, this value becomes its {@code remainingUtility},
 * enabling the Positive Upper Bound computation in later joins.
 *
 * <h3>Why only positive utilities?</h3>
 * Negative-profit items cannot contribute to the upper bound — including them
 * would make the bound non-monotone.  The suffix sum therefore sums
 * {@code profit > 0} items only.
 *
 * <p>Result array length equals {@code items.size()}.  The last entry is always
 * {@code 0.0} (no items after the last position).
 */
public final class SuffixSumCalculator {

    private SuffixSumCalculator() {
        // Static utility class — no instances
    }

    /**
     * Computes the positive-utility suffix sums for items in PTWU rank order.
     *
     * @param items list of {@link ItemInfo} descriptors sorted by ascending PTWU rank
     * @return array of length {@code items.size()} where entry {@code i} holds the
     *         sum of positive utilities of all items at positions {@code > i}
     */
    public static double[] computeSuffixSums(List<ItemInfo> items) {
        int n = items.size();
        double[] suffixSums = new double[n];

        suffixSums[n - 1] = 0.0;
        for (int i = n - 2; i >= 0; i--) {
            ItemInfo next = items.get(i + 1);
            double nextUtil = (next.profit > 0) ? next.utility : 0.0;
            suffixSums[i] = suffixSums[i + 1] + nextUtil;
        }

        return suffixSums;
    }
}
