package ai.msrv.gaterook.core.platform.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

/**
 * The gateway database's connection pools (ARCHITECTURE.md A10, report 14 §7.1, §9):
 * <ul>
 *   <li><b>writer</b>, one connection, {@code transaction_mode=IMMEDIATE}: the only writer. Transactions take the write
 *       lock at {@code BEGIN}, so "read, then write" never fails with {@code SQLITE_BUSY_SNAPSHOT}, and writers queue in
 *       Hikari (Java, the virtual thread unmounts) instead of in {@code busy_timeout} (native code, the carrier is
 *       pinned).</li>
 *   <li><b>reader</b>, up to three connections with {@code PRAGMA query_only=1}: {@code @Transactional(readOnly = true)}
 *       work (Activity, Insights) that never waits for the writer in WAL mode.</li>
 * </ul>
 * A {@link LazyConnectionDataSourceProxy} routes each transaction by its read-only flag; with {@code JpaTransactionManager}
 * that works because Spring marks the proxy connection read-only before Hibernate runs the first statement
 * ({@code DatabaseSelfCheck} proves it on every start). Pragmas go into the URL, applied on every connection:
 * {@code connectionInitSql} with sqlite-jdbc runs only its first statement, so it carries {@code query_only} alone.
 * A connection whose transaction SQLite may have ended by itself (a full disk), or that a broken file left unusable,
 * is replaced, not reused ({@link LostTransactionEviction}).
 */
public final class SqlitePools implements AutoCloseable {

  public static final int WRITER_POOL_SIZE = 1;
  public static final int MAX_READER_POOL_SIZE = 3;

  /** The most connections that can sit in native SQLite code at once: what the scheduler is sized for. */
  public static final int MAX_CONNECTIONS = WRITER_POOL_SIZE + MAX_READER_POOL_SIZE;

  static final String WRITER_NAME = "gateway-db-writer";
  static final String READER_NAME = "gateway-db-reader";

  private final String url;
  private final HikariDataSource writer;
  private final HikariDataSource reader;
  private final DataSource routing;

  private SqlitePools(String url, HikariDataSource writer, HikariDataSource reader) {
    this.url = url;
    this.writer = writer;
    this.reader = reader;
    LazyConnectionDataSourceProxy proxy = new LazyConnectionDataSourceProxy(writer);
    if (reader != null) {
      proxy.setReadOnlyDataSource(reader);
    }
    this.routing = proxy;
  }

  /** Opens both pools on {@code database}; each pool opens one connection at once, so a broken file fails here. */
  public static SqlitePools open(Path database, GatewayDatabaseProperties properties) {
    String url = url(database, properties);
    HikariDataSource writer = pool(WRITER_NAME, url + "&transaction_mode=IMMEDIATE", WRITER_POOL_SIZE, null,
        properties);
    try {
      HikariDataSource reader = properties.readerPoolSize() == 0 ? null
          : pool(READER_NAME, url, properties.readerPoolSize(), "PRAGMA query_only=1", properties);
      return new SqlitePools(url, writer, reader);
    } catch (RuntimeException e) {
      writer.close();
      throw e;
    }
  }

  /**
   * The JDBC URL both pools share. {@code synchronous=FULL}: an audit row survives power loss once the call is told it
   * was written; {@code secure_delete}: deleted pages (crypto-shredded keys) are zeroed (report 12 §6).
   */
  static String url(Path database, GatewayDatabaseProperties properties) {
    return "jdbc:sqlite:" + database.toAbsolutePath() + "?journal_mode=WAL&synchronous=FULL&busy_timeout="
        + properties.busyTimeout().toMillis() + "&foreign_keys=true&secure_delete=true";
  }

  private static HikariDataSource pool(String name, String url, int size, String initSql,
      GatewayDatabaseProperties properties) {
    HikariConfig config = new HikariConfig();
    config.setPoolName(name);
    config.setJdbcUrl(url);
    config.setMaximumPoolSize(size);
    config.setMinimumIdle(1);
    config.setConnectionTimeout(properties.connectionTimeout().toMillis());
    // A local file: nothing ages, nothing in between drops idle connections.
    config.setMaxLifetime(0);
    config.setKeepaliveTime(0);
    config.setIdleTimeout(0);
    // But a full disk or an I/O error leaves the connection broken: replaced, not reused.
    config.setExceptionOverride(new LostTransactionEviction());
    if (initSql != null) {
      // Not setReadOnly(true): sqlite-jdbc either refuses it or, with jdbc.explicit_readonly, still lets writes
      // through (report 14 §7.1). query_only makes every write fail with SQLITE_READONLY.
      config.setConnectionInitSql(initSql);
    }
    return new HikariDataSource(config);
  }

  /** The routing data source: writer by default, reader inside read-only transactions. */
  public DataSource dataSource() {
    return routing;
  }

  public DataSource writer() {
    return writer;
  }

  public Optional<DataSource> reader() {
    return Optional.ofNullable(reader);
  }

  int readerPoolSize() {
    return reader == null ? 0 : reader.getMaximumPoolSize();
  }

  String url() {
    return url;
  }

  @Override
  public void close() {
    if (reader != null) {
      reader.close();
    }
    writer.close();
  }
}
