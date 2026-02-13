package cli;

import application.MiningConfiguration;
import application.MiningOrchestrator;
import domain.collection.TopKCollectorFactory;
import domain.model.HighUtilityPattern;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Locale;

/**
 * Focused comparison benchmark for PTK-HUIM (PLOS ONE format).
 *
 * <p>Compares four dimensions independently:
 * <ol>
 *   <li><b>Parallelism Comparison</b> - DFS + TwoPointer + variant parallel modes</li>
 *   <li><b>Join Strategy Comparison</b> - DFS + Full Parallel + variant join strategies</li>
 *   <li><b>Traversal Strategy Comparison</b> - TwoPointer + Full Parallel + variant search strategies</li>
 *   <li><b>TopK Collector Comparison</b> - DFS + TwoPointer + Full Parallel + variant collectors</li>
 * </ol>
 *
 * <p><b>Multi-K Support:</b> The benchmark can run comparisons across multiple k values in a single
 * execution. Results are organized in separate directories per k value for easy analysis.
 *
 * <h3>Usage</h3>
 * <pre>
 *   java cli.ComparisonBenchmark &lt;database&gt; &lt;profits&gt; &lt;k_values&gt; &lt;minProb&gt; &lt;dataset_name&gt; &lt;comparison_type&gt; [output_dir]
 * </pre>
 *
 * <h3>Comparison Types</h3>
 * <ul>
 *   <li><b>PARALLELISM</b> - Compare parallelism modes (DFS + TwoPointer fixed)</li>
 *   <li><b>JOIN</b> - Compare join strategies (DFS + Full Parallel fixed)</li>
 *   <li><b>TRAVERSAL</b> - Compare search strategies (TwoPointer + Full Parallel fixed)</li>
 *   <li><b>TOPK</b> - Compare TopK collectors (DFS + TwoPointer + Full Parallel fixed)</li>
 *   <li><b>ALL</b> - Run all four comparisons</li>
 * </ul>
 *
 * <h3>Examples</h3>
 * <pre>
 *   # Single k value - Compare parallelism modes with k=100
 *   java cli.ComparisonBenchmark data/mushroom.txt data/profits.txt 100 0.1 MUSHROOM PARALLELISM
 *
 *   # Multiple k values - Compare join strategies with k=100,500,1000
 *   java cli.ComparisonBenchmark data/mushroom.txt data/profits.txt 100,500,1000 0.1 MUSHROOM JOIN
 *
 *   # Multiple k values - Compare traversal strategies
 *   java cli.ComparisonBenchmark data/mushroom.txt data/profits.txt 50,100,200 0.1 MUSHROOM TRAVERSAL
 *
 *   # Run all comparisons with multiple k values
 *   java cli.ComparisonBenchmark data/mushroom.txt data/profits.txt 100,500,1000 0.1 MUSHROOM ALL
 * </pre>
 *
 * <h3>Output Structure</h3>
 * <pre>
 *   benchmark_results/
 *     MUSHROOM/
 *       PARALLELISM/
 *         k100/
 *           PARALLELISM_MUSHROOM_k100_0p70_20250210_143022.txt
 *         k500/
 *           PARALLELISM_MUSHROOM_k500_0p70_20250210_143045.txt
 *         k1000/
 *           PARALLELISM_MUSHROOM_k1000_0p70_20250210_143108.txt
 * </pre>
 */
public final class ComparisonBenchmark {

    // =========================================================================
    // Configuration
    // =========================================================================

    private static final int WARMUP_RUNS = 2;
    private static final int MEASURED_RUNS = 5;
    private static final String DEFAULT_OUTPUT_DIR = "benchmark_results";

    private enum ComparisonType {
        PARALLELISM,  // Compare parallel modes
        JOIN,         // Compare join strategies
        TRAVERSAL,    // Compare search strategies
        TOPK,         // Compare TopK collectors
        ALL           // All comparisons
    }

    private static final class BenchmarkConfig {
        final String name;
        final MiningConfiguration.SearchStrategy searchStrategy;
        final MiningConfiguration.JoinStrategy joinStrategy;
        final boolean phase1aParallel;
        final boolean phase1dParallel;
        final boolean phase3Parallel;
        final TopKCollectorFactory.TopKCollectorType collectorType;

        BenchmarkConfig(String name,
                       MiningConfiguration.SearchStrategy searchStrategy,
                       MiningConfiguration.JoinStrategy joinStrategy,
                       boolean phase1aParallel,
                       boolean phase1dParallel,
                       boolean phase3Parallel) {
            this(name, searchStrategy, joinStrategy, phase1aParallel, phase1dParallel, phase3Parallel,
                TopKCollectorFactory.TopKCollectorType.BASELINE);
        }

