package domain.collection;

/**
 * Factory for creating Top-K pattern collectors with different implementations.
 *
 * <p>This factory enables easy switching between alternative Top-K collection strategies
 * for performance comparison and evaluation. All implementations guarantee <b>100% exactness</b>
 * (identical results to baseline).
 *
 * @see TopKCollectorInterface
 * @see TopKPatternCollector
 * @see ShardedTopKCollector
 * @see LazyTopKCollector
 */
public final class TopKCollectorFactory {

    /**
     * Environment variable for configuring collector type.
     */
    private static final String ENV_COLLECTOR_TYPE = "TOPK_COLLECTOR_TYPE";

    /**
     * System property for configuring collector type.
     */
    private static final String PROP_COLLECTOR_TYPE = "topk.collector.type";

    /**
     * Default collector type if not configured.
     */
    private static final TopKCollectorType DEFAULT_TYPE = TopKCollectorType.BASELINE;

    /**
     * Enumeration of available Top-K collector implementations.
     */
    public enum TopKCollectorType {
        /**
         * Baseline dual-structure min-heap (TreeSet + HashMap).
         * Reference implementation for correctness verification.
         */
        BASELINE,

        /**
         * Distributed sharding with parallel collectors.
         * Best for 16-32 core systems.
         */
        SHARDED,

        /**
         * Lazy batching with amortized updates.
         * Best for high-throughput, bursty workloads.
         */
        LAZY
    }

    /**
     * Configuration for collector creation.
     */
    public static class CollectorConfig {
        /**
         * Collector type to create.
         */
        public TopKCollectorType type = DEFAULT_TYPE;

        /**
         * Maximum number of patterns to retain (k).
         */
        public int capacity;

        /**
         * Number of shards (for SHARDED type).
         * Default: number of available processors.
         */
        public int numShards = Runtime.getRuntime().availableProcessors();

        /**
         * Batch size (for LAZY type).
         * Default: 256.
         */
        public int batchSize = 256;

        public CollectorConfig(int capacity) {
            this.capacity = capacity;
        }

        public CollectorConfig withType(TopKCollectorType type) {
            this.type = type;
            return this;
        }

        public CollectorConfig withNumShards(int numShards) {
            this.numShards = numShards;
            return this;
        }

        public CollectorConfig withBatchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }
    }

    // Private constructor to prevent instantiation
    private TopKCollectorFactory() {
        throw new UnsupportedOperationException("Factory class cannot be instantiated");
    }

    /**
     * Creates a Top-K collector using the default type (or configured via environment).
     *
     * @param capacity maximum number of patterns to retain (k)
     * @return a new Top-K collector instance
     */
    public static TopKCollectorInterface create(int capacity) {
        TopKCollectorType type = getConfiguredType();
        return create(type, capacity);
    }

    /**
     * Creates a Top-K collector of the specified type.
     *
     * @param type the collector type to create
     * @param capacity maximum number of patterns to retain (k)
     * @return a new Top-K collector instance
     */
    public static TopKCollectorInterface create(TopKCollectorType type, int capacity) {
        CollectorConfig config = new CollectorConfig(capacity);
        config.type = type;
        return create(config);
    }

    /**
     * Creates a Top-K collector with detailed configuration.
     *
     * @param config the collector configuration
     * @return a new Top-K collector instance
     */
    public static TopKCollectorInterface create(CollectorConfig config) {
        switch (config.type) {
            case BASELINE:
                return createBaseline(config.capacity);

            case SHARDED:
                return createSharded(config.capacity, config.numShards);

            case LAZY:
                return createLazy(config.capacity, config.batchSize);

            default:
                throw new IllegalArgumentException("Unknown collector type: " + config.type);
        }
    }

    /**
     * Creates a baseline Top-K collector.
     *
     * @param capacity maximum number of patterns to retain
     * @return baseline collector instance
     */
    private static TopKCollectorInterface createBaseline(int capacity) {
        return new TopKPatternCollector(capacity);
    }

    /**
     * Creates a sharded Top-K collector.
     *
     * @param capacity maximum number of patterns to retain
     * @param numShards number of parallel shards
     * @return sharded collector instance
     */
    private static TopKCollectorInterface createSharded(int capacity, int numShards) {
        return new ShardedTopKCollector(capacity, numShards);
    }

    /**
     * Creates a lazy Top-K collector.
     *
     * @param capacity maximum number of patterns to retain
     * @param batchSize batch size for flushing
     * @return lazy collector instance
     */
    private static TopKCollectorInterface createLazy(int capacity, int batchSize) {
        return new LazyTopKCollector(capacity, batchSize);
    }

    /**
     * Gets the configured collector type from environment or system properties.
     *
     * @return configured collector type, or default if not configured
     */
    private static TopKCollectorType getConfiguredType() {
        // Check environment variable first
        String envType = System.getenv(ENV_COLLECTOR_TYPE);
        if (envType != null && !envType.isEmpty()) {
            try {
                return TopKCollectorType.valueOf(envType.toUpperCase());
            } catch (IllegalArgumentException e) {
                System.err.println("Invalid collector type in environment: " + envType);
            }
        }

        // Check system property
        String propType = System.getProperty(PROP_COLLECTOR_TYPE);
        if (propType != null && !propType.isEmpty()) {
            try {
                return TopKCollectorType.valueOf(propType.toUpperCase());
            } catch (IllegalArgumentException e) {
                System.err.println("Invalid collector type in system property: " + propType);
            }
        }

        // Return default
        return DEFAULT_TYPE;
    }
}
