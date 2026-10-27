package ai.msrv.gaterook.connectors.sql.boot;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Connector;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SqlConnectorsAutoConfigurationTest {

  @Test
  void isListedInTheModulesAutoConfigurationImports() {
    assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).getCandidates())
        .contains(SqlConnectorsAutoConfiguration.class.getName());
  }

  @Test
  void registersPostgresAndMysql() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(SqlConnectorsAutoConfiguration.class))
        .run(ctx -> assertThat(ctx.getBeansOfType(Connector.class).values())
            .extracting(c -> c.manifest().id())
            .containsExactlyInAnyOrder("postgres", "mysql"));
  }

  @Test
  void schemasAreNativeImageResources() {
    RuntimeHints hints = new RuntimeHints();
    new SqlHints().registerHints(hints, getClass().getClassLoader());

    assertThat(RuntimeHintsPredicates.resource().forResource("connectors/sql/sql_query.input.schema.json"))
        .accepts(hints);
    assertThat(RuntimeHintsPredicates.resource().forResource("connectors/sql/postgres.config.schema.json"))
        .accepts(hints);
  }
}
