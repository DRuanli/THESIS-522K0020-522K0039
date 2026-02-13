package infrastructure.io;

import domain.model.ProfitTable;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * File-based implementation of {@link ProfitTableReader}.
 *
 * <p>Reads profit values line by line from a plain-text file.
 *
 * <h3>Token format</h3>
 * {@code itemId  profit} (whitespace or colon separated)
 *
 * <p>Negative profits are fully supported and denote cost/loss items.
 * Malformed lines (bad numbers, missing fields) are silently skipped.
 */
public final class FileProfitTableReader implements ProfitTableReader {

    /**
     * Reads the profit table from the given file path.
     *
     * @param filePath path to the profit table file
     * @return parsed {@link ProfitTable}
     * @throws IOException if the file cannot be opened or read
     */
    @Override
    public ProfitTable readProfitTable(String filePath) throws IOException {
        Map<Integer, Double> profits = new HashMap<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split("[:\\s]+");
                if (parts.length >= 2) {
                    try {
                        int item = Integer.parseInt(parts[0]);
                        double profit = Double.parseDouble(parts[1]);
                        profits.put(item, profit);
                    } catch (NumberFormatException e) {
                        // Skip malformed line
                    }
                }
            }
        }

        return new ProfitTable(profits);
    }
}
