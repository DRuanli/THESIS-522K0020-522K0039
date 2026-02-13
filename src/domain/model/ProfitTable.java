package domain.model;

import java.util.*;

/**
 * Immutable profit-per-item lookup table used throughout the mining pipeline
 *
 * <p>The profit value {@code profit(i)} associated with each item {@code i} is the
 * domain-specific unit value (revenue, gain, or loss) assigned ot one unit of item {@code i}.
 * <b>Negative profits are explicity suppported</b> to model cost or loss items:
 *
 * <p><b>Role in the algorithm</b>
 * <ul>
 *     <li><em>PTWU computation (Phase 1):</em> only positive-profit items contribute to the
 *          Positive Transaction-Weighted Utility (PTWU), which is the upper bound used for
 *          pruning. Items with negative profit are excluded from PTWU to keep the bound valid.</li>
 *     <li><em>Utility computation (Phase 2):</em> utility {@code u(i, T) = profit(i) × q(i, T)}
 *         includes negative values exactly.</li>
 *     <li><em>Suffix sums (Phase 2):</em> only positive-utility contributions are accumulated
 *         in the remaining-utility suffix sum, which forms the Positive Upper Bound (PUB).</li>
 * </ul>
 *
 * <p><b>Immutability:</b> the profit map is defensively copied on construction and exposed
 *  only through read-only views, enabling safe concurrent access during parallel phases.
 */
public class ProfitTable {

    /**
     * Core lookup table mapping Item IDs to their unit profit.
     * Encapsulated in an unmodifiable map to ensure thread-safety during parallel mining.
     */
    private final Map<Integer,Double> profits;

    /**
     * Constructs a profit table from an arbitrary item → profit mapping.
     *
     * <p>The input map is defensively copied; callers may modify their map freely after
     * construction. Positive and negative item sets are eagerly computed and cached.
     *
     * @param profits map from item ID to profit value (may be negative, zero, or positive)
     */
    public ProfitTable(Map<Integer, Double> profits) {
        this.profits = Collections.unmodifiableMap(new HashMap<>(profits));
    }

    /**
     * Returns the profit of an item.
     *
     * @param item item ID
     * @return {@code profit(item)}, or 0.0 if the item is not in the table
     */
    public double getProfit(int item) {
        return profits.getOrDefault(item, 0.0);
    }


    /**
     * Returns the complete set of item IDs in this profit table.
     *
     * @return unmodifiable set of all known item IDs
     */
    public Set<Integer> getAllItems() {
        return profits.keySet();
    }

    /**
     * Returns the number of items in the profit table.
     *
     * @return total item count
     */
    public int size() {
        return profits.size();
    }
}
