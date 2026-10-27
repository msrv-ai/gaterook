package ai.msrv.gaterook.connectors.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.TemplateKind;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.connector.api.ToolDefinition.Nature;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

class HttpConnectorTest {

  private final HttpConnector connector = new HttpConnector();

  @Test
  void manifestIsTheCatalogCard() {
    ConnectorManifest m = connector.manifest();

    assertThat(m.id()).isEqualTo("http");
    assertThat(m.kind()).isEqualTo(ConnectorManifest.Kind.BUILT_IN);
    assertThat(m.name()).isEqualTo("HTTP API");
    assertThat(m.group()).isEqualTo("HTTP and MCP");
    assertThat(m.icon()).isEqualTo("globe");
    assertThat(m.configSchema().path("type").asString()).isEqualTo("object");
    assertThat(m.policySchema()).isNotNull();
  }

  @Test
  void publishesTheToolsOfTheKit() {
    List<ToolDefinition> tools = connector.manifest().tools();

    assertThat(tools).extracting(ToolDefinition::name)
        .containsExactly("http_get", "http_request");
    assertTool(tools.get(0), "http_get", Nature.READ, Mode.READ_ONLY, Mode.OFF, true);
    assertTool(tools.get(1), "http_request", Nature.MIXED, Mode.APPROVAL, Mode.APPROVAL, false);
  }

  private static void assertTool(ToolDefinition t, String name, Nature nature, Mode dev, Mode prod, boolean output) {
    assertThat(t.name()).isEqualTo(name);
    assertThat(t.nature()).as(name).isEqualTo(nature);
    assertThat(t.defaultMode()).as(name + " dev/stage mode").isEqualTo(dev);
    assertThat(t.prodDefaultMode()).as(name + " prod mode").isEqualTo(prod);
    assertThat(t.outputSchema() != null).as(name + " outputSchema").isEqualTo(output);
    assertThat(t.inputSchema().at("/properties/connection/type").asString()).as(name).isEqualTo("string");
    assertThat(t.inputSchema().at("/required").toString()).as(name).contains("\"connection\"");
    assertThat(t.annotations().readOnlyHint()).as(name + " readOnlyHint").isEqualTo(nature == Nature.READ);
  }

  @Test
  void anUnusableConfigurationIsDeniedAndNeverSent() {
    ToolCall call = new ToolCall("tc_1", "http_get", "billing-api", JsonNodeFactory.instance.objectNode(), null);
    ConnectionConfig cfg = new ConnectionConfig("c1", "billing-api", "http", Env.DEV, 1,
        JsonNodeFactory.instance.objectNode(), Set.of(), null);

    Classification c = connector.classify(call, cfg, new PolicyView(Env.DEV, "dev.http", null, null));

    assertThat(c.kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(c.ruleId()).isEqualTo("dev.http.invalid-config");
    assertThatThrownBy(() -> connector.execute(call, cfg, null, null))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT));
    assertThat(connector.test(cfg, null, null).ok()).isFalse();
  }

  @Test
  void offersTheOpenApiTemplateKindForItsOwnConnections() {
    assertThat(connector.templateKinds()).extracting(TemplateKind::kind).containsExactly("openapi");
    assertThat(connector.templateKinds().getFirst().connectionTypes()).containsExactly("http");
    assertThat(connector.openApiImporter().kind()).isEqualTo("openapi");
  }

  @Test
  void describesWhereRequestsMayGoWithoutASecret() throws Exception {
    ConnectionConfig cfg = Fixtures.cfg("billing-api", Env.STAGE, """
        {"baseUrls":["https://billing.stage.acme.dev/v1"],"auth":{"type":"bearer"},
         "allowedPaths":["/invoices/**"],"mutatingGetPaths":["/export/run"]}""");

    var d = connector.describe(cfg, null, null, null).orElseThrow();

    assertThat(d.path("baseUrls").toString()).isEqualTo("[\"https://billing.stage.acme.dev/v1\"]");
    assertThat(d.path("allowedPaths").toString()).isEqualTo("[\"/invoices/**\"]");
    assertThat(d.path("mutatingGetPaths").toString()).isEqualTo("[\"/export/run\"]");
    assertThat(d.path("auth").asString()).isEqualTo("bearer");
    assertThat(d.path("followRedirects").asString()).isEqualTo("off");
  }
}
