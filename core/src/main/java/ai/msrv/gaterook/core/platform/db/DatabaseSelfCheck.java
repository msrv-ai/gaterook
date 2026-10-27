package ai.msrv.gaterook.core.platform.db;

import ai.msrv.gaterook.core.platform.boot.GatewayStartupException;
import ai.msrv.gaterook.core.platform.boot.StartupProtocol;
import ai.msrv.gaterook.core.platform.boot.StartupReason;
import ai.msrv.gaterook.core.platform.boot.StartupStep;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves at every start, before the local API opens, what the core relies on in the gateway database (ARCHITECTURE.md
 * A10 gate): each pool's connections carry every pragma of the URL, the reader is {@code query_only} and the writer is
 * not, and JPA transactions really are routed — {@code @Transactional(readOnly = true)} lands on the reader, a write
 * transaction on the writer. A driver or library upgrade that silently drops one of them stops the start
 * ({@code DB_CHECK_FAILED}) instead of weakening the audit or the read-only guarantee. Costs a few milliseconds.
 */
final class DatabaseSelfCheck implements SmartInitializingSingleton, BeanFactoryAware {

  private static final Logger log = LoggerFactory.getLogger(DatabaseSelfCheck.class);

  /** Expected pragma values on every connection of both pools; {@code synchronous} 2 is FULL. */
  static final Map<String, String> PRAGMAS = pragmas();

  private final SqlitePools pools;
  private final GatewayDatabaseProperties properties;
  private BeanFactory beanFactory;

  DatabaseSelfCheck(SqlitePools pools, GatewayDatabaseProperties properties) {
    this.pools = pools;
    this.properties = properties;
  }

  @Override
  public void setBeanFactory(BeanFactory beanFactory) {
    this.beanFactory = beanFactory;
  }

  @Override
  public void afterSingletonsInstantiated() {
    List<String> problems = new ArrayList<>();
    String sqliteVersion;
    try {
      sqliteVersion = check("writer", pools.writer(), 0, problems);
      if (pools.reader().isPresent()) {
        check("reader", pools.reader().get(), 1, problems);
      }
    } catch (SQLException e) {
      throw failed("Can't check the database connections: " + e.getMessage(), e);
    }

    PlatformTransactionManager transactions = beanFactory.getBean(PlatformTransactionManager.class);
    EntityManagerFactory entityManagers = beanFactory.getBean(EntityManagerFactory.class);
    int expectedInReadOnly = pools.reader().isPresent() ? 1 : 0;
    int inReadOnly = queryOnlyInTransaction(transactions, entityManagers, true);
    int inWrite = queryOnlyInTransaction(transactions, entityManagers, false);
    if (inReadOnly != expectedInReadOnly) {
      problems.add("a read-only JPA transaction ran with query_only=" + inReadOnly + ", expected "
          + expectedInReadOnly + " (read-only routing to the reader pool doesn't work)");
    }
    if (inWrite != 0) {
      problems.add("a write JPA transaction ran with query_only=" + inWrite + " (it landed on the reader pool)");
    }
    if (!problems.isEmpty()) {
      throw failed("The gateway database is not configured as required: " + String.join("; ", problems), null);
    }
    log.info("Gateway database {}: SQLite {}, writer pool {} (IMMEDIATE), reader pool {} (query_only), read-only "
        + "transactions verified on the {} pool", pools.url().substring(0, pools.url().indexOf('?')), sqliteVersion,
        SqlitePools.WRITER_POOL_SIZE, pools.readerPoolSize(), pools.reader().isPresent() ? "reader" : "writer");
    StartupProtocol.current().done(StartupStep.DB_MIGRATIONS);
  }

  /** Checks one pooled connection; returns the SQLite version. */
  private String check(String pool, DataSource dataSource, int queryOnly, List<String> problems) throws SQLException {
    try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
      Map<String, String> expected = new LinkedHashMap<>(PRAGMAS);
      expected.put("busy_timeout", Long.toString(properties.busyTimeout().toMillis()));
      expected.put("query_only", Integer.toString(queryOnly));
      for (Map.Entry<String, String> pragma : expected.entrySet()) {
        String actual = single(statement, "PRAGMA " + pragma.getKey());
        if (!pragma.getValue().equalsIgnoreCase(actual)) {
          problems.add(pool + " pool has " + pragma.getKey() + "=" + actual + ", expected " + pragma.getValue());
        }
      }
      return single(statement, "select sqlite_version()");
    }
  }

  /** {@code PRAGMA query_only} on the connection Hibernate uses inside a JPA transaction of the given kind. */
  private static int queryOnlyInTransaction(PlatformTransactionManager transactions,
      EntityManagerFactory entityManagers, boolean readOnly) {
    TransactionTemplate template = new TransactionTemplate(transactions);
    template.setReadOnly(readOnly);
    // The transaction's own EntityManager (Hibernate's session), not a shared proxy: no JDK proxy in the native image.
    Integer value = template.execute(status -> EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagers)
        .unwrap(Session.class).doReturningWork(connection -> {
          try (Statement statement = connection.createStatement()) {
            return Integer.parseInt(single(statement, "PRAGMA query_only"));
          }
        }));
    return value == null ? -1 : value;
  }

  private static String single(Statement statement, String sql) throws SQLException {
    try (ResultSet rows = statement.executeQuery(sql)) {
      return rows.next() ? rows.getString(1) : null;
    }
  }

  private static GatewayStartupException failed(String message, Throwable cause) {
    return new GatewayStartupException(StartupReason.DB_CHECK_FAILED, message, cause);
  }

  private static Map<String, String> pragmas() {
    Map<String, String> pragmas = new LinkedHashMap<>();
    pragmas.put("journal_mode", "wal");
    pragmas.put("synchronous", "2");
    pragmas.put("foreign_keys", "1");
    pragmas.put("secure_delete", "1");
    return Map.copyOf(pragmas);
  }
}