        BenchmarkConfig(String name,
                       MiningConfiguration.SearchStrategy searchStrategy,
                       MiningConfiguration.JoinStrategy joinStrategy,
                       boolean phase1aParallel,
                       boolean phase1dParallel,
                       boolean phase3Parallel,
                       TopKCollectorFactory.TopKCollectorType collectorType) {
            this.name = name;
            this.searchStrategy = searchStrategy;
            this.joinStrategy = joinStrategy;
            this.phase1aParallel = phase1aParallel;
            this.phase1dParallel = phase1dParallel;
            this.phase3Parallel = phase3Parallel;
            this.collectorType = collectorType;
        }
    }

    private static final class BenchmarkResult {
        final BenchmarkConfig config;
        final long[] runTimes;
        final int patternCount;
        final double memoryMB;
        final double meanTime;
        final double medianTime;
        final double stdDevTime;
        final long minTime;
        final long maxTime;
        final HighUtilityPattern topPattern;      // Rank 1 pattern
        final HighUtilityPattern lastPattern;     // Rank k pattern

        BenchmarkResult(BenchmarkConfig config, long[] runTimes, int patternCount, double memoryMB,
                       HighUtilityPattern topPattern, HighUtilityPattern lastPattern) {
            this.config = config;
            this.runTimes = runTimes.clone();
            this.patternCount = patternCount;
            this.memoryMB = memoryMB;
            this.topPattern = topPattern;
            this.lastPattern = lastPattern;
            this.meanTime = calculateMean(runTimes);
            this.medianTime = calculateMedian(runTimes);
            this.stdDevTime = calculateStdDev(runTimes, meanTime);
            this.minTime = Arrays.stream(runTimes).min().orElse(0);
            this.maxTime = Arrays.stream(runTimes).max().orElse(0);
        }

        /**
         * Calculates the arithmetic mean (average) of run times.
         *
         * <p>Formula: mean = (Σ values) / n
         *
         * <p>The mean is the primary metric for reporting performance in publication.
         * It represents the expected value of execution time across multiple runs.
         *
         * @param values array of measured run times in milliseconds
         * @return arithmetic mean, or 0.0 if array is empty
         */
        private static double calculateMean(long[] values) {
            return Arrays.stream(values).average().orElse(0.0);
        }

        /**
         * Calculates the median (middle value) of run times.
         *
         * <p>Formula for odd n: median = sorted[n/2]
         * <br>Formula for even n: median = (sorted[n/2-1] + sorted[n/2]) / 2
         *
         * <p>The median is more robust to outliers than the mean. If one run is
         * significantly slower due to JVM garbage collection or OS scheduling,
         * the median will be less affected than the mean.
         *
         * <p>For benchmark reporting, use median when std dev > 10% of mean.
         *
         * @param values array of measured run times in milliseconds
         * @return median value
         */
        private static double calculateMedian(long[] values) {
            long[] sorted = values.clone();
            Arrays.sort(sorted);
            int n = sorted.length;
            return (n % 2 == 0) ? (sorted[n/2 - 1] + sorted[n/2]) / 2.0 : sorted[n/2];
        }

        /**
         * Calculates the population standard deviation of run times.
         *
         * <p>Formula: σ = √(Σ(x - μ)² / n)
         *
         * <p>Standard deviation measures the variability/spread of run times.
         * Low std dev (< 5% of mean) indicates stable, reproducible measurements.
         * High std dev (> 10% of mean) suggests interference from external factors
         * (GC pauses, CPU throttling, OS background tasks).
         *
         * <p><b>Statistical validity guidelines:</b>
         * <ul>
         *   <li>✅ Excellent: σ/μ < 0.05 (5%)</li>
         *   <li>⚠️ Acceptable: 0.05 ≤ σ/μ ≤ 0.10 (5-10%)</li>
         *   <li>❌ Rerun needed: σ/μ > 0.10 (> 10%)</li>
         * </ul>
         *
         * @param values array of measured run times in milliseconds
         * @param mean   pre-computed mean (avoids recalculation)
         * @return population standard deviation in milliseconds
         */
        private static double calculateStdDev(long[] values, double mean) {
            double variance = 0.0;
            for (long value : values) variance += Math.pow(value - mean, 2);
            return Math.sqrt(variance / values.length);
        }
    }

