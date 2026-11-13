package com.gaterook.connectors.sql.boot;

import com.gaterook.connector.api.Connector;
import com.gaterook.connectors.sql.JdbcSqlConnector;
import com.gaterook.connectors.sql.SqlServerDialect;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Registers the SQL Server connector type (ARCHITECTURE.md §2.3, A13): an explicit bean listed in this module's own
 * {@code AutoConfiguration.imports}, resolved by Spring AOT at build time. The connector is the SQL connector with
 * this dialect; its schemas and native-image hints come with {@code connectors/sql}. No {@code @Conditional*}: an
 * assembly has the type when it depends on this module.
 */
@AutoConfiguration
public class SqlServerConnectorAutoConfiguration {

  @Bean
  Connector sqlServerConnector() {
    return new JdbcSqlConnector(new SqlServerDialect());
  }
}
