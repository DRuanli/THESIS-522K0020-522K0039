package application;

/**
 * Configuration constants for the mining orchestrator.
 *
 * <p>Centralizes all tuning parameters that control ForkJoin parallelism,
 * task granularity, and work-stealing behavior. These values are optimized
 * for typical multi-core systems (4-16 cores) and medium-to-large datasets
 * (10K-1M transactions).
 *
 * <h3>Tuning guidelines</h3>
 * <ul>
 *   <li><b>LEAF_SIZE</b>: Larger values reduce task overhead but decrease work-stealing
 *       granularity. 256 is optimal for most workloads.</li>
 *   <li><b>FINE_GRAIN_THRESHOLD</b>: Controls when prefix mining switches from binary
 *       splitting to per-item task creation. Lower values increase parallelism but
 *       add overhead.</li>
 * </ul>
 */
public final class OrchestratorConfiguration {

    // =========================================================================
    // Parallelism Configuration
    // =========================================================================

    /**
     * Default ForkJoin parallelism level.
     *
     * <p>Set to the number of available processors. The JVM automatically creates
     * worker threads matching this count. For CPU-bound workloads like pattern mining,
     * this value provides optimal throughput without thread thrashing.
     */
    public static final int DEFAULT_PARALLELISM = Runtime.getRuntime().availableProcessors();

    // =========================================================================
    // Task Granularity Configuration
    // =========================================================================

    /**
     * Maximum transactions per leaf task in Phase-1a (PTWU/EP computation)
     * and Phase-1d (UPU-List entry collection).
     *
     * <p>Each ForkJoin task processes at most {@code LEAF_SIZE} transactions before
     * returning. Smaller values increase parallelism but add task creation overhead.
     * Larger values reduce overhead but decrease work-stealing opportunities.
     *
     * <p><b>Benchmark results</b>: 256 provides the best balance for datasets
     * with 10K-1M transactions on 4-16 core systems.
     */
    public static final int LEAF_SIZE = 256;

    /**
     * Threshold for fine-grain task decomposition in Phase-3 prefix mining.
     *
     * <p>When a prefix range has {@code ≤ FINE_GRAIN_THRESHOLD} items, the task
     * splits into one subtask per prefix (instead of binary splitting) to maximize
     * work-stealing granularity. This is critical for load balancing when prefix
     * PTWU values are highly skewed (some prefixes have far more work than others).
     *
     * <p><b>Benchmark results</b>: 16 provides good load balancing without excessive
     * task overhead. Lower values (e.g., 8) may help for very skewed PTWU distributions.
     */
    public static final int FINE_GRAIN_THRESHOLD = 16;

    // =========================================================================
    // Validation Constants
    // =========================================================================

    /**
     * Minimum acceptable value for k (number of top patterns).
     *
     * <p>Values below 1 are meaningless — the algorithm must return at least
     * one pattern (if any exist).
     */
    public static final int MIN_K_VALUE = 1;

    /**
     * Minimum acceptable probability threshold.
     *
     * <p>Probabilities are in the range [0, 1]. Zero is allowed (no EP filtering),
     * though values {@code < 0.01} are rare in practice.
     */
    public static final double MIN_PROBABILITY = 0.0;

    /**
     * Maximum acceptable probability threshold.
     *
     * <p>Probabilities cannot exceed 1.0 (certainty).
     */
    public static final double MAX_PROBABILITY = 1.0;

    // =========================================================================
    // Memory Estimation Constants
    // =========================================================================

    /**
     * Bytes per megabyte for memory reporting.
     */
    public static final double BYTES_PER_MB = 1024.0 * 1024.0;

    /**
     * Milliseconds per second for time reporting.
     */
    public static final double MS_PER_SECOND = 1000.0;

    // =========================================================================
    // Private Constructor
    // =========================================================================

    /**
     * Private constructor to prevent instantiation.
     *
     * <p>This is a pure utility class with only static constants.
     */
    private OrchestratorConfiguration() {
        throw new AssertionError("Utility class — do not instantiate");
    }
}