    // =========================================================================
    // Main Entry Point
    // =========================================================================

    public static void main(String[] args) {
        if (args.length < 6) {
            printUsage();
            System.exit(1);
        }

        try {
            String databasePath = args[0];
            String profitsPath = args[1];
            String kValues = args[2];  // Now accepts comma-separated list: "100,500,1000"
            double minProb = Double.parseDouble(args[3]);
            String datasetName = args[4];
            ComparisonType comparisonType = ComparisonType.valueOf(args[5].toUpperCase());
            String outputDir = args.length >= 7 ? args[6] : DEFAULT_OUTPUT_DIR;

            // Parse k values (comma-separated)
            int[] kArray = parseKValues(kValues);

            // Run comparison for each k value
            for (int k : kArray) {
                System.out.println();
                System.out.println("=".repeat(80));
                System.out.printf("Running benchmark for K = %d%n", k);
                System.out.println("=".repeat(80));
                runComparison(databasePath, profitsPath, k, minProb, datasetName, comparisonType, outputDir);
            }

        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            printUsage();
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Parses k values from comma-separated string.
     * Examples: "100" -> [100], "100,500,1000" -> [100, 500, 1000]
     */
    private static int[] parseKValues(String kString) {
        String[] parts = kString.split(",");
        int[] values = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = Integer.parseInt(parts[i].trim());
            if (values[i] <= 0) {
                throw new IllegalArgumentException("K must be positive: " + values[i]);
            }
        }
        return values;
    }

    private static void printUsage() {
        System.err.println("Usage: java cli.ComparisonBenchmark <database> <profits> <k_values> <minProb> <dataset_name> <comparison_type> [output_dir]");
        System.err.println();
        System.err.println("Arguments:");
        System.err.println("  database         Path to transaction database file");
        System.err.println("  profits          Path to profit table file");
        System.err.println("  k_values         Number of top patterns (comma-separated for multiple)");
        System.err.println("                   Examples: \"100\" or \"100,500,1000\"");
        System.err.println("  minProb          Minimum probability threshold (0.0-1.0)");
        System.err.println("  dataset_name     Name for this dataset (e.g., MUSHROOM, CHESS)");
        System.err.println("  comparison_type  PARALLELISM, JOIN, TRAVERSAL, TOPK, or ALL");
        System.err.println("  output_dir       Output directory (optional, default: benchmark_results)");
        System.err.println();
        System.err.println("Comparison Types:");
        System.err.println("  PARALLELISM  - Compare: Full Parallel, Full Sequential, Phase3 Only, etc.");
        System.err.println("                 Fixed: DFS + TwoPointer + BASELINE collector");
        System.err.println();
        System.err.println("  JOIN         - Compare: TwoPointer, ExponentialSearch, BinarySearch");
        System.err.println("                 Fixed: DFS + Full Parallel + BASELINE collector");
        System.err.println();
        System.err.println("  TRAVERSAL    - Compare: DFS, BEST_FIRST, BREADTH_FIRST, IDDFS");
        System.err.println("                 Fixed: TwoPointer + Full Parallel + BASELINE collector");
        System.err.println();
        System.err.println("  TOPK         - Compare: BASELINE, SHARDED, LAZY collectors");
        System.err.println("                 Fixed: DFS + TwoPointer + Full Parallel");
        System.err.println();
        System.err.println("  ALL          - Run all four comparisons");
        System.err.println();
        System.err.println("Examples:");
        System.err.println("  # Single k value");
        System.err.println("  java cli.ComparisonBenchmark data/mushroom.txt data/profits.txt 100 0.1 MUSHROOM PARALLELISM");
        System.err.println();
        System.err.println("  # Multiple k values");
        System.err.println("  java cli.ComparisonBenchmark data/mushroom.txt data/profits.txt 100,500,1000 0.1 MUSHROOM JOIN");
        System.err.println("  java cli.ComparisonBenchmark data/mushroom.txt data/profits.txt 50,100,200 0.1 MUSHROOM TRAVERSAL");
    }

    // =========================================================================
    // Comparison Execution
    // =========================================================================

    private static void runComparison(String databasePath, String profitsPath,
                                     int k, double minProb, String datasetName,
                                     ComparisonType comparisonType, String outputDir) throws IOException {

        printHeader(datasetName, k, minProb, comparisonType);

        // Load data once
        System.out.println("Loading dataset...");
        DataLoader loader = new DataLoader();
        DataLoader.MiningData data = loader.loadAll(databasePath, profitsPath);
        System.out.printf("Loaded %d transactions%n", data.getTransactionCount());
        System.out.println();

        // Run comparison(s)
        if (comparisonType == ComparisonType.ALL) {
            runSingleComparison("PARALLELISM", createParallelismConfigs(), data, k, minProb, datasetName, outputDir);
            System.out.println();
            runSingleComparison("JOIN", createJoinConfigs(), data, k, minProb, datasetName, outputDir);
            System.out.println();
            runSingleComparison("TRAVERSAL", createTraversalConfigs(), data, k, minProb, datasetName, outputDir);
            System.out.println();
            runSingleComparison("TOPK", createTopKConfigs(), data, k, minProb, datasetName, outputDir);
        } else {
            List<BenchmarkConfig> configs = getConfigsForComparison(comparisonType);
            runSingleComparison(comparisonType.toString(), configs, data, k, minProb, datasetName, outputDir);
        }

        System.out.println();
        System.out.println("=".repeat(80));
        System.out.println("Benchmark Complete! Results saved to: " + outputDir);
        System.out.println("=".repeat(80));
    }

    private static List<BenchmarkConfig> getConfigsForComparison(ComparisonType type) {
        switch (type) {
            case PARALLELISM: return createParallelismConfigs();
            case JOIN: return createJoinConfigs();
            case TRAVERSAL: return createTraversalConfigs();
            case TOPK: return createTopKConfigs();
            default: throw new IllegalArgumentException("Unknown comparison type: " + type);
        }
    }

    /**
     * Comparison 1: DFS + TwoPointer + variant parallel modes.
     */
    private static List<BenchmarkConfig> createParallelismConfigs() {
        List<BenchmarkConfig> configs = new ArrayList<>();

        // Full Parallel (production)
        configs.add(new BenchmarkConfig(
            "Full_Parallel",
            MiningConfiguration.SearchStrategy.DFS,
            MiningConfiguration.JoinStrategy.TWO_POINTER,
            true, true, true
        ));

        // Full Sequential (baseline)
        configs.add(new BenchmarkConfig(
            "Full_Sequential",
            MiningConfiguration.SearchStrategy.DFS,
            MiningConfiguration.JoinStrategy.TWO_POINTER,
            false, false, false
        ));

        // Only Phase 3 parallel (common hybrid)
        configs.add(new BenchmarkConfig(
            "Phase3_Only_Parallel",
            MiningConfiguration.SearchStrategy.DFS,
            MiningConfiguration.JoinStrategy.TWO_POINTER,
            false, false, true
        ));

        // Only Phase 1a sequential (test Phase 1a impact)
        configs.add(new BenchmarkConfig(
            "Phase1a_Sequential",
            MiningConfiguration.SearchStrategy.DFS,
            MiningConfiguration.JoinStrategy.TWO_POINTER,
            false, true, true
        ));

        // Only Phase 1d sequential (test Phase 1d impact)
        configs.add(new BenchmarkConfig(
            "Phase1d_Sequential",
            MiningConfiguration.SearchStrategy.DFS,
            MiningConfiguration.JoinStrategy.TWO_POINTER,
            true, false, true
        ));

        return configs;
    }

    /**
     * Comparison 2: DFS + Full Parallel + variant join strategies.
     */
    private static List<BenchmarkConfig> createJoinConfigs() {
        List<BenchmarkConfig> configs = new ArrayList<>();

        configs.add(new BenchmarkConfig(
            "TwoPointer",
            MiningConfiguration.SearchStrategy.DFS,
            MiningConfiguration.JoinStrategy.TWO_POINTER,
            true, true, true
        ));

        configs.add(new BenchmarkConfig(
            "ExponentialSearch",
            MiningConfiguration.SearchStrategy.DFS,
            MiningConfiguration.JoinStrategy.EXPONENTIAL_SEARCH,
            true, true, true
        ));

        configs.add(new BenchmarkConfig(
            "BinarySearch",
            MiningConfiguration.SearchStrategy.DFS,
            MiningConfiguration.JoinStrategy.BINARY_SEARCH,
            true, true, true
        ));

        return configs;
    }

    /**
     * Comparison 3: TwoPointer + Full Parallel + variant traversal strategies.
     */
    private static List<BenchmarkConfig> createTraversalConfigs() {
        List<BenchmarkConfig> configs = new ArrayList<>();

        configs.add(new BenchmarkConfig(
            "DFS",
            MiningConfiguration.SearchStrategy.DFS,
            MiningConfiguration.JoinStrategy.TWO_POINTER,
            true, true, true
        ));

        configs.add(new BenchmarkConfig(
            "BEST_FIRST",
            MiningConfiguration.SearchStrategy.BEST_FIRST,
            MiningConfiguration.JoinStrategy.TWO_POINTER,
            true, true, true
        ));

        configs.add(new BenchmarkConfig(
            "BREADTH_FIRST",
            MiningConfiguration.SearchStrategy.BREADTH_FIRST,
            MiningConfiguration.JoinStrategy.TWO_POINTER,
            true, true, true
        ));

        configs.add(new BenchmarkConfig(
            "IDDFS",
            MiningConfiguration.SearchStrategy.IDDFS,
            MiningConfiguration.JoinStrategy.TWO_POINTER,
            true, true, true
        ));

        return configs;
    }

    /**
     * Comparison 4: DFS + TwoPointer + Full Parallel + variant TopK collectors.
     */
    private static List<BenchmarkConfig> createTopKConfigs() {
        List<BenchmarkConfig> configs = new ArrayList<>();

        configs.add(new BenchmarkConfig(
            "BASELINE",
            MiningConfiguration.SearchStrategy.DFS,
            MiningConfiguration.JoinStrategy.TWO_POINTER,
            true, true, true,
            TopKCollectorFactory.TopKCollectorType.BASELINE
        ));

        configs.add(new BenchmarkConfig(
            "SHARDED",
            MiningConfiguration.SearchStrategy.DFS,
            MiningConfiguration.JoinStrategy.TWO_POINTER,
            true, true, true,
            TopKCollectorFactory.TopKCollectorType.SHARDED
        ));

        configs.add(new BenchmarkConfig(
            "LAZY",
            MiningConfiguration.SearchStrategy.DFS,
            MiningConfiguration.JoinStrategy.TWO_POINTER,
            true, true, true,
            TopKCollectorFactory.TopKCollectorType.LAZY
        ));

        return configs;
    }

    private static void runSingleComparison(String comparisonName, List<BenchmarkConfig> configs,
                                           DataLoader.MiningData data, int k, double minProb,
                                           String datasetName, String outputDir) throws IOException {

        System.out.println("=".repeat(80));
        System.out.printf("Comparison: %s (%d configurations)%n", comparisonName, configs.size());
        System.out.println("=".repeat(80));
        System.out.println();

        List<BenchmarkResult> results = new ArrayList<>();

        for (int i = 0; i < configs.size(); i++) {
            BenchmarkConfig config = configs.get(i);

            System.out.printf("[%d/%d] %s%n", i + 1, configs.size(), config.name);

            BenchmarkResult result = runConfigBenchmark(config, data, k, minProb);
            results.add(result);

            printResultSummary(result);
            System.out.println();
        }

        // Verify pattern consistency
        System.out.println("-".repeat(80));
        System.out.println("Pattern Verification");
        System.out.println("-".repeat(80));
        verifyPatternConsistency(results, k);
        System.out.println();

        // Save to TXT
        String txtPath = saveComparisonTXT(comparisonName, results, datasetName, k, minProb, outputDir);
        System.out.printf("Results saved to: %s%n", txtPath);
    }

    private static BenchmarkResult runConfigBenchmark(BenchmarkConfig config,
                                                     DataLoader.MiningData data,
                                                     int k, double minProb) {
        // Warmup
        System.out.printf("  Warmup: ");
        for (int i = 0; i < WARMUP_RUNS; i++) {
            System.out.printf("%d ", i + 1);
            System.out.flush();
            runSingleIteration(config, data, k, minProb);
            System.gc();
            try { Thread.sleep(100); } catch (InterruptedException e) { }
        }
        System.out.println("✓");

        // Measured runs
        long[] runTimes = new long[MEASURED_RUNS];
        int patternCount = 0;
        double memoryMB = 0.0;
        HighUtilityPattern topPattern = null;
        HighUtilityPattern lastPattern = null;

        System.out.printf("  Measured: ");
        for (int i = 0; i < MEASURED_RUNS; i++) {
            System.out.printf("%d ", i + 1);
            System.out.flush();

            System.gc();
            try { Thread.sleep(100); } catch (InterruptedException e) { }

            RunResult result = runSingleIteration(config, data, k, minProb);
            runTimes[i] = result.timeMs;
            patternCount = result.patternCount;
            memoryMB = result.memoryMB;
            // Capture patterns from last measured run
            if (i == MEASURED_RUNS - 1) {
                topPattern = result.topPattern;
                lastPattern = result.lastPattern;
            }
        }
        System.out.println("✓");

        return new BenchmarkResult(config, runTimes, patternCount, memoryMB, topPattern, lastPattern);
    }

    private static final class RunResult {
        final long timeMs;
        final int patternCount;
        final double memoryMB;
        final HighUtilityPattern topPattern;
        final HighUtilityPattern lastPattern;

        RunResult(long timeMs, int patternCount, double memoryMB,
                 HighUtilityPattern topPattern, HighUtilityPattern lastPattern) {
            this.timeMs = timeMs;
            this.patternCount = patternCount;
            this.memoryMB = memoryMB;
            this.topPattern = topPattern;
            this.lastPattern = lastPattern;
        }
    }

    private static RunResult runSingleIteration(BenchmarkConfig config,
                                               DataLoader.MiningData data,
                                               int k, double minProb) {
        MiningConfiguration miningConfig = new MiningConfiguration.Builder()
            .setK(k)
            .setMinProbability(minProb)
            .setDebugMode(false)
            .setSearchStrategy(config.searchStrategy)
            .setJoinStrategy(config.joinStrategy)
            .setCollectorType(config.collectorType)
            .setUseParallelPhase1a(config.phase1aParallel)
            .setUseParallelUPUListBuilding(config.phase1dParallel)
            .setUseParallelMining(config.phase3Parallel)
            .build();

        MiningOrchestrator orchestrator = new MiningOrchestrator(miningConfig, data.getProfitTable());

        long startTime = System.currentTimeMillis();
        List<HighUtilityPattern> patterns = orchestrator.mine(data.getProfitTable(), data.getDatabase());
        long endTime = System.currentTimeMillis();

        Runtime runtime = Runtime.getRuntime();
        double memoryMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0);

        // Capture top and last patterns for verification
        HighUtilityPattern topPattern = patterns.isEmpty() ? null : patterns.get(0);
        HighUtilityPattern lastPattern = patterns.isEmpty() ? null : patterns.get(patterns.size() - 1);

        return new RunResult(endTime - startTime, patterns.size(), memoryMB, topPattern, lastPattern);
    }

