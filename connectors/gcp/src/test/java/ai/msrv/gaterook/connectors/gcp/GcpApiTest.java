package ai.msrv.gaterook.connectors.gcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.PreparedAction;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.time.Clock;
import java.util.List;
import mockwebserver3.MockResponse;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code gcp_api_read} and {@code gcp_api_change} against fakes of Google: the Discovery directory and documents are
 * read without the token, the request is the one the document gives, the policy hides list items and the redactor
 * masks environment values; a read never runs a method the document says changes something; a change is shown to the
 * approver exactly and runs only as approved; projects under a folder scope and buckets are looked up before any
 * request reaches them.
 */
class GcpApiTest {

  private static final String SERVICE = "projects/acme-stage/locations/europe-west1/services/api";
  private FakeGoogle google;
  private GcpConnector connector;
  private final ConnectionConfig cfg = Configs.stage();

  @BeforeEach
  void start() throws Exception {
    google = new FakeGoogle();
    google.on("/discovery/v1/apis", this::directory);
    google.on("/docs/run/v2", r -> FakeGoogle.json(200, DiscoveryTest.RUN.replace("https://run.googleapis.com/",
        google.base() + "/")));
    google.on("/docs/storage/v1", r -> FakeGoogle.json(200, DiscoveryTest.STORAGE
        .replace("https://storage.googleapis.com/", google.base() + "/")));
    connector = new GcpConnector(google.cloud, Clock.systemUTC());
  }

  @AfterEach
  void stop() {
    connector.close();
    google.close();
  }

  private MockResponse directory(RecordedRequest r) {
    String name = FakeGoogle.param(r, "name");
    String version = switch (name == null ? "" : name) {
      case "run" -> "v2";
      case "storage" -> "v1";
      default -> null;
    };
    if (version == null) {
      return FakeGoogle.json(200, "{\"kind\":\"discovery#directoryList\"}");
    }
    return FakeGoogle.json(200, """
        {"items":[{"name":"%s","version":"%s","preferred":true,"discoveryRestUrl":"%s/docs/%s/%s"}]}"""
        .formatted(name, version, google.base(), name, version));
  }

  private ToolCall call(String tool, String arguments) {
    return Configs.call(tool, cfg.name(), arguments);
  }

  private ToolResult read(String arguments, PolicyView policy, TestCtx ctx) throws ConnectorException {
    ToolCall call = call("gcp_api_read", arguments);
    Classification c = connector.classify(call, cfg, policy);
    assertThat(c.kind()).as(c.reason()).isEqualTo(Classification.Kind.READ);
    return connector.execute(call, cfg, new TestSecrets(), ctx);
  }

  @Test
  void readsAListWithTheTokenFromTheDocumentsUrlMaskedAndFilteredByThePolicy() throws Exception {
    google.on("/v2/projects/acme-stage/locations/-/services", r -> FakeGoogle.json(200, """
        {"services":[
          {"name":"%s","template":{"containers":[{"env":[{"name":"DB_PASS","value":"hunter2-cnry"}]}]}},
          {"name":"projects/acme-stage/locations/us-central1/services/legacy"}],
         "nextPageToken":"page-2"}""".formatted(SERVICE)));
    TestCtx ctx = TestCtx.of();

    ToolResult result = read("""
        {"service":"run","method":"projects.locations.services.list",
         "params":{"parent":"projects/acme-stage/locations/-","pageSize":10}}""",
        Configs.policy(Env.STAGE, """
            {"resources":["//run.googleapis.com/projects/acme-stage/*/services/api"]}"""), ctx);

    JsonNode s = result.structuredContent();
    assertThat(s.path("httpMethod").asString()).isEqualTo("GET");
    assertThat(s.path("request").asString()).isEqualTo("GET /v2/projects/acme-stage/locations/-/services?pageSize=10");
    assertThat(s.at("/data/services")).hasSize(1);
    assertThat(s.at("/data/services/0/name").asString()).isEqualTo(SERVICE);
    assertThat(s.path("hiddenByPolicy").asInt()).isEqualTo(1);
    assertThat(s.path("masked").asInt()).isEqualTo(1);
    assertThat(s.toString() + result.content().getFirst().text()).doesNotContain("hunter2-cnry");
    assertThat(s.path("notes").toString()).contains("pageToken");
    OutputSchema.apiRead(result);

    RecordedRequest api = google.to("/v2/").getFirst();
    assertThat(api.getHeaders().get("Authorization")).isEqualTo("Bearer " + google.token);
    assertThat(google.to("/discovery/")).hasSize(1).allSatisfy(r ->
        assertThat(r.getHeaders().get("Authorization")).as("Discovery is public").isNull());
    assertThat(google.to("/docs/")).hasSize(1).allSatisfy(r ->
        assertThat(r.getHeaders().get("Authorization")).isNull());
    assertThat(ctx.taints).contains(google.token);
    assertThat(ctx.quotaKeys).contains("gcp:acme-stage:api@300");
  }

