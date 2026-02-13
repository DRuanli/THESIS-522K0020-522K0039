package domain.model;

import java.util.*;

/**
 * Represents a single uncertain transaction in a probabilistic transaction database.
 *
 * <p>In traditional databases, items either appear or don't appear in a transaction.
 * In <b>uncertain databases</b>, each item has a <b>probability of occurrence</b>,
 * modeling real-world scenarios where item presence is uncertain or predicted.
 *
 * <h3>Structure</h3>
 * <p>Each transaction consists of:
 * <ul>
 *   <li><b>Transaction ID</b> — Unique identifier (1-based sequential number)</li>
 *   <li><b>Items</b> — Set of item IDs that may appear in this transaction</li>
 *   <li><b>Quantities</b> — For each item, how many units (e.g., 3 bottles of milk)</li>
 *   <li><b>Probabilities</b> — For each item, the likelihood of occurrence ∈ [0, 1]</li>
 * </ul>
 *
 * <h3>Example</h3>
 * <p>Consider a retail transaction where customer purchase predictions are probabilistic:
 * <pre>
 *   Transaction T₁ (ID=1):
 *     Item 5 (milk):   quantity=2, probability=0.85  (85% likely to buy 2 units)
 *     Item 12 (bread): quantity=1, probability=0.90  (90% likely to buy 1 unit)
 *     Item 23 (eggs):  quantity=3, probability=0.60  (60% likely to buy 3 units)
 *
 *   Real-world scenarios:
 *     - Recommendation systems: predicted items with confidence scores
 *     - Sensor data: items detected with measurement uncertainty
 *     - Market basket: items purchased with stochastic demand
 * </pre>
 *
 * <h3>Performance Optimization</h3>
 * <p>This class uses <b>array-based storage</b> instead of HashMaps for critical performance:
 * <ul>
 *   <li><b>Arrays indexed by item ID</b> — O(1) access vs O(log n) for TreeMap</li>
 *   <li><b>5× faster lookups</b> — Measured in Phase 3 (pattern mining bottleneck)</li>
 *   <li><b>Cache-friendly</b> — Sequential memory access patterns</li>
 *   <li><b>Trade-off</b> — Sparse storage if item IDs have large gaps</li>
 * </ul>
 *
 * <h3>Lazy Computation</h3>
 * <p>The item set is computed lazily via {@link #getItems()} only when first requested,
 * avoiding unnecessary allocation during file parsing when only array access is needed.
 *
 * <h3>Data Integrity</h3>
 * <p>Transactions must satisfy:
 * <ul>
 *   <li>Non-empty (at least one item)</li>
 *   <li>Each item has both quantity > 0 AND probability > 0</li>
 *   <li>Probabilities are valid: P(item, T) ∈ [0, 1]</li>
 *   <li>Quantities are positive integers: q(item, T) > 0</li>
 * </ul>
 *
 * <h3>Thread Safety</h3>
 * <p>Immutable after construction except for lazy {@code itemSet} initialization.
 * The lazy initialization is not thread-safe, but transactions are typically
 * accessed from a single thread during mining phases.
 *
 * <h3>Construction</h3>
 * <p>Instances are created by {@code FileTransactionReader} during database parsing.
 * The constructor converts HashMap-based input (from file parsing) to optimized
 * array-based internal storage.
 *
 * @see FileTransactionReader
 * @see PTWUCalculator
 */
public class Transaction {

    private final int transactionId;

    // HashMap-based storage (optimized for sparse item spaces)
    private final Map<Integer, Integer> quantities;
    private final Map<Integer, Double> probabilities;

    // Cached item set (cached from map keys)
    private Set<Integer> itemSet;

    /**
     * Constructs a transaction from HashMaps.
     *
     * <p>This constructor stores the HashMaps directly for memory-efficient
     * storage in sparse item spaces (e.g., kosarak dataset).
     *
     * @param transactionId     unique transaction identifier (1-based)
     * @param itemQuantities    map of item ID → quantity
     * @param itemProbabilities map of item ID → probability
     */
    public Transaction(int transactionId,
                       Map<Integer, Integer> itemQuantities,
                       Map<Integer, Double> itemProbabilities) {
        this.transactionId = transactionId;
        // Store HashMaps directly (memory-efficient for sparse data)
        this.quantities = new HashMap<>(itemQuantities);
        this.probabilities = new HashMap<>(itemProbabilities);
    }

