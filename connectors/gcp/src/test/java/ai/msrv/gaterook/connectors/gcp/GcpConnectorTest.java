package ai.msrv.gaterook.connectors.gcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.EndpointView;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.connector.api.ToolDefinition.Nature;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class GcpConnectorTest {

  private static final Instant NOW = Instant.parse("2026-10-22T12:30:00Z");
  private final CloudCredentials cloud = new CloudCredentials(CloudEndpoints.PRODUCTION, Clock.systemUTC());
  private final GcpConnector connector = new GcpConnector(cloud, Clock.fixed(NOW, ZoneOffset.UTC));

  @AfterEach
  void close() {
    connector.close();
    cloud.close();
  }

  @Test
  void manifestIsTheCatalogCard() {
    ConnectorManifest m = connector.manifest();

    assertThat(m.id()).isEqualTo("gcp");
    assertThat(m.kind()).isEqualTo(ConnectorManifest.Kind.BUILT_IN);
    assertThat(m.name()).isEqualTo("GCP");
    assertThat(m.group()).isEqualTo("Cloud");
    assertThat(m.icon()).isEqualTo("cloud");
    assertThat(m.description()).isEqualTo("Resources, statuses and metrics, read through service account "
        + "impersonation.");
    assertThat(m.configSchema().path("type").asString()).isEqualTo("object");
    assertThat(m.policySchema().path("x-policy-section").asString()).isEqualTo("cloud");
    assertThat(m.access().files()).containsExactly("~/.config/gcloud/application_default_credentials.json");
  }

  @Test
  void publishesTheToolsOfTheKit() {
    List<ToolDefinition> tools = connector.manifest().tools();

    assertThat(tools).extracting(ToolDefinition::name).containsExactly("gcp_describe", "gcp_metrics");
    for (ToolDefinition t : tools) {
      assertThat(t.nature()).as(t.name()).isEqualTo(Nature.READ);
      assertThat(t.defaultMode()).isEqualTo(Mode.READ_ONLY);
      assertThat(t.prodDefaultMode()).isEqualTo(Mode.READ_ONLY);
      assertThat(t.outputSchema()).isNotNull();
      assertThat(t.annotations()).isEqualTo(ToolDefinition.Annotations.read());
      assertThat(t.inputSchema().at("/properties/connection").toString()).isEqualTo("{\"type\":\"string\"}");
      assertThat(t.inputSchema().at("/required").toString()).contains("\"connection\"");
      assertThat(t.readOnlyModeAllowed()).isTrue();
    }
  }

  @Test
  void anEndpointsToolsNameItsScopesAndProjects() {
    ConnectionConfig other = Configs.of("gcp-dev", Env.DEV, """
        {"scopes":["projects/acme-dev"],"credentials":{"source":"adc"},"liveGet":false}""");
    EndpointView view = new EndpointView("ops", Env.STAGE, List.of(Configs.stage(), other), Map.of(), List.of());

    List<ToolDefinition> tools = connector.tools(view);

    ToolDefinition describe = tools.get(0);
    assertThat(describe.description()).endsWith(" Scopes: gcp-dev: projects/acme-dev (no live reads); gcp-stage: "
        + "projects/acme-stage, folders/123456. Live reads: compute, run, cloudsql, container, pubsub, storage, "
        + "cloudfunctions, redis.");
    assertThat(describe.inputSchema().at("/properties/scope/enum").toString())
        .isEqualTo("[\"folders/123456\",\"projects/acme-dev\",\"projects/acme-stage\"]");
    assertThat(describe.inputSchema().at("/properties/connection").toString()).isEqualTo("{\"type\":\"string\"}");
    assertThat(tools.get(1).description()).endsWith(" Projects: gcp-dev: acme-dev; gcp-stage: acme-stage.");
    assertThat(tools.get(1).inputSchema().at("/properties/project/enum").toString())
        .isEqualTo("[\"acme-dev\",\"acme-stage\"]");
    assertThat(connector.manifest().tools().get(0).inputSchema().at("/properties/scope/enum").isMissingNode())
        .as("the manifest's schema stays as it is").isTrue();
  }

  @Test
  void executeWithoutTheDecisionOfClassifyRunsNothing() {
    ToolCall call = Configs.call("gcp_describe", "gcp-stage", "{}");

    assertThatThrownBy(() -> connector.execute(call, Configs.stage(), new TestSecrets(), TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
          assertThat(e.getMessage()).contains("no policy decision");
        });
    // A decision is for one connection version: the connection changed between classify and execute.
    connector.classify(call, Configs.stage(), Configs.policy(Env.STAGE));
    ConnectionConfig edited = new ConnectionConfig("c-gcp-stage", "gcp-stage", "gcp", Env.STAGE, 2,
        Configs.stage().config(), java.util.Set.of(), null);
    assertThatThrownBy(() -> connector.execute(call, edited, new TestSecrets(), TestCtx.of()))
        .hasMessageContaining("no policy decision");
  }

  @Test
  void theApproverSeesTheRequestsExactlyAsTheyGo() {
    ActionPreview search = connector.describeAction(Configs.call("gcp_describe", "gcp-stage", """
        {"assetTypes":["run.googleapis.com/Service"],"query":"state=ACTIVE","limit":26}"""), Configs.stage());
    ActionPreview get = connector.describeAction(Configs.call("gcp_describe", "gcp-stage", """
        {"name":"//run.googleapis.com/projects/acme-stage/locations/europe-west1/services/api"}"""), Configs.stage());
    ActionPreview metrics = connector.describeAction(Configs.call("gcp_metrics", "gcp-stage", """
        {"metric":"run.googleapis.com/request_count","filter":"resource.labels.service_name = \\"api\\"","from":"-1h"}"""),
        Configs.stage());

    assertThat(search.kind()).isEqualTo(ActionPreview.Kind.TEXT);
    assertThat(search.text()).isEqualTo("""
        gcp_describe · gcp-stage (GCP · projects/acme-stage +1 · stage)
        searchAllResources projects/acme-stage assetTypes=[run.googleapis.com/Service] query="state=ACTIVE" pageSize=26""");
    assertThat(search.payload().path("limit").asInt()).isEqualTo(26);
    assertThat(get.text()).isEqualTo("""
        gcp_describe · gcp-stage (GCP · projects/acme-stage +1 · stage)
        searchAllResources projects/acme-stage, folders/123456 query=name:"api" (finds //run.googleapis.com/projects/acme-stage/locations/europe-west1/services/api)
        GET https://run.googleapis.com/v2/projects/acme-stage/locations/europe-west1/services/api""");
    assertThat(metrics.text()).isEqualTo("""
        gcp_metrics · gcp-stage (GCP · acme-stage · stage)
        timeSeries.list projects/acme-stage
        filter: metric.type = "run.googleapis.com/request_count" AND (resource.labels.service_name = "api")
        interval: 2026-10-22T11:30:00Z → 2026-10-22T12:30:00Z (1 h) · period 60 s
        aligner: by the metric's kind (ALIGN_MEAN for gauges, ALIGN_RATE for counters) · reducer REDUCE_NONE
        series: up to 20""");
    JsonNode payload = metrics.payload();
    assertThat(payload.path("from").asString()).isEqualTo("2026-10-22T11:30:00Z");
    assertThat(payload.path("period").asInt()).isEqualTo(60);
    assertThat(metrics.summary()).isEqualTo("gcp_metrics on gcp-stage: run.googleapis.com/request_count");
  }

  @Test
  void describeConnectionToldWithoutAnyRequest() throws Exception {
    JsonNode d = connector.describe(Configs.stage(), new TestSecrets(), TestCtx.of(), null).orElseThrow();

    assertThat(d.path("scopes").toString()).isEqualTo("[\"projects/acme-stage\",\"folders/123456\"]");
    assertThat(d.path("metricsProjects").toString()).isEqualTo("[\"acme-stage\"]");
    assertThat(d.path("identity").asString()).isEqualTo(FakeGoogle.SA);
    assertThat(d.path("liveServices")).hasSize(8);
    assertThat(d.at("/exampleSearches/0/assetTypes/0").asString()).isEqualTo("run.googleapis.com/Service");
  }
}
