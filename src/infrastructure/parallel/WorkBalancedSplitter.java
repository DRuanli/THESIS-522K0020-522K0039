package infrastructure.parallel;

import domain.model.ItemRanking;
import domain.model.UtilityProbabilityList;

import java.util.List;
import java.util.Map;

/**
 * PTWU-weighted binary task splitter for ForkJoin load balancing.
 *
 * <p>Naïve midpoint splitting assigns equal numbers of items to each half,
 * but item subtrees differ wildly in size (high-PTWU items have more
 * transaction intersections and thus more UPU-List join work).  PTWU-weighted
 * splitting assigns items to halves such that the total PTWU is approximately
 * equal, producing more balanced subtree work.
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li>Scan the range to compute {@code totalWork = Σ PTWU(i)}.</li>
 *   <li>Walk items left-to-right, accumulating until {@code cumulative ≥ totalWork / 2}.</li>
 *   <li>Return the index at which the cumulative sum first crosses the midpoint.</li>
 * </ol>
 *
 * <p>If all items have zero PTWU (e.g., all-negative-profit range), falls back
 * to ordinary midpoint splitting.
 */
public final class WorkBalancedSplitter {

    private WorkBalancedSplitter() {
        // Static utility class — no instances
    }

    /**
     * Finds a split index that balances cumulative PTWU between the two halves.
     *
     * @param itemRanking     item ranking supplying the ordered item list
     * @param singleItemLists UPU-Lists keyed by item ID (used to read PTWU)
     * @param rangeStart      inclusive start of the range to split
     * @param rangeEnd        exclusive end of the range to split
     * @return split index {@code s} such that {@code [rangeStart, s)} and {@code [s, rangeEnd)}
     *         have approximately equal cumulative PTWU; guaranteed in {@code (rangeStart, rangeEnd)}
     */
    public static int findSplit(ItemRanking itemRanking,
                               Map<Integer, UtilityProbabilityList> singleItemLists,
                               int rangeStart, int rangeEnd) {
        List<Integer> sortedItems = itemRanking.getSortedItems();

        // Compute total PTWU in range (direct field access for performance)
        double totalWork = 0.0;
        for (int i = rangeStart; i < rangeEnd; i++) {
            int item = sortedItems.get(i);
            UtilityProbabilityList list = singleItemLists.get(item);
            if (list != null) {
                totalWork += list.ptwu;
            }
        }

        // Degenerate case: all items have zero PTWU (e.g. all negative-profit).
        // PTWU-weighted split degrades to 1-vs-all; midpoint is strictly better.
        if (totalWork == 0.0) {
            return (rangeStart + rangeEnd) >>> 1;
        }

        // Find midpoint by PTWU
        double halfWork = totalWork / 2.0;
        double cumulative = 0.0;
        int split = rangeStart;

        for (int i = rangeStart; i < rangeEnd; i++) {
            int item = sortedItems.get(i);
            UtilityProbabilityList list = singleItemLists.get(item);
            if (list != null) {
                cumulative += list.ptwu;
            }
            if (cumulative >= halfWork) {
                split = i + 1;
                break;
            }
        }

        // Ensure valid split
        if (split <= rangeStart) split = rangeStart + 1;
        if (split >= rangeEnd) split = rangeEnd - 1;

        return split;
    }
}
