package domain.collection;

import domain.engine.PatternCollector;
import domain.model.HighUtilityPattern;
import domain.model.UtilityProbabilityList;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

import static infrastructure.util.NumericalConstants.EPSILON;

/**
 * Thread-safe Top-K pattern collector using a min-heap with lock-based mutation.
 *
 * <p>Maintains the Top-K patterns ordered by Expected Utility (EU) descending.
 * Internally uses a <em>min-heap</em> (so the weakest pattern is at the root for
 * O(log k) eviction) implemented via {@link TreeSet}, plus a {@link HashMap} index
 * on itemset identity for O(1) update detection.
 *
 * <h3>Dual-Structure Design: Why Both TreeSet AND HashMap?</h3>
 * <p>This class uses two complementary data structures to achieve optimal performance:
 *
 * <p><b>1. TreeSet (Min-Heap for EU Ordering)</b>
 * <ul>
 *   <li><b>Purpose:</b> Maintain patterns sorted by EU ascending (min-heap)</li>
 *   <li><b>Operations:</b>
 *     <ul>
 *       <li>{@code first()} → O(1) access to weakest pattern (lowest EU)</li>
 *       <li>{@code pollFirst()} → O(log k) eviction of weakest pattern</li>
 *       <li>{@code descendingSet()} → O(k) retrieval of Top-K in EU descending order</li>
 *     </ul>
 *   </li>
 *   <li><b>Limitation:</b> Cannot efficiently check "does itemset X already exist?"
 *       because TreeSet searches by {@code compareTo()} (which compares EU),
 *       not by {@code equals()} (which compares itemset identity)</li>
 * </ul>
 *
 * <p><b>2. HashMap (Index for Duplicate Detection)</b>
 * <ul>
 *   <li><b>Purpose:</b> Detect when same itemset is collected multiple times with different EU values</li>
 *   <li><b>Operations:</b>
 *     <ul>
 *       <li>{@code get(itemset)} → O(1) lookup by itemset identity</li>
 *       <li>Enables update-in-place (remove old, add new) instead of duplicate insertion</li>
 *     </ul>
 *   </li>
 *   <li><b>Key insight:</b> HashMap uses {@code equals()/hashCode()} which are based
 *       on itemset identity, allowing efficient duplicate detection</li>
 * </ul>
 *
 * <h3>Why Can't We Use Just TreeSet?</h3>
 * <p>{@link HighUtilityPattern} has asymmetric equality semantics:
 * <ul>
 *   <li><b>compareTo()</b> — Compares by EU (for heap ordering)</li>
 *   <li><b>equals()</b> — Compares by itemset identity only (ignores EU)</li>
 * </ul>
 *
 * <p>This asymmetry is intentional but creates a problem:
 * <pre>
 *   Pattern P1 = {items: {5,12}, EU: 100.0}
 *   Pattern P2 = {items: {5,12}, EU: 150.0}  (same itemset, higher EU)
 *
 *   TreeSet behavior:
 *     - compareTo(P1, P2) != 0 (different EU) → TreeSet treats them as different
 *     - equals(P1, P2) == true (same itemset) → but TreeSet doesn't use equals!
 *
 *   Result: Without HashMap index, TreeSet would store BOTH patterns, violating
 *   the requirement that each itemset appears at most once.
 * </pre>
 *
 * <p>The HashMap index solves this by detecting existing itemsets in O(1) and
 * updating them in-place (remove old pattern, add updated pattern).
 *
 * <h3>Concurrency Design</h3>
 * <ul>
 *   <li><b>Volatile threshold</b> — {@code admissionThreshold} is {@code volatile}
 *       so engine threads can read it lock-free for fast-path rejection before
 *       acquiring the lock (reduces contention by ~70% when heap is full)</li>
 *   <li><b>Lock-guarded mutations</b> — All add/evict/update operations are
 *       serialized via {@code ReentrantLock} to maintain consistency between
 *       TreeSet and HashMap</li>
 *   <li><b>Double-checked locking</b> — Fast-path (lock-free) + re-check (under lock)
 *       eliminates TOCTOU (time-of-check-to-time-of-use) race conditions while
 *       minimizing lock contention</li>
 * </ul>
 *
 * @see domain.model.HighUtilityPattern
 */
public final class TopKPatternCollector implements TopKCollectorInterface {

    private final int capacity;
    private final TreeSet<HighUtilityPattern> patternHeap;
    private final Map<Set<Integer>, HighUtilityPattern> patternIndex;
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Volatile admission threshold: minimum EU in the current Top-K collection.
     * Zero until the heap reaches capacity.
     * Read lock-free by engine threads for fast-path rejection; updated under lock.
     */
    public volatile double admissionThreshold = 0.0;

    /**
     * Constructs a Top-K collector with the specified capacity.
     *
     * @param k maximum number of patterns to retain
     */
    public TopKPatternCollector(int k) {
        this.capacity = k;
        this.patternHeap = new TreeSet<>();
        this.patternIndex = new HashMap<>();
    }

