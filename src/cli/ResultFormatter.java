package cli;

import domain.model.HighUtilityPattern;

import java.util.List;
import java.util.Locale;

/**
 * Formats and prints mining results to standard output.
 *
 * <p>Results are presented as a ranked table with columns for rank, itemset,
 * Expected Utility, and Existential Probability.  A performance summary
 * (execution time, pattern count, memory) is appended at the end.
 *
 * <h3>Locale-Independent Formatting</h3>
 * <p>All floating-point values use {@link Locale#ROOT} to ensure consistent,
 * locale-independent decimal formatting. This is critical for:
 * <ul>
 *   <li><b>Reproducibility</b> — Results are identical regardless of system locale
 *       (e.g., US: "3.14", Germany: "3,14", France: "3,14")</li>
 *   <li><b>CSV parsing</b> — Comma-separated values remain parseable when decimals
 *       use period (.) instead of comma (,) as decimal separator</li>
 *   <li><b>Cross-platform compatibility</b> — Benchmark results can be compared
 *       across different systems and locales without reformatting</li>
 *   <li><b>Publication standards</b> — Scientific publications (PLOS ONE, IEEE, ACM)
 *       require period (.) as decimal separator per international standards</li>
 * </ul>
 *
 * <p><b>Example:</b> Without Locale.ROOT, a German system might output "123,456"
 * instead of "123.456", breaking CSV parsers and making cross-locale comparison impossible.
 */
public final class ResultFormatter {

    /**
     * Constructs a formatter.
     */
    public ResultFormatter() {
    }

    /**
     * Prints the top-k patterns and performance statistics to {@code System.out}.
     *
     * @param patterns        patterns sorted by EU descending (as returned by the collector)
     * @param executionTimeMs total wall-clock time from data load to result ready, in ms
     * @param memoryUsedMB    heap memory in use at result-ready time, in megabytes
     */
    public void printResults(List<HighUtilityPattern> patterns, long executionTimeMs, double memoryUsedMB) {
        System.out.println("=================================================");
        System.out.printf("TOP-%d HIGH-UTILITY PATTERNS%n", patterns.size());
        System.out.println("=================================================");

        if (patterns.isEmpty()) {
            System.out.println("No patterns found.");
        } else {
            System.out.printf("%-6s %-40s %-15s %-15s%n",
                "Rank", "Pattern", "Expected Util", "Exist Prob");
            System.out.println("-------------------------------------------------");

            int rank = 1;
            for (HighUtilityPattern pattern : patterns) {
                String itemsetStr = formatItemset(pattern.getItems());
                System.out.printf(Locale.ROOT, "%-6d %-40s %-15.4f %-15.6f%n",
                    rank++,
                    itemsetStr,
                    pattern.getExpectedUtility(),
                    pattern.getExistentialProbability());
            }
        }

        System.out.println("=================================================");
        System.out.printf(Locale.ROOT, "Execution time: %.3f seconds%n", executionTimeMs / 1000.0);
        System.out.printf("Patterns found: %d%n", patterns.size());
        System.out.printf(Locale.ROOT, "Memory used: %.2f MB%n", memoryUsedMB);
        System.out.println("=================================================");
    }

    private String formatItemset(Iterable<Integer> items) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (int item : items) {
            if (!first) sb.append(", ");
            sb.append(item);
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }
}
