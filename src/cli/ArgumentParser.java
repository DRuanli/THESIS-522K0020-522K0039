package cli;

import application.MiningConfiguration;
import domain.collection.TopKCollectorFactory;
import infrastructure.util.ValidationUtils;

/**
 * Parses and validates all command-line arguments for the PTK-HUIM miner.
 *
 * <h3>Syntax</h3>
 * <pre>
 *   &lt;database_file&gt; &lt;profit_file&gt; &lt;k&gt; &lt;minProb&gt;
 *       [--help | -h]
 *       [--debug]
 *       [--output &lt;file&gt; | -o &lt;file&gt;]
 *       [--no-parallel]
 *       [--strategy DFS|BEST_FIRST|BREADTH_FIRST|IDDFS]
 *       [--join TWO_POINTER|EXPONENTIAL_SEARCH|BINARY_SEARCH]
 * </pre>
 *
 * <h3>Required positional arguments</h3>
 * <ol>
 *   <li>{@code database_file} — path to the transaction database file</li>
 *   <li>{@code profit_file}  — path to the profit table file</li>
 *   <li>{@code k}            — number of top patterns to return (positive integer)</li>
 *   <li>{@code minProb}      — minimum existential probability threshold ({@code ∈ [0, 1]})</li>
 * </ol>
 *
 * <h3>Optional flags</h3>
 * <ul>
 *   <li>{@code --help, -h}         — Show usage information and exit</li>
 *   <li>{@code --debug}            — Enable debug output with phase-level timing</li>
 *   <li>{@code --output, -o FILE}  — Write results to FILE instead of stdout</li>
 *   <li>{@code --no-parallel}      — Disable all parallelization (for debugging)</li>
 *   <li>{@code --strategy STRATEGY}— Select search strategy (default: DFS)</li>
 *   <li>{@code --join JOIN}        — Select join strategy (default: TWO_POINTER)</li>
 * </ul>
 *
 * <h3>Usage example</h3>
 * <pre>
 *   java cli.CommandLineInterface data/db.txt data/profit.txt 100 0.1 --debug --strategy BEST_FIRST
 * </pre>
 *
 * @see MiningConfiguration
 */
public final class ArgumentParser {

    // =========================================================================
    // Error Messages
    // =========================================================================

    private static final String USAGE_MESSAGE =
        "Usage: <database_file> <profit_file> <k> <minProb> [OPTIONS]\n" +
        "Options:\n" +
        "  --help, -h              Show this help message and exit\n" +
        "  --debug                 Enable debug output with phase-level timing\n" +
        "  --output, -o <file>     Write results to file instead of stdout\n" +
        "  --no-parallel           Disable all parallelization\n" +
        "  --strategy <strategy>   Search strategy: DFS, BEST_FIRST, BREADTH_FIRST, IDDFS (default: DFS)\n" +
        "  --join <join>           Join strategy: TWO_POINTER, EXPONENTIAL_SEARCH, BINARY_SEARCH (default: TWO_POINTER)\n" +
        "  --collector <type>      TopK collector: BASELINE, SHARDED, LAZY (default: BASELINE)";

    private static final String MISSING_ARGS_ERROR =
        "Missing required arguments. " + USAGE_MESSAGE;

    private static final String INVALID_K_FORMAT =
        "Invalid k: must be a positive integer";

    private static final String INVALID_PROB_FORMAT =
        "Invalid minProbability: must be a number in [0, 1]";

    private static final String MISSING_STRATEGY_VALUE =
        "--strategy requires a value (DFS, BEST_FIRST, BREADTH_FIRST, IDDFS)";

    private static final String UNKNOWN_STRATEGY_FORMAT =
        "Unknown strategy: %s. Valid strategies: DFS, BEST_FIRST, BREADTH_FIRST, IDDFS";

    private static final String MISSING_JOIN_VALUE =
        "--join requires a value (TWO_POINTER, EXPONENTIAL_SEARCH, BINARY_SEARCH)";

    private static final String UNKNOWN_JOIN_FORMAT =
        "Unknown join strategy: %s. Valid join strategies: TWO_POINTER, EXPONENTIAL_SEARCH, BINARY_SEARCH";

    private static final String MISSING_COLLECTOR_VALUE =
        "--collector requires a value (BASELINE, SHARDED, LAZY)";

    private static final String UNKNOWN_COLLECTOR_FORMAT =
        "Unknown collector type: %s. Valid collectors: BASELINE, SHARDED, LAZY";

    private static final String MISSING_OUTPUT_VALUE =
        "--output requires a file path";

    private static final String UNKNOWN_ARG_FORMAT =
        "Unknown argument: %s. Use --help for usage information.";

    // =========================================================================
    // Parsed Fields
    // =========================================================================

