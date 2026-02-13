package infrastructure.io;

import domain.model.ProfitTable;

import java.io.IOException;

/**
 * Abstraction for loading item profit tables.
 *
 * <p>The profit table maps each item ID to its per-unit profit, which may be
 * negative (cost/loss items).  The default file-based implementation is
 * {@link FileProfitTableReader}.
 *
 * <h3>Expected profit format (default)</h3>
 * Each line:
 * <pre>
 *   itemId  profit
 * </pre>
 * Tokens are whitespace-separated; negative profits are supported.
 *
 * @see FileProfitTableReader
 */
public interface ProfitTableReader {

    /**
     * Reads a profit table from the specified source.
     *
     * @param source source identifier (e.g., file path)
     * @return parsed {@link ProfitTable}; never {@code null}
     * @throws IOException if the source cannot be read or is malformed
     */
    ProfitTable readProfitTable(String source) throws IOException;
}
