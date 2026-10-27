package ai.msrv.gaterook.connectors.aws.boot;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Connector;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AwsConnectorsAutoConfigurationTest {

  @Test
  void isListedInTheModulesAutoConfigurationImports() {
    assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).getCandidates())
        .contains(AwsConnectorsAutoConfiguration.class.getName());
  }

  @Test
  void registersTheConnector() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AwsConnectorsAutoConfiguration.class))
        .run(ctx -> assertThat(ctx.getBeansOfType(Connector.class).values())
            .extracting(c -> c.manifest().id())
            .containsExactly("aws"));
  }

  @Test
  void schemasAreNativeImageResources() {
    RuntimeHints hints = new RuntimeHints();
    new AwsHints().registerHints(hints, getClass().getClassLoader());

    assertThat(RuntimeHintsPredicates.resource().forResource("connectors/aws/aws_describe.input.schema.json"))
        .accepts(hints);
    assertThat(RuntimeHintsPredicates.resource().forResource("connectors/aws/config.schema.json"))
        .accepts(hints);
  }
}