    private String databaseFile;
    private String profitFile;
    private int k;
    private double minProbability;
    private boolean helpRequested = false;
    private boolean debugMode = false;
    private String outputFile = null;
    private boolean noParallel = false;
    private MiningConfiguration.SearchStrategy searchStrategy = MiningConfiguration.SearchStrategy.DFS;
    private MiningConfiguration.JoinStrategy joinStrategy = MiningConfiguration.JoinStrategy.TWO_POINTER;
    private TopKCollectorFactory.TopKCollectorType collectorType = TopKCollectorFactory.TopKCollectorType.BASELINE;

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Parses command-line arguments.
     *
     * @param args command-line arguments from {@code main()}
     * @throws IllegalArgumentException if arguments are invalid or missing
     */
    public void parse(String[] args) {
        // Check for help flag first (allow --help even without required args)
        if (args.length > 0 && (args[0].equals("--help") || args[0].equals("-h"))) {
            helpRequested = true;
            return;
        }

        if (args.length < 4) {
            throw new IllegalArgumentException(MISSING_ARGS_ERROR);
        }

        // Parse required positional arguments
        parseRequiredArguments(args);

        // Parse optional flags
        parseOptionalFlags(args);
    }

    /**
     * Builds a {@link MiningConfiguration} from parsed arguments.
     *
     * <p>Must be called after {@link #parse(String[])}.
     *
     * @return immutable mining configuration
     */
    public MiningConfiguration buildConfiguration() {
        MiningConfiguration.Builder builder = new MiningConfiguration.Builder()
            .setK(k)
            .setMinProbability(minProbability)
            .setDebugMode(debugMode)
            .setSearchStrategy(searchStrategy)
            .setJoinStrategy(joinStrategy)
            .setCollectorType(collectorType);

        // If --no-parallel is set, disable all parallelization
        if (noParallel) {
            builder.setUseParallelPhase1a(false)
                   .setUseParallelUPUListBuilding(false)
                   .setUseParallelMining(false);
        }

        return builder.build();
    }

    // =========================================================================
    // Getters
    // =========================================================================

    public String getDatabaseFile() { return databaseFile; }
    public String getProfitFile() { return profitFile; }
    public int getK() { return k; }
    public double getMinProbability() { return minProbability; }
    public boolean isHelpRequested() { return helpRequested; }
    public boolean isDebugMode() { return debugMode; }
    public String getOutputFile() { return outputFile; }
    public boolean isNoParallel() { return noParallel; }
    public MiningConfiguration.SearchStrategy getSearchStrategy() { return searchStrategy; }
    public MiningConfiguration.JoinStrategy getJoinStrategy() { return joinStrategy; }

    /**
     * Prints help message to stderr.
     */
    public void printHelp() {
        System.err.println("PTK-HUIM: Probabilistic Top-K High Utility Itemset Miner");
        System.err.println();
        System.err.println(USAGE_MESSAGE);
        System.err.println();
        System.err.println("Example:");
        System.err.println("  java cli.CommandLineInterface data/db.txt data/profit.txt 100 0.1 --debug");
    }

    // =========================================================================
    // Private Parsing Methods
    // =========================================================================

    /**
     * Parses the four required positional arguments.
     *
     * @param args command-line arguments
     * @throws IllegalArgumentException if arguments are invalid
     */
    private void parseRequiredArguments(String[] args) {
        databaseFile = args[0];
        profitFile = args[1];
        k = parseKParameter(args[2]);
        minProbability = parseMinProbParameter(args[3]);
    }

    /**
     * Parses k parameter with validation.
     *
     * @param arg the k argument string
     * @return parsed k value
     * @throws IllegalArgumentException if k is invalid
     */
    private int parseKParameter(String arg) {
        try {
            int parsedK = Integer.parseInt(arg);
            ValidationUtils.validatePositive(parsedK, "k");
            return parsedK;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(INVALID_K_FORMAT);
        }
    }

    /**
     * Parses minProbability parameter with validation.
     *
     * @param arg the minProb argument string
     * @return parsed minProbability value
     * @throws IllegalArgumentException if minProb is invalid
     */
    private double parseMinProbParameter(String arg) {
        try {
            double parsedProb = Double.parseDouble(arg);
            ValidationUtils.validateProbability(parsedProb, "minProbability");
            return parsedProb;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(INVALID_PROB_FORMAT);
        }
    }

