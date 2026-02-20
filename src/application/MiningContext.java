package application;

import domain.collection.TopKCollectorFactory;
import domain.collection.TopKCollectorInterface;
import domain.model.*;
import infrastructure.parallel.ThresholdCoordinator;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mutable state container that threads data through the three mining phases.
 *
 * <p>Each phase reads from earlier fields and writes to its own output fields.
 * The access pattern is strictly sequential per phase (phases 1–2 are single-threaded
 * or barrier-synchronized before the next phase starts), so no locking is needed on
 * the context fields themselves.  Phase 3 access to the {@link TopKPatternCollector}
 * is thread-safe through the collector's own lock.
 *
 * <h3>Data flow</h3>
 * <pre>
 *   Phase 1 (PREPROCESSING) → itemPTWU, itemExistentialProbabilities, validItems,
 *                             itemRanking, singleItemLists
 *   Phase 2 (INITIALIZATION) → collector (via tryCollect for 1-itemsets),
 *                              thresholdCoordinator.captureInitialThreshold()
 *   Phase 3 (MINING) → collector (via tryCollect for multi-itemsets, parallel)
 * </pre>
 */
public final class MiningContext {

    private final MiningConfiguration config;
    private final ProfitTable profitTable;
    private final List<Transaction> database;

    private double[] itemPTWU;        // NOW: dense array indexed by dense index
    private double[] itemLogComp;     // NOW: dense array indexed by dense index
    private int maxItemId;            // for array bounds checking

    // Dense index mapping (NEW)
    private int[] itemIdToDenseIndex;  // sparse[maxItemId+1] -> dense index or -1
    private int[] denseIndexToItemId;  // dense[denseSize] -> item ID
    private int denseSize;             // number of actual items

    private Set<Integer> validItems;
    private ItemRanking itemRanking;
    private Map<Integer, UtilityProbabilityList> singleItemLists;

    private final TopKCollectorInterface collector;  // Interface type for alternative implementations
    private final ThresholdCoordinator thresholdCoordinator;

    public MiningContext(MiningConfiguration config, ProfitTable profitTable,
                         List<Transaction> database) {
        this.config = config;
        this.profitTable = profitTable;
        this.database = database;
        // Use factory to create collector based on configuration
        this.collector = TopKCollectorFactory.create(config.getCollectorType(), config.getK());
        this.thresholdCoordinator = new ThresholdCoordinator(collector);
    }

    // Getters and setters
    public MiningConfiguration getConfig() { return config; }
    public ProfitTable getProfitTable() { return profitTable; }
    public List<Transaction> getDatabase() { return database; }

    public double[] getItemPTWU() { return itemPTWU; }
    public void setItemPTWU(double[] ptwu) { this.itemPTWU = ptwu; }

    public double[] getItemLogComp() { return itemLogComp; }
    public void setItemLogComp(double[] logComp) { this.itemLogComp = logComp; }

    public int getMaxItemId() { return maxItemId; }
    public void setMaxItemId(int maxItemId) { this.maxItemId = maxItemId; }

    // Dense mapping getters/setters (NEW)
    public int[] getItemIdToDenseIndex() { return itemIdToDenseIndex; }
    public void setItemIdToDenseIndex(int[] mapping) { this.itemIdToDenseIndex = mapping; }

    public int[] getDenseIndexToItemId() { return denseIndexToItemId; }
    public void setDenseIndexToItemId(int[] mapping) { this.denseIndexToItemId = mapping; }

    public int getDenseSize() { return denseSize; }
    public void setDenseSize(int denseSize) { this.denseSize = denseSize; }

    /**
     * Computes EP for an item from stored log-complement.
     */
    public double getEP(int itemId) {
        if (itemId < 0 || itemId > maxItemId || itemLogComp == null) return 0.0;
        if (itemIdToDenseIndex == null) return 0.0;

        // CHANGE: Translate item ID to dense index before array access
        int denseIdx = itemIdToDenseIndex[itemId];
        if (denseIdx < 0) return 0.0;  // item not in profit table

        double lc = itemLogComp[denseIdx];  // CHANGE: use denseIdx
        return (lc <= -700.0) ? 1.0 : 1.0 - Math.exp(lc);
    }

    public Set<Integer> getValidItems() { return validItems; }
    public void setValidItems(Set<Integer> items) { this.validItems = items; }

    public ItemRanking getItemRanking() { return itemRanking; }
    public void setItemRanking(ItemRanking ranking) { this.itemRanking = ranking; }

    public Map<Integer, UtilityProbabilityList> getSingleItemLists() {
        return singleItemLists;
    }
    public void setSingleItemLists(Map<Integer, UtilityProbabilityList> lists) {
        this.singleItemLists = lists;
    }

    public TopKCollectorInterface getCollector() { return collector; }  // Return interface type
    public ThresholdCoordinator getThresholdCoordinator() {
        return thresholdCoordinator;
    }
}
