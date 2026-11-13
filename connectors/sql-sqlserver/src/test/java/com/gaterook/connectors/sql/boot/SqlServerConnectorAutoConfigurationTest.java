package com.gaterook.connectors.sql.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.Connector;
import com.gaterook.connector.api.ConnectorManifest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The module registers exactly its own connector type, with the SQL family's tools and the driver on hand. */
class SqlServerConnectorAutoConfigurationTest {

  @Test
  void registersTheTypeWithTheSqlToolsAndItsDriver() {
    new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(SqlServerConnectorAutoConfiguration.class)).run(context -> {
      assertThat(context.getBeansOfType(Connector.class)).hasSize(1);
      ConnectorManifest manifest = context.getBean(Connector.class).manifest();
      assertThat(manifest.id()).isEqualTo("sqlserver");
      assertThat(manifest.kind()).isEqualTo(ConnectorManifest.Kind.BUILT_IN);
      assertThat(manifest.tools()).extracting(t -> t.name()).contains("sql_query", "sql_execute");
      assertThat(Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver")).as("the JDBC driver comes with this module").isNotNull();
    });
  }
}