    /**
     * Validates transaction data integrity.
     *
     * <p>This method ensures that the transaction satisfies all data integrity constraints
     * required for correct mining operation. It is intended to be called after construction
     * to catch malformed input data early.
     *
     * <p><b>Validation Rules:</b>
     * <ol>
     *   <li><b>Non-empty</b> — Transaction must contain at least one item</li>
     *   <li><b>Quantity-Probability consistency</b> — Each item must have BOTH quantity > 0
     *       AND probability > 0 (cannot have one without the other)</li>
     *   <li><b>Valid probabilities</b> — All probabilities must be in range [0, 1]</li>
     *   <li><b>Positive quantities</b> — All quantities must be positive integers</li>
     * </ol>
     *
     * <p><b>Usage Note:</b> This method is currently defined but not called from the constructor.
     * To enable validation, add {@code validate();} at the end of the constructor. Validation
     * adds runtime overhead but catches malformed data during parsing instead of during mining.
     *
     * @throws IllegalArgumentException if transaction is empty, or if any item has:
     *         <ul>
     *           <li>Mismatched quantity/probability (one is 0 while the other is not)</li>
     *           <li>Probability outside range [0, 1]</li>
     *           <li>Non-positive quantity</li>
     *         </ul>
     */
    private void validate() {
        if (quantities.isEmpty()) {
            throw new IllegalArgumentException("Transaction cannot be empty");
        }

        for (Map.Entry<Integer, Integer> e : quantities.entrySet()) {
            int item = e.getKey();
            int qty = e.getValue();
            Double prob = probabilities.get(item);

            // Check that items have matching quantities and probabilities
            if (prob == null || prob <= 0.0) {
                throw new IllegalArgumentException(
                    "Item " + item + " has mismatched quantity/probability");
            }

            // Validate probability range
            if (prob < 0.0 || prob > 1.0) {
                throw new IllegalArgumentException(
                    "Invalid probability " + prob + " for item " + item);
            }

            // Validate quantity
            if (qty <= 0) {
                throw new IllegalArgumentException(
                    "Invalid quantity " + qty + " for item " + item);
            }
        }
    }

    /**
     * Returns the unique transaction identifier.
     *
     * @return 1-based sequential transaction ID
     */
    public int getTransactionId() {
        return transactionId;
    }

    /**
     * Returns the set of item IDs present in this transaction.
     *
     * <p>Returns the keyset from the quantities map.
     *
     * @return set of item IDs
     */
    public Set<Integer> getItems() {
        if (itemSet == null) {
            itemSet = quantities.keySet();
        }
        return itemSet;
    }

    /**
     * Returns the quantity of an item in this transaction.
     *
     * <p><b>Performance</b>: O(1) HashMap lookup.
     *
     * @param item item ID
     * @return q(item, T) if present, 0 otherwise
     */
    public int getQuantity(int item) {
        return quantities.getOrDefault(item, 0);
    }

    /**
     * Returns the occurrence probability of an item in this transaction.
     *
     * <p><b>Performance</b>: O(1) HashMap lookup.
     *
     * @param item item ID
     * @return P(item, T) ∈ [0, 1] if present, 0.0 otherwise
     */
    public double getProbability(int item) {
        return probabilities.getOrDefault(item, 0.0);
    }

    /**
     * Returns the map of item quantities for efficient bulk access.
     *
     * <p><b>WARNING:</b> Direct map access - do not modify!
     *
     * @return map of item ID → quantity
     */
    public Map<Integer, Integer> getItemQuantities() {
        return quantities;
    }

    /**
     * Returns the map of item probabilities for efficient bulk access.
     *
     * <p><b>WARNING:</b> Direct map access - do not modify!
     *
     * @return map of item ID → probability
     */
    public Map<Integer, Double> getItemProbabilities() {
        return probabilities;
    }

    @Override
    public String toString() {
        return "Transaction{id=" + transactionId + ", items=" + getItems().size() + "}";
    }
}
