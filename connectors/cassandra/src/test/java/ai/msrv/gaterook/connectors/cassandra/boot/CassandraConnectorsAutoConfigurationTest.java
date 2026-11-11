package ai.msrv.gaterook.connectors.cassandra.boot;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connectors.cassandra.NeverRetry;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CassandraConnectorsAutoConfigurationTest {

  @Test
  void registersTheConnectorThroughItsOwnImports() throws Exception {
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(
        "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")) {
      assertThat(in).isNotNull();
      assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
          .contains(CassandraConnectorsAutoConfiguration.class.getName());
    }
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CassandraConnectorsAutoConfiguration.class))
        .run(ctx -> assertThat(ctx.getBeansOfType(Connector.class).values())
            .extracting(c -> c.manifest().id()).containsExactly("cassandra"));
  }

  @Test
  void hintsTheSchemasAndTheRetryPolicyForTheNativeImage() throws Exception {
    RuntimeHints hints = new RuntimeHints();
    new CassandraHints().registerHints(hints, getClass().getClassLoader());
    assertThat(RuntimeHintsPredicates.resource().forResource("connectors/cassandra/config.schema.json"))
        .accepts(hints);
    assertThat(RuntimeHintsPredicates.resource().forResource("connectors/cassandra/cassandra_query.input.schema.json"))
        .accepts(hints);
    assertThat(RuntimeHintsPredicates.reflection().onConstructorInvocation(NeverRetry.class.getConstructor(
        com.datastax.oss.driver.api.core.context.DriverContext.class, String.class))).accepts(hints);
  }
}
