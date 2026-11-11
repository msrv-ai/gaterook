package ai.msrv.gaterook.connectors.cosmosdb.boot;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.ToolCall;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

class CosmosDbConnectorsAutoConfigurationTest {

  @Test
  void registersTheConnectorThroughItsOwnImports() throws Exception {
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(
        "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")) {
      assertThat(in).isNotNull();
      assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
          .contains(CosmosDbConnectorsAutoConfiguration.class.getName());
    }
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CosmosDbConnectorsAutoConfiguration.class))
        .run(ctx -> assertThat(ctx.getBeansOfType(Connector.class).values())
            .extracting(c -> c.manifest().id()).containsExactly("cosmosdb"));
  }

  @Test
  void plainHttpToTheEmulatorOnlyWhenTheCoreSaysItIsNoRelease() {
    assertThat(CosmosDbConnectorsAutoConfiguration.plainLoopback(new MockEnvironment()
        .withProperty("gateway.release", "false"))).isTrue();
    assertThat(CosmosDbConnectorsAutoConfiguration.plainLoopback(new MockEnvironment()
        .withProperty("gateway.release", "true"))).isFalse();
    // Without the core's word the build counts as a release.
    assertThat(CosmosDbConnectorsAutoConfiguration.plainLoopback(new MockEnvironment())).isFalse();
    ConnectionConfig emulator = new ConnectionConfig("c", "emu", "cosmosdb", Env.DEV, 1, JsonMapper.builder().build()
        .readTree("{\"endpoint\": \"http://127.0.0.1:8081\", \"auth\": {\"method\": \"key\", \"keyKind\": "
            + "\"readWrite\"}}"), Set.of(), null);
    ToolCall list = new ToolCall("tc", "cosmosdb_list_databases", "emu", JsonNodeFactory.instance.objectNode(), null);
    PolicyView policy = new PolicyView(Env.DEV, "dev.cosmosdb", (ObjectNode) null, null);
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CosmosDbConnectorsAutoConfiguration.class))
        .withPropertyValues("gateway.release=false")
        .run(ctx -> assertThat(ctx.getBean(Connector.class).classify(list, emulator, policy).kind())
            .isEqualTo(Classification.Kind.READ));
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CosmosDbConnectorsAutoConfiguration.class))
        .run(ctx -> assertThat(ctx.getBean(Connector.class).classify(list, emulator, policy).ruleId())
            .isEqualTo("dev.cosmosdb.bad-config"));
  }

  @Test
  void hintsTheSchemasForTheNativeImage() {
    RuntimeHints hints = new RuntimeHints();
    new CosmosDbHints().registerHints(hints, getClass().getClassLoader());
    for (String schema : new String[] {"connectors/cosmosdb/config.schema.json",
        "connectors/cosmosdb/cosmosdb_query.input.schema.json", "connectors/cosmosdb/cosmosdb_execute.input.schema.json"}) {
      assertThat(RuntimeHintsPredicates.resource().forResource(schema)).as(schema).accepts(hints);
    }
  }
}
