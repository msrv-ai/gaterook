package com.gaterook.connectors.sql.boot;

import com.gaterook.connector.api.Connector;
import com.gaterook.connectors.sql.JdbcSqlConnector;
import com.gaterook.connectors.sql.MySqlDialect;
import com.gaterook.connectors.sql.PostgresDialect;
import com.gaterook.connectors.sql.SqliteDialect;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Registers the built-in SQL connectors (ARCHITECTURE.md §2.3, A13).
 *
 * <p>Explicit beans listed in this module's own {@code AutoConfiguration.imports}: Spring AOT resolves them at build
 * time, so the native image needs no classpath scanning. No {@code @Conditional*}: a connector type is switched on
 * and off at run time by {@code connector_type.enabled}, not by a build-time condition.
 *
 * <p>PostgreSQL, MySQL and SQLite are registered here. Oracle Database and SQL Server are the same connector with
 * their own dialects, registered by {@code connectors/sql-oracle} and {@code connectors/sql-sqlserver}, which bring
 * their JDBC drivers: an assembly without those modules has neither the types nor the drivers.
 */
@AutoConfiguration
@ImportRuntimeHints(SqlHints.class)
public class SqlConnectorsAutoConfiguration {

  @Bean
  Connector postgresConnector() {
    return new JdbcSqlConnector(new PostgresDialect());
  }

  @Bean
  Connector mysqlConnector() {
    return new JdbcSqlConnector(new MySqlDialect());
  }

  @Bean
  Connector sqliteConnector() {
    return new JdbcSqlConnector(new SqliteDialect());
  }
}
