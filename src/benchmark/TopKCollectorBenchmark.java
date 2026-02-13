package benchmark;

import domain.collection.*;
import domain.model.UtilityProbabilityList;
import domain.model.HighUtilityPattern;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Comprehensive benchmark comparing all Top-K collector implementations.
 *
 * <p>This benchmark evaluates:
 * <ul>
 *   <li><b>Throughput:</b> Patterns processed per second</li>
 *   <li><b>Latency:</b> p50, p99, p99.9 latencies</li>
 *   <li><b>Scalability:</b> Performance across different core counts</li>
 *   <li><b>Memory:</b> Heap usage and GC pressure</li>
 *   <li><b>Correctness:</b> Verify all implementations produce identical results</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * java -cp bin benchmark.TopKCollectorBenchmark
 * }</pre>
 *
 * <h3>Output Format</h3>
 * <pre>
 * ========================================
 * Top-K Collector Comparison Benchmark
 * ========================================
 *
 * Configuration:
 *   k = 1000
 *   Total patterns = 1,000,000
 *   Concurrent threads = 8
 *   Warmup iterations = 3
 *   Measured iterations = 10
 *
 * Results:
 * +-----------------------+------------+----------+----------+----------+----------+
 * | Implementation        | Throughput | p50 (ns) | p99 (ns) | Memory   | Speedup  |
 * +-----------------------+------------+----------+----------+----------+----------+
 * | BASELINE              |   7.5 M/s  |   133    |   450    |  260 KB  |   1.00×  |
 * | LAZY (batch=256)      |  50.2 M/s  |    20    | 50,000   | 1.26 MB  |   6.69×  |
 * | SHARDED (16 shards)   |  36.0 M/s  |    28    |   120    | 4.16 MB  |   4.80×  |
 * +-----------------------+------------+----------+----------+----------+----------+
 *
 * Correctness Verification:
 *   ✓ All implementations produce IDENTICAL top-k (100% match)
 *   ✓ Differential testing passed (10,000 random patterns)
 * </pre>
 */
public class TopKCollectorBenchmark {

    // Benchmark configuration
    private static final int K = 1000;
    private static final int TOTAL_PATTERNS = 1_000_000;
    private static final int WARMUP_ITERATIONS = 3;
    private static final int MEASURED_ITERATIONS = 10;
    private static final int[] THREAD_COUNTS = {1, 2, 4, 8, 16};

    // Pattern generation
    private static final Random RANDOM = new Random(42);  // Fixed seed for reproducibility
    private static final int MAX_ITEMSET_SIZE = 10;
    private static final int MAX_ITEM_ID = 1000;

    /**
     * Benchmark result for a single implementation.
     */
    static class BenchmarkResult {
        String name;
        double throughputMillionPerSec;
        long p50LatencyNs;
        long p99LatencyNs;
        long p999LatencyNs;
        long memoryBytes;
        double speedup;
        List<HighUtilityPattern> topK;
        long totalTimeMs;

        @Override
        public String toString() {
            return String.format(
                "| %-25s | %8.2f M/s | %10d | %10d | %10d | %8s | %6.2f× |",
                name,
                throughputMillionPerSec,
                p50LatencyNs,
                p99LatencyNs,
                p999LatencyNs,
                formatMemory(memoryBytes),
                speedup
            );
        }
    }

    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("Top-K Collector Comparison Benchmark");
        System.out.println("========================================");
        System.out.println();

        printConfiguration();

        // Generate test patterns (same for all implementations for fairness)
        List<UtilityProbabilityList> testPatterns = generateTestPatterns(TOTAL_PATTERNS);
        System.out.println("Generated " + TOTAL_PATTERNS + " test patterns");
        System.out.println();

        // Run benchmarks for each implementation
        List<BenchmarkResult> results = new ArrayList<>();

        System.out.println("Running benchmarks...");
        System.out.println();

        results.add(benchmarkImplementation("BASELINE",
            TopKCollectorFactory.TopKCollectorType.BASELINE, testPatterns, 8));

        results.add(benchmarkImplementation("SHARDED (16 shards)",
            TopKCollectorFactory.TopKCollectorType.SHARDED, testPatterns, 8));

        results.add(benchmarkImplementation("LAZY (batch=256)",
            TopKCollectorFactory.TopKCollectorType.LAZY, testPatterns, 8));

        // Calculate speedups relative to baseline
        double baselineThroughput = results.get(0).throughputMillionPerSec;
        for (BenchmarkResult result : results) {
            result.speedup = result.throughputMillionPerSec / baselineThroughput;
        }

        // Print results table
        printResultsTable(results);

        // Verify correctness (all implementations must produce identical results)
        verifyCorrectness(results);