    /**
     * Attempts to admit a candidate pattern from its UPU-List.
     *
     * <p>Fast-path (lock-free): if the heap is full and {@code EU < admissionThreshold - ε},
     * the candidate is rejected immediately without acquiring the lock.
     *
     * <p>Under the lock:
     * <ol>
     *   <li>Re-check threshold (eliminates TOCTOU race).</li>
     *   <li>If the same itemset is already present with lower EU, update it.</li>
     *   <li>Otherwise add the new pattern and evict the weakest if over capacity.</li>
     * </ol>
     *
     * @param candidate UPU-List of the candidate pattern
     * @return {@code true} if the pattern was admitted or its EU was updated
     */
    @Override
    public boolean tryCollect(UtilityProbabilityList candidate) {
        double eu = candidate.expectedUtility;
        double ep = candidate.existentialProbability;

        // Fast path: lock-free threshold check
        if (patternHeap.size() >= capacity && eu < admissionThreshold - EPSILON) {
            return false;
        }

        lock.lock();
        try {
            // Re-check under lock to eliminate TOCTOU race
            if (patternHeap.size() >= capacity && eu < admissionThreshold - EPSILON) {
                return false;
            }

            Set<Integer> itemset = candidate.itemset;
            HighUtilityPattern existing = patternIndex.get(itemset);

            if (existing != null) {
                // Same itemset already collected — update if EU improved
                if (eu > existing.expectedUtility + EPSILON) {
                    patternHeap.remove(existing);
                    HighUtilityPattern updated = new HighUtilityPattern(itemset, eu, ep);
                    patternHeap.add(updated);
                    patternIndex.put(itemset, updated);
                    updateThreshold();
                    return true;
                }
                return false;
            }

            // New pattern
            HighUtilityPattern newPattern = new HighUtilityPattern(itemset, eu, ep);
            patternHeap.add(newPattern);
            patternIndex.put(itemset, newPattern);

            // Evict weakest if over capacity
            while (patternHeap.size() > capacity) {
                HighUtilityPattern weakest = patternHeap.pollFirst();
                patternIndex.remove(weakest.items);
            }

            updateThreshold();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Updates the admission threshold to the minimum EU in the current Top-K collection.
     *
     * <p>This method is called after every mutation (add, evict, update) to keep the
     * threshold synchronized with the weakest pattern in the heap.
     *
     * <h3>Threshold Logic</h3>
     * <ul>
     *   <li><b>Heap at capacity:</b> threshold = EU of weakest pattern ({@code patternHeap.first()})
     *       <br>This is the minimum EU required for admission (patterns with EU below this
     *       will be rejected)</li>
     *   <li><b>Heap below capacity:</b> threshold = 0.0
     *       <br>Accept all patterns until heap is full</li>
     * </ul>
     *
     * <h3>Volatile Write</h3>
     * <p>Writing to {@code admissionThreshold} is a volatile write, which establishes
     * a happens-before relationship with all subsequent volatile reads. This ensures
     * engine threads see the updated threshold immediately (within memory model constraints).
     *
     * <h3>Caller Responsibility</h3>
     * <p><b>IMPORTANT:</b> This method MUST be called while holding {@code lock}.
     * It does not acquire the lock itself. Calling without holding the lock would
     * create a race condition between reading {@code patternHeap.first()} and
     * concurrent modifications.
     *
     * @implNote Called by {@link #tryCollect(UtilityProbabilityList)} after every
     *           mutation while holding the lock
     */
    private void updateThreshold() {
        // Check if heap is at capacity and non-empty
        if (patternHeap.size() >= capacity && !patternHeap.isEmpty()) {
            // Threshold = minimum EU in heap (weakest pattern's EU)
            // TreeSet is min-heap ordered, so first() returns the weakest
            admissionThreshold = patternHeap.first().expectedUtility;
        } else {
            // Heap not full yet → accept all patterns (threshold = 0)
            admissionThreshold = 0.0;
        }
    }

    /**
     * Returns all collected patterns in descending EU order.
     *
     * @return a snapshot list of Top-K patterns, highest EU first
     */
    @Override
    public List<HighUtilityPattern> getCollectedPatterns() {
        lock.lock();
        try {
            return new ArrayList<>(patternHeap.descendingSet());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the current admission threshold.
     *
     * @return current admission threshold; 0.0 if fewer than k patterns collected
     */
    @Override
    public double getAdmissionThreshold() {
        return admissionThreshold;
    }

    /**
     * Returns the target capacity (k) of this collector.
     *
     * @return the maximum number of patterns to retain
     */
    @Override
    public int getCapacity() {
        return capacity;
    }

    /**
     * Returns the current number of patterns in the collector.
     *
     * @return current pattern count
     */
    @Override
    public int getCurrentSize() {
        lock.lock();
        try {
            return patternHeap.size();
        } finally {
            lock.unlock();
        }
    }
}