    // =========================================================================
    // Output
    // =========================================================================

    private static void printHeader(String datasetName, int k, double minProb, ComparisonType type) {
        System.out.println("=".repeat(80));
        System.out.println("PTK-HUIM Comparison Benchmark (PLOS ONE Format)");
        System.out.println("=".repeat(80));
        System.out.printf("Dataset:         %s%n", datasetName);
        System.out.printf("K:               %d%n", k);
        System.out.printf("Min Prob:        %.4f%n", minProb);
        System.out.printf("Comparison:      %s%n", type);
        System.out.printf("Processors:      %d%n", Runtime.getRuntime().availableProcessors());
        System.out.printf("Warmup runs:     %d%n", WARMUP_RUNS);
        System.out.printf("Measured runs:   %d%n", MEASURED_RUNS);
        System.out.println("=".repeat(80));
        System.out.println();
    }

    private static void printResultSummary(BenchmarkResult result) {
        System.out.printf("  Patterns: %d | Mean: %.2f ms | Median: %.2f ms | StdDev: %.2f ms%n",
            result.patternCount, result.meanTime, result.medianTime, result.stdDevTime);
    }

    /**
     * Verifies that all configurations produce identical top-1 and last patterns.
     * Prints verification results to console.
     */
    private static void verifyPatternConsistency(List<BenchmarkResult> results, int k) {
        if (results.isEmpty()) {
            System.out.println("No results to verify.");
            return;
        }

        // Get reference patterns from first configuration
        BenchmarkResult reference = results.get(0);
        String refTopItemset = formatItemset(reference.topPattern);
        String refTopEU = formatEU(reference.topPattern);
        String refLastItemset = formatItemset(reference.lastPattern);
        String refLastEU = formatEU(reference.lastPattern);

        System.out.println("Reference Configuration: " + reference.config.name);
        System.out.printf("  Rank 1:  %s  EU=%-12s  EP=%.6f%n",
            refTopItemset, refTopEU,
            reference.topPattern != null ? reference.topPattern.getExistentialProbability() : 0.0);
        System.out.printf("  Rank %d: %s  EU=%-12s  EP=%.6f%n",
            k, refLastItemset, refLastEU,
            reference.lastPattern != null ? reference.lastPattern.getExistentialProbability() : 0.0);
        System.out.println();

        // Compare all other configurations
        boolean allMatch = true;
        for (int i = 1; i < results.size(); i++) {
            BenchmarkResult result = results.get(i);
            String topItemset = formatItemset(result.topPattern);
            String topEU = formatEU(result.topPattern);
            String lastItemset = formatItemset(result.lastPattern);
            String lastEU = formatEU(result.lastPattern);

            boolean topMatch = refTopItemset.equals(topItemset) && refTopEU.equals(topEU);
            boolean lastMatch = refLastItemset.equals(lastItemset) && refLastEU.equals(lastEU);

            System.out.printf("%-25s  Rank 1: %s  Rank %d: %s%n",
                result.config.name,
                topMatch ? "✓ MATCH" : "✗ MISMATCH",
                k,
                lastMatch ? "✓ MATCH" : "✗ MISMATCH");

            if (!topMatch) {
                System.out.printf("  Expected: %s  EU=%s%n", refTopItemset, refTopEU);
                System.out.printf("  Got:      %s  EU=%s%n", topItemset, topEU);
                allMatch = false;
            }
            if (!lastMatch) {
                System.out.printf("  Expected: %s  EU=%s%n", refLastItemset, refLastEU);
                System.out.printf("  Got:      %s  EU=%s%n", lastItemset, lastEU);
                allMatch = false;
            }
        }

        System.out.println();
        if (allMatch) {
            System.out.println("✓ All configurations produce IDENTICAL results!");
        } else {
            System.out.println("✗ WARNING: Configurations produce DIFFERENT results!");
        }
    }

