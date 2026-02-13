package infrastructure.io;

import domain.model.Transaction;

import java.io.IOException;
import java.util.List;

/**
 * Abstraction for loading uncertain transaction databases.
 *
 * <p>Implementations parse the physical format (file, stream, etc.) and
 * produce a list of {@link Transaction} objects ready for Phase-1 processing.
 * The default file-based implementation is {@link FileTransactionReader}.
 *
 * <h3>Expected transaction format (default)</h3>
 * Each line represents one transaction:
 * <pre>
 *   itemId:quantity:probability  itemId:quantity:probability  ...
 * </pre>
 * Tokens are whitespace-separated.  Duplicate item IDs on the same line retain
 * only the last occurrence (quantities are NOT accumulated).
 *
 * @see FileTransactionReader
 */
public interface TransactionReader {

    /**
     * Reads all transactions from the specified source.
     *
     * @param source source identifier (e.g., file path)
     * @return ordered list of parsed {@link Transaction} objects; never {@code null}
     * @throws IOException if the source cannot be read or is malformed
     */
    List<Transaction> readTransactions(String source) throws IOException;
}
