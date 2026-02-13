package cli;

import domain.model.ProfitTable;
import domain.model.Transaction;
import infrastructure.io.FileProfitTableReader;
import infrastructure.io.FileTransactionReader;

import java.io.IOException;
import java.util.List;

/**
 * Utility class for loading mining data from files.
 *
 * <p>Provides a centralized, type-safe API for loading transaction databases
 * and profit tables. All file I/O errors are propagated as {@link IOException}
 * for consistent error handling across CLI tools.
 *
 * <h3>Usage example</h3>
 * <pre>
 *   DataLoader loader = new DataLoader();
 *   MiningData data = loader.loadAll("data/db.txt", "data/profit.txt");
 *   List&lt;Transaction&gt; database = data.getDatabase();
 *   ProfitTable profitTable = data.getProfitTable();
 * </pre>
 *
 * <p>This class is stateless and thread-safe. All methods can be called concurrently.
 */
public final class DataLoader {

    /**
     * Immutable container for loaded mining data.
     *
     * <p>Returned by {@link #loadAll(String, String)} to provide both the transaction
     * database and profit table in a single, type-safe object.
     */
    public static final class MiningData {
        private final List<Transaction> database;
        private final ProfitTable profitTable;

        /**
         * Constructs a mining data container.
         *
         * @param database    the transaction database (non-null)
         * @param profitTable the profit table (non-null)
         */
        public MiningData(List<Transaction> database, ProfitTable profitTable) {
            if (database == null || profitTable == null) {
                throw new IllegalArgumentException("database and profitTable cannot be null");
            }
            this.database = database;
            this.profitTable = profitTable;
        }

        /**
         * Returns the transaction database.
         *
         * @return the loaded transactions
         */
        public List<Transaction> getDatabase() {
            return database;
        }

        /**
         * Returns the profit table.
         *
         * @return the loaded profit values
         */
        public ProfitTable getProfitTable() {
            return profitTable;
        }

        /**
         * Returns the number of transactions in the database.
         *
         * @return transaction count
         */
        public int getTransactionCount() {
            return database.size();
        }

        /**
         * Returns the number of items in the profit table.
         *
         * @return item count
         */
        public int getItemCount() {
            return profitTable.size();
        }
    }

    /**
     * Loads a transaction database from a file.
     *
     * @param databasePath path to the transaction database file
     * @return list of transactions
     * @throws IOException if the file cannot be read or parsed
     * @throws IllegalArgumentException if databasePath is null or empty
     */
    public List<Transaction> loadDatabase(String databasePath) throws IOException {
        validateFilePath(databasePath, "Database file path");
        FileTransactionReader reader = new FileTransactionReader();
        return reader.readTransactions(databasePath);
    }

    /**
     * Loads a profit table from a file.
     *
     * @param profitPath path to the profit table file
     * @return the profit table
     * @throws IOException if the file cannot be read or parsed
     * @throws IllegalArgumentException if profitPath is null or empty
     */
    public ProfitTable loadProfitTable(String profitPath) throws IOException {
        validateFilePath(profitPath, "Profit file path");
        FileProfitTableReader reader = new FileProfitTableReader();
        return reader.readProfitTable(profitPath);
    }

    /**
     * Loads both transaction database and profit table in one call.
     *
     * <p>This is a convenience method that loads both data sources and returns
     * them in a single {@link MiningData} container. It's equivalent to calling
     * {@link #loadDatabase(String)} and {@link #loadProfitTable(String)} separately.
     *
     * @param databasePath path to the transaction database file
     * @param profitPath   path to the profit table file
     * @return container with both loaded data sources
     * @throws IOException if either file cannot be read or parsed
     * @throws IllegalArgumentException if any path is null or empty
     */
    public MiningData loadAll(String databasePath, String profitPath) throws IOException {
        List<Transaction> database = loadDatabase(databasePath);
        ProfitTable profitTable = loadProfitTable(profitPath);
        return new MiningData(database, profitTable);
    }

    /**
     * Validates a file path parameter.
     *
     * @param path      the file path to validate
     * @param paramName parameter name for error messages
     * @throws IllegalArgumentException if path is null or empty
     */
    private void validateFilePath(String path, String paramName) {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException(paramName + " cannot be null or empty");
        }
    }
}
