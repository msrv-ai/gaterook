package com.gaterook.connectors.s3.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.Connector;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class S3ConnectorsAutoConfigurationTest {

  @Test
  void registersTheConnectorThroughItsOwnImports() throws Exception {
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(
        "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")) {
      assertThat(in).isNotNull();
      assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
          .contains(S3ConnectorsAutoConfiguration.class.getName());
    }
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(S3ConnectorsAutoConfiguration.class))
        .run(ctx -> assertThat(ctx.getBeansOfType(Connector.class).values())
            .extracting(c -> c.manifest().id()).containsExactly("s3"));
  }

  @Test
  void hintsTheSchemasForTheNativeImage() {
    RuntimeHints hints = new RuntimeHints();
    new S3Hints().registerHints(hints, getClass().getClassLoader());
    assertThat(RuntimeHintsPredicates.resource().forResource("connectors/s3/config.schema.json")).accepts(hints);
    assertThat(RuntimeHintsPredicates.resource().forResource("connectors/s3/s3_read_object.input.schema.json"))
        .accepts(hints);
  }
}
