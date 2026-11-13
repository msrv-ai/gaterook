package com.gaterook.connectors.databricks.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.Connector;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DatabricksConnectorsAutoConfigurationTest {

  @Test
  void registersTheConnectorThroughItsOwnImports() throws Exception {
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(
        "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")) {
      assertThat(in).isNotNull();
      assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
          .contains(DatabricksConnectorsAutoConfiguration.class.getName());
    }
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(DatabricksConnectorsAutoConfiguration.class))
        .run(ctx -> assertThat(ctx.getBeansOfType(Connector.class).values())
            .extracting(c -> c.manifest().id()).containsExactly("databricks"));
  }

  @Test
  void hintsTheSchemasForTheNativeImage() {
    RuntimeHints hints = new RuntimeHints();
    new DatabricksHints().registerHints(hints, getClass().getClassLoader());
    assertThat(RuntimeHintsPredicates.resource().forResource("connectors/databricks/config.schema.json")).accepts(hints);
    assertThat(RuntimeHintsPredicates.resource().forResource("connectors/databricks/databricks_query.input.schema.json"))
        .accepts(hints);
  }
}
