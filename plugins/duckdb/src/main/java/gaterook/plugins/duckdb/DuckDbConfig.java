package gaterook.plugins.duckdb;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * A connection's settings as the manifest's configSchema defines them, read without I/O: the database file and the
 * data folders as the person typed or chose them, and the limits of every call. A DuckDB file has no user, password or
 * host: there is no secret field.
 *
 * @param file               the database file as configured ({@link DuckDbFiles#path} reads it)
 * @param dataFolders        the folders whose files queries may read, as configured; empty: none
 * @param maxRows            rows a query returns at most (the environment's limit applies on top)
 * @param statementTimeoutMs the connection's own statement timeout, or {@code null} for the environment's
 * @param memoryLimitMb      DuckDB's {@code memory_limit} for one call
 * @param threads            DuckDB's {@code threads} for one call
 */
record DuckDbConfig(String file, List<String> dataFolders, int maxRows, Integer statementTimeoutMs,
    int memoryLimitMb, int threads) {

  /** As many data folders as the schema allows. */
  static final int MAX_FOLDERS = 16;

  static DuckDbConfig of(ConnectionConfig cfg) throws ConnectorException {
    String file = cfg.string("/file");
    if (file == null || file.isBlank()) {
      throw ConnectorException.badInput("Choose the database file of the connection.");
    }
    JsonNode folders = cfg.config().path("dataFolders");
    List<String> dataFolders = new ArrayList<>();
    if (!folders.isMissingNode() && !folders.isNull()) {
      if (!folders.isArray() || folders.size() > MAX_FOLDERS) {
        throw ConnectorException.badInput("Data folders is a list of at most " + MAX_FOLDERS + " folders.");
      }
      for (JsonNode f : folders) {
        if (!f.isString() || f.asString().isBlank()) {
          throw ConnectorException.badInput("Every data folder is a path.");
        }
        if (!dataFolders.contains(f.asString().strip())) {
          dataFolders.add(f.asString().strip());
        }
      }
    }
    JsonNode timeout = cfg.config().at("/options/statementTimeoutMs");
    Integer statementTimeoutMs = timeout.isMissingNode() || timeout.isNull() ? null
        : Math.clamp(timeout.asInt(30_000), 100, 600_000);
    return new DuckDbConfig(file.strip(), List.copyOf(dataFolders),
        Math.clamp(cfg.integer("/options/maxRows", 1000), 1, 10_000), statementTimeoutMs,
        Math.clamp(cfg.integer("/options/memoryLimitMb", 512), 64, 16_384),
        Math.clamp(cfg.integer("/options/threads", 2), 1, 16));
  }

  /** The configured file's name, for targets and summaries: the person's folders stay out of them. */
  String fileName() {
    String text = file.replace('\\', '/');
    while (text.endsWith("/") && text.length() > 1) {
      text = text.substring(0, text.length() - 1);
    }
    int slash = text.lastIndexOf('/');
    return slash < 0 ? text : text.substring(slash + 1);
  }
}
