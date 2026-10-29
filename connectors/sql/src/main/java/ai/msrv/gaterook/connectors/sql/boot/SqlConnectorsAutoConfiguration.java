package ai.msrv.gaterook.connectors.sql.boot;

import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connectors.sql.JdbcSqlConnector;
import ai.msrv.gaterook.connectors.sql.MySqlDialect;
import ai.msrv.gaterook.connectors.sql.OracleDialect;
import ai.msrv.gaterook.connectors.sql.PostgresDialect;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Registers the built-in SQL connectors (ARCHITECTURE.md §2.3, A13).
 *
 * <p>Explicit beans listed in this module's own {@code AutoConfiguration.imports}: Spring AOT resolves them at build
 * time, so the native image needs no classpath scanning. No {@code @Conditional*}: a connector type is switched on
 * and off at run time by {@code connector_type.enabled}, not by a build-time condition.
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
  Connector oracleConnector() {
    return new JdbcSqlConnector(new OracleDialect());
  }
}