    private static String formatItemset(HighUtilityPattern pattern) {
        if (pattern == null) return "null";
        Set<Integer> itemset = pattern.getItems();
        List<Integer> sorted = new ArrayList<>(itemset);
        Collections.sort(sorted);
        return sorted.toString();
    }

    private static String formatEU(HighUtilityPattern pattern) {
        if (pattern == null) return "0.0000";
        return String.format(Locale.US, "%.4f", pattern.getExpectedUtility());
    }

    private static String saveComparisonTXT(String comparisonName, List<BenchmarkResult> results,
                                          String datasetName, int k, double minProb,
                                          String outputDir) throws IOException {
        // Create organized output directory structure: outputDir/datasetName/comparisonName/k<value>/
        File dir = new File(outputDir, datasetName + "/" + comparisonName + "/k" + k);
        if (!dir.exists()) dir.mkdirs();

        // Generate filename with proper decimal notation
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String probStr = String.format(Locale.US, "%.2f", minProb).replace(".", "p");
        String filename = String.format("%s_%s_k%d_%s_%s.txt",
            comparisonName, datasetName, k, probStr, timestamp);
        String filepath = new File(dir, filename).getPath();

        // Write TXT with nice formatting
        try (PrintWriter writer = new PrintWriter(new FileWriter(filepath))) {
            // Header
            writer.println("================================================================================");
            writer.println("PTK-HUIM Comparison Benchmark Results");
            writer.println("================================================================================");
            writer.println();

            // Metadata
            writer.println("BENCHMARK CONFIGURATION");
            writer.println("  Comparison Type:     " + comparisonName);
            writer.println("  Dataset:             " + datasetName);
            writer.println("  K (top patterns):    " + k);
            writer.println(String.format(Locale.US, "  Min Probability:     %.4f", minProb));
            writer.println("  Processors:          " + Runtime.getRuntime().availableProcessors());
            writer.println("  Warmup Runs:         " + WARMUP_RUNS);
            writer.println("  Measured Runs:       " + MEASURED_RUNS);
            writer.println("  Timestamp:           " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
            writer.println();

            writer.println("================================================================================");
            writer.println("RESULTS");
            writer.println("================================================================================");
            writer.println();

            // Results for each configuration
            for (int i = 0; i < results.size(); i++) {
                BenchmarkResult result = results.get(i);

                writer.println(String.format("[%d/%d] %s", i + 1, results.size(), result.config.name));
                writer.println("  " + "-".repeat(76));
                writer.println(String.format("  Patterns Found:      %d", result.patternCount));
                writer.println(String.format(Locale.US, "  Memory Usage:        %.2f MB", result.memoryMB));
                writer.println();

                writer.println("  Individual Run Times (ms):");
                for (int j = 0; j < result.runTimes.length; j++) {
                    writer.println(String.format("    Run %d: %d ms", j + 1, result.runTimes[j]));
                }
                writer.println();

                writer.println("  Statistical Summary:");
                writer.println(String.format(Locale.US, "    Mean:              %.2f ms", result.meanTime));
                writer.println(String.format(Locale.US, "    Median:            %.2f ms", result.medianTime));
                writer.println(String.format(Locale.US, "    Std Dev:           %.2f ms", result.stdDevTime));
                writer.println(String.format("    Min:               %d ms", result.minTime));
                writer.println(String.format("    Max:               %d ms", result.maxTime));
                writer.println(String.format(Locale.US, "    CV (Coef. Var.):   %.2f%%",
                    (result.stdDevTime / result.meanTime) * 100));
                writer.println();
            }

            writer.println("================================================================================");
            writer.println("SUMMARY TABLE");
            writer.println("================================================================================");
            writer.println();

            // Summary table header
            writer.println(String.format("%-25s %10s %12s %12s %12s %12s",
                "Configuration", "Patterns", "Mean (ms)", "Median (ms)", "StdDev (ms)", "CV (%)"));
            writer.println("-".repeat(88));

            // Summary table rows
            for (BenchmarkResult result : results) {
                double cv = (result.stdDevTime / result.meanTime) * 100;
                writer.println(String.format(Locale.US, "%-25s %10d %12.2f %12.2f %12.2f %11.2f%%",
                    result.config.name,
                    result.patternCount,
                    result.meanTime,
                    result.medianTime,
                    result.stdDevTime,
                    cv));
            }

            writer.println();
            writer.println("================================================================================");
            writer.println("Note: CV (Coefficient of Variation) = (StdDev / Mean) × 100%");
            writer.println("      CV < 5% indicates excellent reproducibility");
            writer.println("      CV 5-10% indicates acceptable variability");
            writer.println("      CV > 10% may indicate need for more runs or system interference");
            writer.println("================================================================================");
            writer.println();

            // Pattern verification section
            writer.println("================================================================================");
            writer.println("PATTERN VERIFICATION");
            writer.println("================================================================================");
            writer.println();

            if (!results.isEmpty()) {
                // Get reference patterns from first configuration
                BenchmarkResult reference = results.get(0);
                String refTopItemset = formatItemset(reference.topPattern);
                String refTopEU = formatEU(reference.topPattern);
                String refLastItemset = formatItemset(reference.lastPattern);
                String refLastEU = formatEU(reference.lastPattern);

                writer.println("Reference Configuration: " + reference.config.name);
                writer.println(String.format("  Rank 1:  %s  EU=%-12s  EP=%.6f",
                    refTopItemset, refTopEU,
                    reference.topPattern != null ? reference.topPattern.getExistentialProbability() : 0.0));
                writer.println(String.format("  Rank %d: %s  EU=%-12s  EP=%.6f",
                    k, refLastItemset, refLastEU,
                    reference.lastPattern != null ? reference.lastPattern.getExistentialProbability() : 0.0));
                writer.println();

                // Compare all other configurations
                boolean allMatch = true;
                for (int i = 1; i < results.size(); i++) {
                    BenchmarkResult result = results.get(i);
                    String topItemset = formatItemset(result.topPattern);
                    String topEU = formatEU(result.topPattern);
                    String lastItemset = formatItemset(result.lastPattern);
                    String lastEU = formatEU(result.lastPattern);

                    boolean topMatch = refTopItemset.equals(topItemset) && refTopEU.equals(topEU);
                    boolean lastMatch = refLastItemset.equals(lastItemset) && refLastEU.equals(lastEU);

                    writer.println(String.format("%-25s  Rank 1: %s  Rank %d: %s",
                        result.config.name,
                        topMatch ? "✓ MATCH" : "✗ MISMATCH",
                        k,
                        lastMatch ? "✓ MATCH" : "✗ MISMATCH"));

                    if (!topMatch) {
                        writer.println(String.format("  Expected: %s  EU=%s", refTopItemset, refTopEU));
                        writer.println(String.format("  Got:      %s  EU=%s", topItemset, topEU));
                        allMatch = false;
                    }
                    if (!lastMatch) {
                        writer.println(String.format("  Expected: %s  EU=%s", refLastItemset, refLastEU));
                        writer.println(String.format("  Got:      %s  EU=%s", lastItemset, lastEU));
                        allMatch = false;
                    }
                }

                writer.println();
                if (allMatch) {
                    writer.println("✓ All configurations produce IDENTICAL results!");
                } else {
                    writer.println("✗ WARNING: Configurations produce DIFFERENT results!");
                }
            } else {
                writer.println("No results to verify.");
            }

            writer.println();
            writer.println("================================================================================");
        }

        return filepath;
    }
}