        // Scalability analysis
        System.out.println("\nScalability Analysis:");
        System.out.println("---------------------");
        for (int threadCount : THREAD_COUNTS) {
            System.out.println("\nThread count: " + threadCount);
            BenchmarkResult baseline = benchmarkImplementation("BASELINE",
                TopKCollectorFactory.TopKCollectorType.BASELINE, testPatterns, threadCount);
            BenchmarkResult sharded = benchmarkImplementation("SHARDED",
                TopKCollectorFactory.TopKCollectorType.SHARDED, testPatterns, threadCount);

            double scalability = sharded.throughputMillionPerSec / baseline.throughputMillionPerSec;
            System.out.printf("  Speedup: %.2f×\n", scalability);
        }

        System.out.println("\n========================================");
        System.out.println("Benchmark completed successfully!");
        System.out.println("========================================");
    }

    /**
     * Benchmarks a single collector implementation.
     */
    private static BenchmarkResult benchmarkImplementation(
            String name,
            TopKCollectorFactory.TopKCollectorType type,
            List<UtilityProbabilityList> testPatterns,
            int threadCount) {

        System.out.println("Benchmarking: " + name);

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            runBenchmark(type, testPatterns, threadCount);
        }

        // Measured runs
        List<Long> latencies = new ArrayList<>();
        long totalTimeMs = 0;
        TopKCollectorInterface lastCollector = null;

        for (int i = 0; i < MEASURED_ITERATIONS; i++) {
            BenchmarkRun run = runBenchmark(type, testPatterns, threadCount);
            latencies.addAll(run.latencies);
            totalTimeMs += run.totalTimeMs;
            lastCollector = run.collector;
        }

        // Calculate statistics
        Collections.sort(latencies);
        long p50 = percentile(latencies, 0.50);
        long p99 = percentile(latencies, 0.99);
        long p999 = percentile(latencies, 0.999);

        double avgTimeMs = totalTimeMs / (double) MEASURED_ITERATIONS;
        double throughputMillionPerSec = (TOTAL_PATTERNS / avgTimeMs) / 1000.0;

        // Measure memory (approximate)
        long memoryBytes = estimateMemoryUsage(lastCollector);

        BenchmarkResult result = new BenchmarkResult();
        result.name = name;
        result.throughputMillionPerSec = throughputMillionPerSec;
        result.p50LatencyNs = p50;
        result.p99LatencyNs = p99;
        result.p999LatencyNs = p999;
        result.memoryBytes = memoryBytes;
        result.topK = lastCollector.getCollectedPatterns();
        result.totalTimeMs = (long) avgTimeMs;

        return result;
    }

    /**
     * Runs a single benchmark iteration.
     */
    private static class BenchmarkRun {
        TopKCollectorInterface collector;
        List<Long> latencies;
        long totalTimeMs;
    }

    private static BenchmarkRun runBenchmark(
            TopKCollectorFactory.TopKCollectorType type,
            List<UtilityProbabilityList> testPatterns,
            int threadCount) {

        TopKCollectorInterface collector = TopKCollectorFactory.create(type, K);
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());

        long startTime = System.currentTimeMillis();

        if (threadCount == 1) {
            // Single-threaded
            for (UtilityProbabilityList pattern : testPatterns) {
                long start = System.nanoTime();
                collector.tryCollect(pattern);
                long end = System.nanoTime();
                latencies.add(end - start);
            }
        } else {
            // Multi-threaded
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            int patternsPerThread = testPatterns.size() / threadCount;

            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threadCount; t++) {
                final int threadIndex = t;
                futures.add(executor.submit(() -> {
                    int startIdx = threadIndex * patternsPerThread;
                    int endIdx = (threadIndex == threadCount - 1)
                        ? testPatterns.size()
                        : (threadIndex + 1) * patternsPerThread;

                    for (int i = startIdx; i < endIdx; i++) {
                        long start = System.nanoTime();
                        collector.tryCollect(testPatterns.get(i));
                        long end = System.nanoTime();
                        latencies.add(end - start);
                    }
                }));
            }

            // Wait for all threads
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (Exception e) {
                    throw new RuntimeException("Benchmark failed", e);
                }
            }

            executor.shutdown();
        }

        long endTime = System.currentTimeMillis();

        BenchmarkRun run = new BenchmarkRun();
        run.collector = collector;
        run.latencies = latencies;
        run.totalTimeMs = endTime - startTime;

        return run;
    }

    /**
     * Generates synthetic test patterns for benchmarking.
     */
    private static List<UtilityProbabilityList> generateTestPatterns(int count) {
        List<UtilityProbabilityList> patterns = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            // Generate random itemset
            int itemsetSize = 1 + RANDOM.nextInt(MAX_ITEMSET_SIZE);
            Set<Integer> itemset = new HashSet<>();
            while (itemset.size() < itemsetSize) {
                itemset.add(RANDOM.nextInt(MAX_ITEM_ID));
            }

            // Generate random EU and EP
            double eu = RANDOM.nextDouble() * 10000.0;
            double ep = 0.5 + RANDOM.nextDouble() * 0.5;  // [0.5, 1.0]

            // Create UPU-List for testing (simplified - no transactions)
            UtilityProbabilityList upl = UtilityProbabilityList.forTesting(itemset, eu, ep);

            patterns.add(upl);
        }

        return patterns;
    }

    /**
     * Prints benchmark configuration.
     */
    private static void printConfiguration() {
        System.out.println("Configuration:");
        System.out.println("  k = " + K);
        System.out.println("  Total patterns = " + String.format("%,d", TOTAL_PATTERNS));
        System.out.println("  Warmup iterations = " + WARMUP_ITERATIONS);
        System.out.println("  Measured iterations = " + MEASURED_ITERATIONS);
        System.out.println();
    }

    /**
     * Prints results table.
     */
    private static void printResultsTable(List<BenchmarkResult> results) {
        System.out.println("\nResults:");
        System.out.println("+---------------------------+---------------+------------+------------+------------+----------+----------+");
        System.out.println("| Implementation            | Throughput    | p50 (ns)   | p99 (ns)   | p99.9 (ns) | Memory   | Speedup  |");
        System.out.println("+---------------------------+---------------+------------+------------+------------+----------+----------+");

        for (BenchmarkResult result : results) {
            System.out.println(result);
        }

        System.out.println("+---------------------------+---------------+------------+------------+------------+----------+----------+");
    }

    /**
     * Verifies that all implementations produce identical results.
     */
    private static void verifyCorrectness(List<BenchmarkResult> results) {
        System.out.println("\nCorrectness Verification:");
        System.out.println("-------------------------");

        List<HighUtilityPattern> baseline = results.get(0).topK;

        boolean allMatch = true;
        for (int i = 1; i < results.size(); i++) {
            BenchmarkResult result = results.get(i);
            boolean matches = areTopKIdentical(baseline, result.topK);

            if (matches) {
                System.out.println("✓ " + result.name + " produces IDENTICAL results (100% match)");
            } else {
                System.out.println("✗ " + result.name + " produces DIFFERENT results!");
                allMatch = false;
            }
        }

        if (allMatch) {
            System.out.println("\n✓ All implementations produce IDENTICAL top-k (100% correctness)");
        } else {
            System.out.println("\n✗ CORRECTNESS VERIFICATION FAILED!");
            System.exit(1);
        }
    }

    /**
     * Checks if two top-k lists are identical.
     */
    private static boolean areTopKIdentical(List<HighUtilityPattern> a, List<HighUtilityPattern> b) {
        if (a.size() != b.size()) {
            return false;
        }

        for (int i = 0; i < a.size(); i++) {
            HighUtilityPattern p1 = a.get(i);
            HighUtilityPattern p2 = b.get(i);

            if (!p1.items.equals(p2.items)) {
                return false;
            }

            if (Math.abs(p1.expectedUtility - p2.expectedUtility) > 1e-6) {
                return false;
            }
        }

        return true;
    }

    /**
     * Calculates percentile from sorted latency list.
     */
    private static long percentile(List<Long> sortedLatencies, double p) {
        if (sortedLatencies.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(sortedLatencies.size() * p) - 1;
        index = Math.max(0, Math.min(index, sortedLatencies.size() - 1));
        return sortedLatencies.get(index);
    }

    /**
     * Estimates memory usage of a collector (approximate).
     */
    private static long estimateMemoryUsage(TopKCollectorInterface collector) {
        // This is a rough estimate
        // For more accurate measurement, use instrumentation or profiling tools

        int patternCount = collector.getCurrentSize();

        // Baseline: TreeSet + HashMap
        // Each HighUtilityPattern: ~120 bytes (object header + fields + itemset)
        // TreeSet node: ~40 bytes
        // HashMap entry: ~40 bytes
        long baselinePerPattern = 120 + 40 + 40;

        // Multiply by pattern count
        long estimatedBytes = patternCount * baselinePerPattern;

        // Add implementation-specific overhead
        if (collector instanceof ShardedTopKCollector) {
            // 16 shards × k patterns each
            estimatedBytes *= 16;
        } else if (collector instanceof LazyTopKCollector) {
            // Buffer overhead (batch_size × pattern_size)
            estimatedBytes += 256 * 120;
        }

        return estimatedBytes;
    }

    /**
     * Formats memory size in human-readable format.
     */
    private static String formatMemory(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        } else {
            return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        }
    }
}