  @Test
  void aReadNeverRunsAMethodTheDocumentSaysChanges() throws Exception {
    String create = """
        {"service":"run","method":"projects.locations.services.patch",
         "params":{"name":"%s"},"body":{"description":"x"}}""".formatted(SERVICE);
    ToolCall call = call("gcp_api_read", create);
    assertThat(connector.classify(call, cfg, Configs.policy(Env.STAGE)).kind())
        .as("the document isn't known yet: execute decides").isEqualTo(Classification.Kind.READ);

    assertThatThrownBy(() -> connector.execute(call, cfg, new TestSecrets(), TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
          assertThat(e.getMessage()).contains("PATCH").contains("gcp_api_change");
        });
    assertThat(google.to("/v2/")).as("nothing reached Cloud Run").isEmpty();

    Classification again = connector.classify(call("gcp_api_read", create), cfg, Configs.policy(Env.STAGE));
    assertThat(again.kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(again.ruleId()).isEqualTo("stage.cloud.not_a_read");
  }

  @Test
  void aChangeIsShownExactlyAndRunsOnlyAsApproved() throws Exception {
    google.on("/v2/" + SERVICE, r -> FakeGoogle.json(200, """
        {"name":"projects/acme-stage/locations/europe-west1/operations/op-7","done":false}"""));
    String args = """
        {"service":"run","method":"projects.locations.services.patch",
         "params":{"name":"%s","updateMask":"traffic"},
         "body":{"traffic":[{"type":"TRAFFIC_TARGET_ALLOCATION_TYPE_REVISION","revision":"api-00041-abc","percent":100}]}}"""
        .formatted(SERVICE);
    ToolCall call = call("gcp_api_change", args);
    Classification c = connector.classify(call, cfg, Configs.policy(Env.STAGE));
    assertThat(c.kind()).isEqualTo(Classification.Kind.MUTATION);
    assertThat(c.reason()).isEqualTo("Calls run projects.locations.services.patch in projects/acme-stage");

    PreparedAction prepared = connector.prepare(call, cfg, new TestSecrets(), TestCtx.of());

    assertThat(prepared.outcome()).isEqualTo(PreparedAction.Outcome.APPROVAL);
    ActionPreview preview = prepared.preview();
    assertThat(preview.kind()).isEqualTo(ActionPreview.Kind.HTTP);
    assertThat(preview.text()).startsWith("PATCH " + google.base() + "/v2/" + SERVICE + "?updateMask=traffic\n"
        + "Authorization: Bearer ‹masked› (" + FakeGoogle.SA + ")\nContent-Type: application/json\n\n{");
    assertThat(preview.text()).contains("\"revision\" : \"api-00041-abc\"");
    assertThat(preview.warnings()).anySatisfy(w -> assertThat(w).contains("long-running operation"));
    assertThat(preview.auditFacets()).containsEntry("gcp.method", "projects.locations.services.patch")
        .containsEntry("gcp.project", "acme-stage");
    JsonNode payload = preview.payload();
    assertThat(payload.path("httpMethod").asString()).isEqualTo("PATCH");
    assertThat(payload.path("url").asString()).isEqualTo(google.base() + "/v2/" + SERVICE + "?updateMask=traffic");
    assertThat(patches()).as("prepare sends nothing").isEmpty();

    // Not approved: nothing runs.
    connector.classify(call, cfg, Configs.policy(Env.STAGE));
    assertThatThrownBy(() -> connector.execute(call, cfg, new TestSecrets(), TestCtx.of()))
        .hasMessageContaining("runs only after a person approved it");
    // Approved, but for another request: nothing runs.
    ObjectNode other = (ObjectNode) payload.deepCopy();
    other.put("url", payload.path("url").asString() + "&validateOnly=true");
    connector.classify(call, cfg, Configs.policy(Env.STAGE));
    assertThatThrownBy(() -> connector.execute(call, cfg, new TestSecrets(), TestCtx.of().approved(other)))
        .hasMessageContaining("doesn't match the request that was approved");
    assertThat(patches()).isEmpty();

    connector.classify(call, cfg, Configs.policy(Env.STAGE));
    ToolResult result = connector.execute(call, cfg, new TestSecrets(), TestCtx.of().approved(payload));

    assertThat(patches()).hasSize(1);
    RecordedRequest sent = patches().getFirst();
    assertThat(sent.getUrl().encodedQuery()).isEqualTo("updateMask=traffic");
    assertThat(TestCtx.JSON.readTree(sent.getBody().utf8())).isEqualTo(payload.path("body"));
    assertThat(result.structuredContent().at("/data/name").asString()).endsWith("/operations/op-7");
    assertThat(result.structuredContent().path("notes").toString()).contains("operations.get");
  }

  @Test
  void aChangeOfAReadIsRefusedBeforeAnyRequest() throws Exception {
    ToolCall call = call("gcp_api_change", """
        {"service":"run","method":"projects.locations.services.get","params":{"name":"%s"}}""".formatted(SERVICE));
    assertThat(connector.classify(call, cfg, Configs.policy(Env.STAGE)).kind())
        .isEqualTo(Classification.Kind.MUTATION);

    PreparedAction prepared = connector.prepare(call, cfg, new TestSecrets(), TestCtx.of());

    assertThat(prepared.outcome()).isEqualTo(PreparedAction.Outcome.REJECT);
    assertThat(prepared.message()).contains("only reads").contains("gcp_api_read");
    Classification again = connector.classify(call("gcp_api_change", """
        {"service":"run","method":"projects.locations.services.get","params":{"name":"%s"}}""".formatted(SERVICE)),
        cfg, Configs.policy(Env.STAGE));
    assertThat(again.ruleId()).isEqualTo("stage.cloud.is_a_read");
  }

  @Test
  void aProjectOutsideTheConnectionsProjectsMustBeUnderItsFolder() throws Exception {
    google.on("/v1/projects/acme-team:getAncestry", r -> FakeGoogle.json(200, """
        {"ancestor":[{"resourceId":{"type":"project","id":"acme-team"}},{"resourceId":{"type":"folder","id":"123456"}},
         {"resourceId":{"type":"organization","id":"42"}}]}"""));
    google.on("/v1/projects/acme-elsewhere:getAncestry", r -> FakeGoogle.json(200, """
        {"ancestor":[{"resourceId":{"type":"project","id":"acme-elsewhere"}},{"resourceId":{"type":"folder","id":"777"}}]}"""));
    google.on("/v2/projects/acme-team/", r -> FakeGoogle.json(200, "{\"services\":[]}"));

    read("""
        {"service":"run","method":"projects.locations.services.list","params":{"parent":"projects/acme-team/locations/-"}}""",
        Configs.policy(Env.STAGE), TestCtx.of());
    read("""
        {"service":"run","method":"projects.locations.services.list","params":{"parent":"projects/acme-team/locations/europe-west1"}}""",
        Configs.policy(Env.STAGE), TestCtx.of());

    assertThat(google.to("/v1/projects/acme-team:getAncestry")).as("cached for the connection version").hasSize(1);
    assertThat(google.to("/v2/projects/acme-team/")).hasSize(2);
    assertThatThrownBy(() -> read("""
        {"service":"run","method":"projects.locations.services.list","params":{"parent":"projects/acme-elsewhere/locations/-"}}""",
        Configs.policy(Env.STAGE), TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
          assertThat(e.getMessage()).contains("projects/acme-elsewhere isn't under folders/123456");
        });
    assertThat(google.to("/v2/projects/acme-elsewhere/")).isEmpty();
  }

  @Test
  void aBucketMustBelongToAProjectOfTheConnectionAndObjectNamesAreOneSegment() throws Exception {
    google.on("/storage/v1/b/acme-stage-uploads", r -> FakeGoogle.json(200, "{\"projectNumber\":\"123456789012\"}"));
    google.on("/v1/projects/acme-stage", r -> FakeGoogle.json(200, "{\"projectNumber\":\"123456789012\"}"));
    google.on("/storage/v1/b/acme-stage-uploads/o", r -> FakeGoogle.json(200, """
        {"kind":"storage#object","name":"reports/2026 q3.csv","bucket":"acme-stage-uploads","size":"42"}"""));
    google.on("/storage/v1/b/someone-elses", r -> FakeGoogle.json(200, "{\"projectNumber\":\"999\"}"));
    google.on("/v1/projects/999:getAncestry", r -> FakeGoogle.json(200, """
        {"ancestor":[{"resourceId":{"type":"project","id":"other"}},{"resourceId":{"type":"organization","id":"9"}}]}"""));

    ToolResult object = read("""
        {"service":"storage","method":"objects.get","params":{"bucket":"acme-stage-uploads","object":"reports/2026 q3.csv"}}""",
        Configs.policy(Env.STAGE), TestCtx.of());

    assertThat(object.structuredContent().at("/data/size").asString()).isEqualTo("42");
    List<RecordedRequest> sent = google.to("/storage/v1/b/acme-stage-uploads/o/");
    assertThat(sent).hasSize(1);
    assertThat(sent.getFirst().getUrl().encodedPath()).isEqualTo(
        "/storage/v1/b/acme-stage-uploads/o/reports%2F2026%20q3.csv");
    assertThatThrownBy(() -> read("""
        {"service":"storage","method":"objects.list","params":{"bucket":"someone-elses"}}""",
        Configs.policy(Env.STAGE), TestCtx.of()))
        .isInstanceOf(ConnectorException.class).hasMessageContaining("isn't under");
    assertThat(google.to("/storage/v1/b/someone-elses/o")).isEmpty();
  }

  private List<RecordedRequest> patches() {
    return google.to("/v2/" + SERVICE).stream().filter(r -> r.getMethod().equals("PATCH")).toList();
  }
}
