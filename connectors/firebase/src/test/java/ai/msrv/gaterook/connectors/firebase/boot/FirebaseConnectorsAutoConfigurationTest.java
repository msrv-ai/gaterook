package ai.msrv.gaterook.connectors.firebase.boot;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Connector;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class FirebaseConnectorsAutoConfigurationTest {

  @Test
  void registersBothConnectorsThroughItsOwnImports() throws Exception {
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(
        "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")) {
      assertThat(in).isNotNull();
      assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
          .contains(FirebaseConnectorsAutoConfiguration.class.getName());
    }
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(FirebaseConnectorsAutoConfiguration.class))
        .run(ctx -> assertThat(ctx.getBeansOfType(Connector.class).values())
            .extracting(c -> c.manifest().id()).containsExactlyInAnyOrder("firestore", "firebase-rtdb"));
  }

  @Test
  void hintsTheSchemasForTheNativeImage() {
    RuntimeHints hints = new RuntimeHints();
    new FirebaseHints().registerHints(hints, getClass().getClassLoader());
    for (String schema : new String[] {"connectors/firestore/config.schema.json",
        "connectors/firestore/firestore_query.input.schema.json", "connectors/firebase-rtdb/config.schema.json",
        "connectors/firebase-rtdb/rtdb_write.input.schema.json"}) {
      assertThat(RuntimeHintsPredicates.resource().forResource(schema)).as(schema).accepts(hints);
    }
  }
}
