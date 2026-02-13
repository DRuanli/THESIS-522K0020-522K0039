package domain.model;

import java.util.*;

/**
 * Represents a high-utility pattern discovered in an uncertain transaction database.
 *
 * <p>A high-utility pattern is an itemset X = {i₁, i₂, ..., iₖ} that appears across
 * multiple uncertain transactions with sufficiently high <b>Expected Utility (EU)</b>
 * and <b>Existential Probability (EP)</b>.
 *
 * <h3>Key Concepts</h3>
 * <ul>
 *   <li><b>Expected Utility (EU)</b> — The probability-weighted sum of utilities across
 *       all transactions where X appears. Measures the expected profit/value of X.</li>
 *   <li><b>Existential Probability (EP)</b> — The probability that X appears in at least
 *       one transaction in the database. Measures the reliability/likelihood of X.</li>
 *   <li><b>Itemset</b> — The set of item IDs that comprise this pattern (e.g., {5, 12, 23}).</li>
 * </ul>
 *
 * <h3>Example</h3>
 * <p>Consider a retail database where items have uncertain presence (customers might
 * purchase with certain probabilities):
 * <pre>
 *   Pattern X = {milk, bread, butter}
 *   EU(X) = 125.50  (expected profit from this combination)
 *   EP(X) = 0.85    (85% chance of appearing in at least one transaction)
 * </pre>
 *
 * <h3>Top-K Mining</h3>
 * <p>In Top-K High-Utility Itemset Mining, we discover the K patterns with highest EU,
 * subject to a minimum EP threshold. This class represents one such discovered pattern.
 *
 * <h3>Comparison Semantics</h3>
 * <ul>
 *   <li><b>compareTo()</b> — Compares by EU ascending (for min-heap in Top-K collection)</li>
 *   <li><b>equals()</b> — Based on itemset identity only (not EU or EP values)</li>
 *   <li><b>hashCode()</b> — Based on itemset identity only</li>
 * </ul>
 *
 * <h3>Performance Optimization</h3>
 * <p>The class caches a sorted item list at construction time to optimize the
 * {@code compareTo()} method, which is called frequently during Top-K maintenance.
 * This avoids repeated allocation and sorting operations.
 *
 * <h3>Immutability</h3>
 * <p>All fields are final, making instances immutable after construction. However,
 * the {@code items} set reference is retained directly (no defensive copy) for
 * performance — callers must ensure the set is not modified after construction.
 *
 * @see TopKPatternCollector
 * @see UtilityProbabilityList
 */
public class HighUtilityPattern implements Comparable<HighUtilityPattern> {
    /** The itemset: set of item IDs comprising this pattern. */
    public final Set<Integer> items;

    /** EU(X): probability-weighted sum of utilities across the database. */
    public final double expectedUtility;

    /** EP(X): probability of appearing in at least one transaction. */
    public final double existentialProbability;

    /**
     * Cached sorted item list for fast comparisons.
     * Computed once at construction to avoid repeated allocation and sorting in compareTo().
     */
    private final List<Integer> sortedItems;

    /**
     * Constructs a high-utility pattern.
     *
     * <p>The {@code items} reference is retained directly (no defensive copy)
     * for performance; callers must ensure the set is not subsequently modified.
     *
     * @param items                  non-null, non-empty set of item IDs
     * @param expectedUtility        EU(X) ∈ ℝ (may be negative if loss items dominate)
     * @param existentialProbability EP(X) ∈ [0, 1]
     * @throws IllegalArgumentException if {@code items} is null or empty, or if
     *                                  {@code existentialProbability} is outside [0, 1]
     */
    public HighUtilityPattern(Set<Integer> items, double expectedUtility,
                             double existentialProbability) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Pattern items cannot be empty");
        }
        if (existentialProbability < 0.0 || existentialProbability > 1.0) {
            throw new IllegalArgumentException(
                "Invalid existential probability: " + existentialProbability);
        }
        this.items = items;
        this.expectedUtility = expectedUtility;
        this.existentialProbability = existentialProbability;

        // Cache sorted items once for fast comparisons (avoids repeated allocation in compareTo)
        this.sortedItems = new ArrayList<>(items);
        Collections.sort(this.sortedItems);
    }

    /**
     * Returns the itemset.
     *
     * @return set of item IDs
     */
    public Set<Integer> getItems() {
        return items;
    }

    /**
     * Returns the expected utility.
     *
     * @return EU(X)
     */
    public double getExpectedUtility() {
        return expectedUtility;
    }

    /**
     * Returns the existential probability.
     *
     * @return EP(X) ∈ [0, 1]
     */
    public double getExistentialProbability() {
        return existentialProbability;
    }

    /**
     * Compares by EU <em>ascending</em> (min-heap order), with size-then-lexicographic
     * tiebreaking for total-order consistency with {@code equals}.
     *
     * @param other other pattern
     * @return negative if this has smaller EU; positive if larger; 0 if tie-broken equal
     */
    @Override
    public int compareTo(HighUtilityPattern other) {
        int euCmp = Double.compare(this.expectedUtility, other.expectedUtility);
        if (euCmp != 0) return euCmp;

        if (this.items.size() != other.items.size()) {
            return Integer.compare(this.items.size(), other.items.size());
        }

        // Use cached sorted lists instead of creating new ones
        for (int i = 0; i < this.sortedItems.size(); i++) {
            int cmp = Integer.compare(this.sortedItems.get(i), other.sortedItems.get(i));
            if (cmp != 0) return cmp;
        }
        return 0;
    }

    /**
     * Equality is based solely on the itemset identity (not EU or EP).
     *
     * @param obj the other object
     * @return {@code true} if both patterns represent the same itemset
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof HighUtilityPattern)) return false;
        return items.equals(((HighUtilityPattern) obj).items);
    }

    @Override
    public int hashCode() {
        return items.hashCode();
    }

    @Override
    public String toString() {
        return String.format("Pattern{items=%s, EU=%.4f, EP=%.6f}",
                           items, expectedUtility, existentialProbability);
    }
}