    /**
     * Parses optional flags starting from index 4.
     *
     * <p>This method uses a loop with index manipulation to handle both simple flags
     * (no arguments) and parametrized flags (with arguments like --strategy DFS).
     *
     * <h3>Flag Handling Pattern</h3>
     * <p>The loop iterates through arguments starting at index 4 (after the 4 required positional arguments).
     * Two types of flags are supported:
     *
     * <p><b>1. Simple flags (no arguments):</b>
     * <pre>
     *   case "--debug":
     *       debugMode = true;
     *       break;  // Loop automatically advances i++
     * </pre>
     * The {@code break} statement exits the switch and returns to the loop,
     * which automatically increments {@code i} to move to the next argument.
     *
     * <p><b>2. Parametrized flags (with arguments):</b>
     * <pre>
     *   case "--strategy":
     *       i = parseStrategyFlag(args, i);  // Consumes TWO positions
     *       break;
     * </pre>
     * The helper method (e.g., {@link #parseStrategyFlag}) returns the updated index
     * after consuming both the flag and its value. This skips the loop's automatic
     * {@code i++} for the value argument.
     *
     * <p><b>Example walkthrough:</b>
     * <pre>
     *   args = ["db.txt", "profit.txt", "100", "0.1", "--debug", "--strategy", "DFS"]
     *   Indices:  [0]       [1]          [2]    [3]    [4]       [5]          [6]
     *
     *   i=4: args[4]="--debug" → set debugMode=true, loop increments i to 5
     *   i=5: args[5]="--strategy" → call parseStrategyFlag(args, 5)
     *        → parseStrategyFlag consumes args[6]="DFS", returns 6
     *        → i=6, loop increments i to 7
     *   i=7: i >= args.length, loop exits
     * </pre>
     *
     * <p>This pattern ensures that each flag and its associated value(s) are
     * processed correctly without manual index tracking in the main loop.
     *
     * @param args command-line arguments
     * @throws IllegalArgumentException if flags are invalid
     */
    private void parseOptionalFlags(String[] args) {
        // Loop through all arguments after the 4 required positional parameters
        for (int i = 4; i < args.length; i++) {
            String arg = args[i];

            switch (arg) {
                case "--help":
                case "-h":
                    // Help flag: set boolean and continue (handled in parse())
                    helpRequested = true;
                    break;

                case "--debug":
                    // Simple flag: no arguments, just set boolean
                    debugMode = true;
                    break;  // Loop auto-increments i to next argument

                case "--output":
                case "-o":
                    // Parametrized flag: consumes next argument as file path
                    i = parseOutputFlag(args, i);
                    break;

                case "--no-parallel":
                    // Simple flag: disable all parallelization
                    noParallel = true;
                    break;

                case "--strategy":
                    // Parametrized flag: consumes next argument as value
                    // parseStrategyFlag() returns updated index after consuming value
                    i = parseStrategyFlag(args, i);
                    break;  // Loop auto-increments i (now past the consumed value)

                case "--join":
                    // Parametrized flag: consumes next argument as value
                    i = parseJoinFlag(args, i);
                    break;  // Loop auto-increments i (now past the consumed value)

                case "--collector":
                    // Parametrized flag: consumes next argument as collector type
                    i = parseCollectorFlag(args, i);
                    break;  // Loop auto-increments i (now past the consumed value)

                default:
                    // Unknown flag: throw descriptive error
                    throw new IllegalArgumentException(String.format(UNKNOWN_ARG_FORMAT, arg));
            }
        }
    }

    /**
     * Parses --output flag.
     *
     * @param args command-line arguments
     * @param i    current index (at --output or -o)
     * @return next index to process
     * @throws IllegalArgumentException if output file is missing
     */
    private int parseOutputFlag(String[] args, int i) {
        if (i + 1 >= args.length) {
            throw new IllegalArgumentException(MISSING_OUTPUT_VALUE);
        }

        outputFile = args[++i];
        return i;
    }

    /**
     * Parses --strategy flag.
     *
     * @param args command-line arguments
     * @param i    current index (at --strategy)
     * @return next index to process
     * @throws IllegalArgumentException if strategy is missing or invalid
     */
    private int parseStrategyFlag(String[] args, int i) {
        if (i + 1 >= args.length) {
            throw new IllegalArgumentException(MISSING_STRATEGY_VALUE);
        }

        String strategyStr = args[++i].toUpperCase();

        try {
            searchStrategy = MiningConfiguration.SearchStrategy.valueOf(strategyStr);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                String.format(UNKNOWN_STRATEGY_FORMAT, strategyStr));
        }

        return i;
    }

    /**
     * Parses --join flag.
     *
     * @param args command-line arguments
     * @param i    current index (at --join)
     * @return next index to process
     * @throws IllegalArgumentException if join strategy is missing or invalid
     */
    private int parseJoinFlag(String[] args, int i) {
        if (i + 1 >= args.length) {
            throw new IllegalArgumentException(MISSING_JOIN_VALUE);
        }

        String joinStr = args[++i].toUpperCase();

        try {
            joinStrategy = MiningConfiguration.JoinStrategy.valueOf(joinStr);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                String.format(UNKNOWN_JOIN_FORMAT, joinStr));
        }

        return i;
    }

    /**
     * Parses --collector flag.
     *
     * @param args command-line arguments
     * @param i    current index (at --collector)
     * @return next index to process
     * @throws IllegalArgumentException if collector type is missing or invalid
     */
    private int parseCollectorFlag(String[] args, int i) {
        if (i + 1 >= args.length) {
            throw new IllegalArgumentException(MISSING_COLLECTOR_VALUE);
        }

        String collectorStr = args[++i].toUpperCase();

        try {
            collectorType = TopKCollectorFactory.TopKCollectorType.valueOf(collectorStr);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                String.format(UNKNOWN_COLLECTOR_FORMAT, collectorStr));
        }

        return i;
    }

    // =========================================================================
    // Additional Getters
    // =========================================================================

    /**
     * Returns the parsed TopK collector type.
     *
     * @return collector type (default: BASELINE)
     */
    public TopKCollectorFactory.TopKCollectorType getCollectorType() {
        return collectorType;
    }
}
