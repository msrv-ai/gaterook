package ai.msrv.gaterook.connectors.http.boot;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connectors.support.TemplateImporter;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class HttpConnectorsAutoConfigurationTest {

  @Test
  void isListedInTheModulesAutoConfigurationImports() {
    assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).getCandidates())
        .contains(HttpConnectorsAutoConfiguration.class.getName());
  }

  @Test
  void registersTheConnectorAndItsOpenApiImport() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(HttpConnectorsAutoConfiguration.class))
        .run(ctx -> {
          assertThat(ctx.getBeansOfType(Connector.class).values())
              .extracting(c -> c.manifest().id())
              .containsExactly("http");
          assertThat(ctx.getBeansOfType(TemplateImporter.class).values())
              .extracting(TemplateImporter::kind)
              .containsExactly("openapi");
        });
  }

  @Test
  void schemasAreNativeImageResources() {
    RuntimeHints hints = new RuntimeHints();
    new HttpHints().registerHints(hints, getClass().getClassLoader());

    assertThat(RuntimeHintsPredicates.resource().forResource("connectors/http/http_get.input.schema.json"))
        .accepts(hints);
    assertThat(RuntimeHintsPredicates.resource().forResource("connectors/http/config.schema.json"))
        .accepts(hints);
    assertThat(RuntimeHintsPredicates.resource().forResource("connectors/http/openapi.definition.schema.json"))
        .accepts(hints);
  }
}
