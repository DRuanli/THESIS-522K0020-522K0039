package domain.collection;

import domain.model.HighUtilityPattern;
import domain.model.UtilityProbabilityList;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.ReentrantLock;

import static infrastructure.util.NumericalConstants.EPSILON;

/**
 * Lazy Top-K collector with batching for high-throughput scenarios.
 *
 * <h3>Design: Lazy Batching with Amortized Updates</h3>
 * <ul>
 *   <li><b>Fast path:</b> Lock-free buffering with conservative threshold check</li>
 *   <li><b>Slow path:</b> Periodic batch processing with exact admission logic</li>
 *   <li><b>Exactness:</b> All buffered candidates eventually processed exactly (no loss)</li>
 * </ul>
 *
 * <h3>Correctness Guarantee (100% Exact)</h3>
 * <p><b>Theorem:</b> LazyTopKCollector produces identical results to baseline.
 *
 * <p><b>Proof:</b>
 * <ol>
 *   <li>Conservative threshold (≤ true threshold) never rejects patterns that
 *       baseline would accept</li>
 *   <li>During flush(), each buffered pattern processed with exact baseline logic</li>
 *   <li>Final heap state = baseline heap state ✓</li>
 * </ol>
 *
 * <p><b>Key insight:</b> Timing differs (immediate vs. batched), but final result
 * is IDENTICAL.
 *
 * @see TopKPatternCollector
 * @see TopKCollectorInterface
 */
public final class LazyTopKCollector implements TopKCollectorInterface {

    /**
     * Default batch size for flushing buffer.
     * Tuned for cache-line alignment (64 bytes × 256 = 16 KB, fits L1 cache).
     */
    private static final int DEFAULT_BATCH_SIZE = 256;

    /**
     * Conservative threshold multiplier for fast-path rejection.
     * Using 0.95 ensures we never reject patterns that baseline would accept.
     */
    private static final double THRESHOLD_SAFETY_MARGIN = 0.95;

    private final int capacity;
    private final int batchSize;

    /**
     * Exact collector for batch processing.
     * All buffered candidates are processed through this collector to ensure exactness.
     */
    private final TopKPatternCollector exactCollector;

    /**
     * Lock-free buffer for fast-path insertions.
     * Candidates are buffered here until batch size is reached.
     */
    private final ConcurrentLinkedQueue<UtilityProbabilityList> buffer;

    /**
     * Lock for batch processing (flush operation).
     * Ensures only one thread processes the buffer at a time.
     */
    private final ReentrantLock flushLock = new ReentrantLock();

    /**
     * Conservative admission threshold for fast-path rejection.
     * Always ≤ true threshold to ensure we never incorrectly reject patterns.
     */
    private volatile double conservativeThreshold = 0.0;

    /**
     * Constructs a lazy Top-K collector with specified batch size.
     *
     * @param k maximum number of patterns to retain
     * @param batchSize number of candidates to buffer before flushing
     */
    public LazyTopKCollector(int k, int batchSize) {
        this.capacity = k;
        this.batchSize = batchSize;
        this.exactCollector = new TopKPatternCollector(k);
        this.buffer = new ConcurrentLinkedQueue<>();
    }

    /**
     * Convenience constructor using default batch size.
     *
     * @param k maximum number of patterns to retain
     */
    public LazyTopKCollector(int k) {
        this(k, DEFAULT_BATCH_SIZE);
    }

    /**
     * Attempts to collect a candidate pattern using lazy batching.
     *
     * <p><b>Fast path (lock-free):</b>
     * <ol>
     *   <li>Check EU against conservative threshold (with safety margin)</li>
     *   <li>If rejected, return immediately (no lock, no buffer)</li>
     *   <li>Otherwise, add to buffer (lock-free queue)</li>
     * </ol>
     *
     * <p><b>Slow path (batched):</b>
     * <ol>
     *   <li>If buffer reaches batch size, trigger flush</li>
     *   <li>Flush drains buffer and processes all candidates exactly</li>
     *   <li>Update conservative threshold from exact collector</li>
     * </ol>
     *
     * <p><b>Exactness:</b> All buffered candidates are eventually processed through
     * {@code exactCollector.tryCollect()}, which uses identical logic to baseline.
     *
     * @param candidate UPU-List of the candidate pattern
     * @return {@code true} if the pattern was buffered (may be admitted later)
     */
    @Override
    public boolean tryCollect(UtilityProbabilityList candidate) {
        double eu = candidate.expectedUtility;

        // Fast-path rejection: conservative threshold check (lock-free)
        if (eu < conservativeThreshold * THRESHOLD_SAFETY_MARGIN - EPSILON) {
            return false;
        }

        // Buffer candidate (lock-free)
        buffer.offer(candidate);

        // Check if batch size reached (amortized check)
        if (buffer.size() >= batchSize) {
            // Try to flush (non-blocking - only one thread flushes)
            if (flushLock.tryLock()) {
                try {
                    flush();
                } finally {
                    flushLock.unlock();
                }
            }
            // If another thread is flushing, skip (our candidate is in buffer)
        }

        return true;  // Buffered (exact processing deferred)
    }

