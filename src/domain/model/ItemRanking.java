package domain.model;

import java.util.*;
import java.util.stream.Collectors;

import static infrastructure.util.NumericalConstants.EPSILON;

/**
 * Maintains a total ordering of items by ascending PTWU (Probabilistic Transaction-Weighted Utility).
 *
 * <p>In the PTK-HUIM algorithm, items are processed in PTWU-ascending order during pattern growth.
 * This canonical ordering serves two critical purposes:
 *
 * <h3>1. Pruning via PTWU Upper Bound</h3>
 * <p>PTWU is a monotone upper bound: if an itemset X has PTWU below the current threshold,
 * then all supersets of X also have PTWU below the threshold. By exploring items in
 * PTWU-ascending order, low-utility branches are pruned early.
 *
 * <h3>2. Avoiding Duplicate Enumeration</h3>
 * <p>The canonical ordering prevents counting the same pattern multiple times. When extending
 * a prefix with rank {@code r}, only items at positions {@code > r} are considered, ensuring
 * each itemset is generated exactly once.
 *
 * <h3>Data Structure</h3>
 * <p>This class uses a hybrid approach for optimal performance:
 * <ul>
 *   <li><b>Sorted list</b> — {@code sortedItems} stores items in PTWU-ascending order
 *       for sequential traversal during pattern growth</li>
 *   <li><b>Array-based reverse lookup</b> — {@code rankByItemId[item]} provides O(1)
 *       rank lookups (3× faster than HashMap) for filtering transaction items</li>
 *   <li><b>PTWU values</b> — {@code ptwuValues[rank]} stores PTWU for binary search
 *       to compute the global cutoff threshold</li>
 * </ul>
 *
 * <h3>Example</h3>
 * <pre>
 *   Assume items {A=5, B=12, C=3} with PTWU values {150.0, 80.0, 200.0}
 *
 *   Ranking (PTWU ascending):
 *     Rank 0: Item B (PTWU=80.0)
 *     Rank 1: Item A (PTWU=150.0)
 *     Rank 2: Item C (PTWU=200.0)
 *
 *   Reverse lookup:
 *     rankByItemId[5]  = 1   (Item A has rank 1)
 *     rankByItemId[12] = 0   (Item B has rank 0)
 *     rankByItemId[3]  = 2   (Item C has rank 2)
 *
 *   Pattern growth from prefix {B}:
 *     - Consider only items with rank > 0 (i.e., A and C)
 *     - This avoids generating {A,B} and {B,A} separately
 * </pre>
 *
 * <h3>Construction</h3>
 * <p>Instances are created via the static factory method {@link #fromPTWUArray(double[], Set, int)},
 * which sorts valid items by PTWU and builds the optimized lookup structures.
 *
 * <h3>Thread Safety</h3>
 * <p>Instances are immutable after construction and safe for concurrent access.
 *
 * @see UtilityProbabilityList
 * @see PTWUCalculator
 */
public class ItemRanking {

    private final List<Integer> sortedItems;
    private final int[] rankByItemId;  // Array-based rank lookup (faster than HashMap)
    private final double[] ptwuValues;

    /**
     * Private constructor for creating an ItemRanking from pre-sorted items.
     *
     * <p>This constructor builds the optimized rank-lookup array and PTWU array
     * from the provided sorted item list. It is called only by the static factory
     * method {@link #fromPTWUArray(double[], Set, int)}.
     *
     * @param sortedItems items already sorted by PTWU ascending (with ties broken by item ID)
     * @param ptwuArray   full PTWU array indexed by item ID (from Phase 1)
     * @param maxItemId   maximum item ID in the dataset (determines array size)
     */
    private ItemRanking(List<Integer> sortedItems, double[] ptwuArray, int maxItemId) {
        this.sortedItems = Collections.unmodifiableList(new ArrayList<>(sortedItems));

        // Build array-based rank lookup and PTWU array
        this.rankByItemId = new int[maxItemId + 1];
        Arrays.fill(rankByItemId, -1);  // Default: item not ranked

        this.ptwuValues = new double[sortedItems.size()];

        for (int i = 0; i < sortedItems.size(); i++) {
            int itemId = sortedItems.get(i);
            rankByItemId[itemId] = i;                        // O(1) direct array assignment
            ptwuValues[i] = (itemId <= maxItemId) ? ptwuArray[itemId] : 0.0;  // Direct array access
        }
    }

    /**
     * Constructs an {@code ItemRanking} from a PTWU array and a set of valid items.
     *
     * <p>Only items present in {@code validItems} are included in the ranking
     * (items filtered out in Phase 1 by the EP threshold are excluded).
     *
     * @param ptwuArray  array of PTWU values indexed by item ID (computed in Phase 1)
     * @param validItems subset of item IDs that pass the minimum EP threshold
     * @param maxItemId  maximum item ID in the dataset (array size = maxItemId + 1)
     * @return {@code ItemRanking} with items sorted by PTWU ascending, ties broken by item ID
     */
    public static ItemRanking fromPTWUArray(double[] ptwuArray,
                                           Set<Integer> validItems,
                                           int maxItemId) {
        List<Integer> sorted = validItems.stream()
            .filter(item -> {
                // Filter out items with PTWU <= 0
                // These items appear in transactions but only with negative-profit items
                double ptwu = (item <= maxItemId) ? ptwuArray[item] : 0.0;
                return ptwu > 0.0;
            })
            .sorted((a, b) -> {
                double ptwuA = (a <= maxItemId) ? ptwuArray[a] : 0.0;
                double ptwuB = (b <= maxItemId) ? ptwuArray[b] : 0.0;
                int c = Double.compare(ptwuA, ptwuB);
                return (c != 0) ? c : Integer.compare(a, b);
            })
            .collect(Collectors.toList());
        return new ItemRanking(sorted, ptwuArray, maxItemId);
    }


    /**
     * Returns the 0-based rank of an item.
     *
     * <p>Rank 0 corresponds to the item with the smallest PTWU.
     * Only valid items (those returned by {@link #getSortedItems()}) have a rank.
     *
     * <p><b>Performance</b>: O(1) direct array access (3x faster than HashMap.get()).
     *
     * @param item item ID
     * @return 0-based rank, or −1 if the item is not in the ranking
     */
    public int getRank(int item) {
        return (item >= 0 && item < rankByItemId.length) ? rankByItemId[item] : -1;
    }

    /**
     * Returns all ranked items in PTWU-ascending order.
     *
     * <p>This list defines the canonical extension order: when building an itemset
     * from prefix with rank {@code r}, only items at positions {@code > r} are
     * considered as extensions.
     *
     * @return unmodifiable list of item IDs sorted by PTWU ascending
     */
    public List<Integer> getSortedItems() {
        return sortedItems;
    }


    /**
     * Binary-searches the PTWU array for the first index whose PTWU ≥ threshold.
     *
     * <p>Used in Phase 5 to compute {@code globalCutoff}: since items below the
     * initial threshold can never form a valid extension (their PTWU is too low),
     * all search engines start extensions at or beyond this cutoff.
     *
     * @param threshold minimum PTWU value (typically the initial mining threshold)
     * @return first 0-based index with PTWU ≥ threshold, or {@code size()} if none
     */
    public int findFirstIndexAboveThreshold(double threshold) {
        int lo = 0, hi = ptwuValues.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (ptwuValues[mid] < threshold - EPSILON) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /**
     * Returns the number of ranked items.
     *
     * @return count of valid, ranked items
     */
    public int size() {
        return sortedItems.size();
    }
}
