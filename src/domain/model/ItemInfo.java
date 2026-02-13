package domain.model;

/**
 * Transient, per-transaction item descriptor used during Phase 2 UPU-List construction.
 *
 * <p>Each {@code ItemInfo} instance captures the information needed to produce one
 * {@link UtilityProbabilityList.Entry} for a specific item–transaction pair:
 * <ul>
 *   <li>{@code itemId} — the item identifier.</li>
 *   <li>{@code rank} — the item's 0-based position in the PTWU-ascending order
 *       ({@link ItemRanking}). Cached here so the list of items in a transaction
 *       can be sorted in O(n log n) by rank without repeated map lookups.</li>
 *   <li>{@code quantity} — q(item, T).</li>
 *   <li>{@code profit} — profit(item), stored to decide whether the utility
 *       contributes to the positive suffix sum.</li>
 *   <li>{@code utility} — u(item, T) = profit × quantity (pre-computed).</li>
 *   <li>{@code logProbability} — log P(item, T), stored in log-space for stable
 *       UPU-List join multiplication (addition in log-space).</li>
 * </ul>
 *
 * <p>Instances are created inside {@code MiningOrchestrator.extractSortedItems()} and
 * consumed immediately by {@link oop.infrastructure.computation.SuffixSumCalculator}
 * and the UPU-List entry builder. They are never stored beyond the transaction loop.
 *
 * @see UtilityProbabilityList.Entry
 * @see oop.infrastructure.computation.SuffixSumCalculator
 */
public final class ItemInfo {

    /** Item identifier. */
    public final int itemId;

    /** 0-based PTWU-ascending rank; enables O(1) sort key during suffix sum computation. */
    public final int rank;

    /** Purchase quantity q(item, T) for this transaction. */
    public final int quantity;

    /** Unit profit value (may be negative). */
    public final double profit;

    /** Pre-computed utility: {@code profit × quantity}. May be negative. */
    public final double utility;

    /** Log-space probability: {@code log P(item, T)}. */
    public final double logProbability;

    /**
     * Constructs an item descriptor with pre-computed utility.
     *
     * @param itemId         item identifier
     * @param rank           0-based PTWU rank from {@link ItemRanking}
     * @param quantity        purchase quantity q(item, T)
     * @param profit          unit profit value
     * @param logProbability  {@code log P(item, T)}; use {@link oop.infrastructure.computation.ProbabilityModel#toLogSpace}
     *                        to convert raw probabilities safely
     */
    public ItemInfo(int itemId, int rank, int quantity, double profit, double logProbability) {
        this.itemId = itemId;
        this.rank = rank;
        this.quantity = quantity;
        this.profit = profit;
        this.utility = profit * quantity;
        this.logProbability = logProbability;
    }
}
