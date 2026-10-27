package ai.msrv.gaterook.connectors.gcp.boot;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Connector;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class GcpConnectorsAutoConfigurationTest {

  @Test
  void isListedInTheModulesAutoConfigurationImports() {
    assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).getCandidates())
        .contains(GcpConnectorsAutoConfiguration.class.getName());
  }

  @Test
  void registersTheConnector() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(GcpConnectorsAutoConfiguration.class))
        .run(ctx -> assertThat(ctx.getBeansOfType(Connector.class).values())
            .extracting(c -> c.manifest().id())
            .containsExactly("gcp"));
  }

  @Test
  void schemasAreNativeImageResources() {
    RuntimeHints hints = new RuntimeHints();
    new GcpHints().registerHints(hints, getClass().getClassLoader());

    assertThat(RuntimeHintsPredicates.resource().forResource("connectors/gcp/gcp_describe.input.schema.json"))
        .accepts(hints);
    assertThat(RuntimeHintsPredicates.resource().forResource("connectors/gcp/config.schema.json"))
        .accepts(hints);
  }
}