    /**
     * Returns all collected patterns after flushing the buffer.
     *
     * <p><b>Two-phase process:</b>
     * <ol>
     *   <li>Flush buffer to process all pending candidates exactly</li>
     *   <li>Return exact top-k from underlying collector</li>
     * </ol>
     *
     * <p><b>Exactness guarantee:</b> Since we flush before returning, this method
     * produces identical results to calling baseline {@code tryCollect()} on all
     * candidates in the same order.
     *
     * @return exact top-k patterns, highest EU first
     */
    @Override
    public List<HighUtilityPattern> getCollectedPatterns() {
        // Ensure all buffered candidates are processed
        flushLock.lock();
        try {
            flush();
            return exactCollector.getCollectedPatterns();
        } finally {
            flushLock.unlock();
        }
    }

    /**
     * Returns the current admission threshold from the exact collector.
     *
     * <p><b>Note:</b> This value may be slightly stale during buffering (before flush).
     * The conservative threshold used in {@link #tryCollect(UtilityProbabilityList)}
     * accounts for this staleness with a safety margin.
     *
     * @return current admission threshold; 0.0 if fewer than k patterns collected
     */
    @Override
    public double getAdmissionThreshold() {
        return exactCollector.admissionThreshold;
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
     * Returns the current number of patterns in the exact collector.
     *
     * <p><b>Note:</b> This does NOT include buffered candidates that haven't been
     * processed yet. The true total is {@code getCurrentSize() + buffer.size()}.
     *
     * @return current pattern count in exact collector (excludes buffer)
     */
    @Override
    public int getCurrentSize() {
        return exactCollector.getCollectedPatterns().size();
    }

    /**
     * Processes all buffered candidates through the exact collector.
     *
     * <p><b>Exact processing:</b> Each candidate is processed through
     * {@code exactCollector.tryCollect()}, which uses identical admission logic
     * to the baseline collector.
     *
     * <p><b>Complexity:</b> O(b log k) where b = buffer size
     * <ul>
     *   <li>Drain buffer: O(b)</li>
     *   <li>Process each candidate: O(log k) per pattern</li>
     *   <li>Update threshold: O(1)</li>
     * </ul>
     *
     * <p><b>Amortization:</b> Called every {@code batchSize} insertions, so
     * amortized cost per insertion is O(log k) / batchSize.
     *
     * <p><b>Caller responsibility:</b> Must hold {@code flushLock} when calling.
     */
    private void flush() {
        // Drain buffer (lock-free queue → local list)
        List<UtilityProbabilityList> candidates = new ArrayList<>();
        UtilityProbabilityList candidate;
        while ((candidate = buffer.poll()) != null) {
            candidates.add(candidate);
        }

        // Process each candidate exactly (same logic as baseline)
        for (UtilityProbabilityList c : candidates) {
            exactCollector.tryCollect(c);
        }

        // Update conservative threshold for future fast-path rejections
        updateConservativeThreshold();
    }

    /**
     * Updates the conservative threshold from the exact collector.
     *
     * <p><b>Conservative guarantee:</b> We use the exact threshold directly
     * (no multiplication) because we apply the safety margin in {@code tryCollect()}.
     * This ensures the cached value is always ≤ true threshold.
     *
     * <p><b>Staleness:</b> Between flushes, this value may be stale (lower than
     * current threshold), but this is safe: it only affects pruning efficiency,
     * not correctness.
     */
    private void updateConservativeThreshold() {
        conservativeThreshold = exactCollector.admissionThreshold;
    }

    /**
     * Returns the current buffer size (pending candidates).
     *
     * <p>Useful for monitoring and debugging batching behavior.
     *
     * @return number of candidates in buffer (not yet processed)
     */
    public int getBufferSize() {
        return buffer.size();
    }

    /**
     * Returns diagnostic information about batching behavior.
     *
     * <p>Useful for tuning batch size and understanding performance characteristics.
     *
     * @return map with keys: "buffer_size", "exact_size", "threshold", "conservative_threshold"
     */
    public Map<String, Object> getDiagnostics() {
        Map<String, Object> diagnostics = new HashMap<>();
        diagnostics.put("buffer_size", buffer.size());
        diagnostics.put("exact_size", exactCollector.getCollectedPatterns().size());
        diagnostics.put("threshold", exactCollector.admissionThreshold);
        diagnostics.put("conservative_threshold", conservativeThreshold);
        diagnostics.put("batch_size", batchSize);
        return diagnostics;
    }
}
