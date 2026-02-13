package cli;

import application.MiningConfiguration;
import application.MiningOrchestrator;
import application.OrchestratorConfiguration;
import domain.model.HighUtilityPattern;
import domain.model.ProfitTable;
import domain.model.Transaction;

import java.io.IOException;
import java.io.PrintStream;
import java.io.FileOutputStream;
import java.util.List;

/**
 * Command-line entry point for the PTK-HUIM miner.
 *
 * <h3>Usage</h3>
 * <pre>
 *   java cli.CommandLineInterface &lt;database&gt; &lt;profits&gt; &lt;k&gt; &lt;minProb&gt; [options]
 * </pre>
 *
 * <h3>Required positional arguments</h3>
 * <ol>
 *   <li>{@code database} — path to the transaction database file</li>
 *   <li>{@code profits}  — path to the profit table file</li>
 *   <li>{@code k}        — number of top patterns to return (positive integer)</li>
 *   <li>{@code minProb}  — minimum existential probability threshold ({@code ∈ [0, 1]})</li>
 * </ol>
 *
 * <h3>Optional flags</h3>
 * <p>See {@link ArgumentParser} for full list of optional flags.
 *
 * <h3>Output</h3>
 * <ul>
 *   <li>Results are printed to stdout via {@link ResultFormatter} (or to a file if --output is used)</li>
 *   <li>Debug output goes to stderr (if --debug is enabled)</li>
 *   <li>Exit code is 0 on success, non-zero on error</li>
 * </ul>
 *
 * @see ArgumentParser
 * @see MiningOrchestrator
 * @see ResultFormatter
 */
public final class CommandLineInterface {

    /**
     * Main entry point.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        try {
            execute(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Argument Error: " + e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println("I/O Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Unexpected error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Executes the mining workflow.
     *
     * @param args command-line arguments
     * @throws IOException if data files cannot be loaded
     * @throws IllegalArgumentException if arguments are invalid
     */
    private static void execute(String[] args) throws IOException {
        // Parse arguments
        ArgumentParser parser = new ArgumentParser();
        parser.parse(args);

        // Handle --help flag
        if (parser.isHelpRequested()) {
            parser.printHelp();
            return;
        }

        MiningConfiguration config = parser.buildConfiguration();

        logStartup(config, parser);

        // Load data
        DataLoader.MiningData data = loadData(config, parser);

        logDataLoaded(config, data);

        // Execute mining
        MiningResult result = executeMining(config, data);

        // Display results
        displayResults(parser, result);
    }

    /**
     * Logs startup information if debug mode is enabled.
     *
     * @param config mining configuration
     * @param parser argument parser with parsed values
     */
    private static void logStartup(MiningConfiguration config, ArgumentParser parser) {
        if (config.isDebugMode()) {
            System.err.println("[CLI] Starting PTK-HUIM mining...");
            System.err.printf("[CLI] Parameters: k=%d, minProb=%.4f, strategy=%s, join=%s%n",
                parser.getK(),
                parser.getMinProbability(),
                parser.getSearchStrategy(),
                parser.getJoinStrategy());
        }
    }

    /**
     * Loads mining data from files.
     *
     * @param config mining configuration
     * @param parser argument parser with file paths
     * @return loaded mining data
     * @throws IOException if files cannot be loaded
     */
    private static DataLoader.MiningData loadData(
            MiningConfiguration config,
            ArgumentParser parser) throws IOException {

        DataLoader loader = new DataLoader();

        if (config.isDebugMode()) {
            System.err.printf("[CLI] Loading database from: %s%n", parser.getDatabaseFile());
            System.err.printf("[CLI] Loading profit table from: %s%n", parser.getProfitFile());
        }

        return loader.loadAll(parser.getDatabaseFile(), parser.getProfitFile());
    }

    /**
     * Logs data loading information if debug mode is enabled.
     *
     * @param config mining configuration
     * @param data   loaded mining data
     */
    private static void logDataLoaded(MiningConfiguration config, DataLoader.MiningData data) {
        if (config.isDebugMode()) {
            System.err.printf("[CLI] Database size: %d transactions%n", data.getTransactionCount());
            System.err.printf("[CLI] Profit table size: %d items%n", data.getItemCount());
        }
    }

    /**
     * Executes the mining algorithm.
     *
     * @param config mining configuration
     * @param data   loaded mining data
     * @return mining result with patterns, time, and memory
     */
    private static MiningResult executeMining(
            MiningConfiguration config,
            DataLoader.MiningData data) {

        long startTime = System.currentTimeMillis();

        MiningOrchestrator orchestrator = new MiningOrchestrator(
            config, data.getProfitTable());

        List<HighUtilityPattern> patterns = orchestrator.mine(
            data.getProfitTable(), data.getDatabase());

        long endTime = System.currentTimeMillis();
        long executionTime = endTime - startTime;

        double memoryUsedMB = measureMemoryUsage();

        return new MiningResult(patterns, executionTime, memoryUsedMB);
    }

    /**
     * Measures memory usage after mining completes.
     *
     * @return memory used in megabytes
     */
    private static double measureMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        long memoryBytes = runtime.totalMemory() - runtime.freeMemory();
        return memoryBytes / OrchestratorConfiguration.BYTES_PER_MB;
    }

    /**
     * Displays mining results to stdout or to a file.
     *
     * @param parser argument parser with output file setting
     * @param result mining result
     * @throws IOException if output file cannot be written
     */
    private static void displayResults(ArgumentParser parser, MiningResult result) throws IOException {
        ResultFormatter formatter = new ResultFormatter();

        // Redirect output to file if --output was specified
        if (parser.getOutputFile() != null) {
            try (PrintStream fileOut = new PrintStream(new FileOutputStream(parser.getOutputFile()))) {
                PrintStream originalOut = System.out;
                System.setOut(fileOut);

                formatter.printResults(
                    result.patterns,
                    result.executionTimeMs,
                    result.memoryUsedMB);

                System.setOut(originalOut);
                System.err.println("[CLI] Results written to: " + parser.getOutputFile());
            }
        } else {
            // Print to stdout as usual
            formatter.printResults(
                result.patterns,
                result.executionTimeMs,
                result.memoryUsedMB);
        }
    }

    // =========================================================================
    // Result Container
    // =========================================================================

    /**
     * Immutable container for mining results.
     */
    private static final class MiningResult {
        final List<HighUtilityPattern> patterns;
        final long executionTimeMs;
        final double memoryUsedMB;

        MiningResult(List<HighUtilityPattern> patterns, long executionTimeMs, double memoryUsedMB) {
            this.patterns = patterns;
            this.executionTimeMs = executionTimeMs;
            this.memoryUsedMB = memoryUsedMB;
        }
    }
}
