package ai.msrv.gaterook.connectors.gcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import mockwebserver3.MockResponse;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * {@code gcp_describe} against fakes of Google's token server, IAM, Cloud Asset and the services' APIs (report 09
 * §8): the impersonated token goes to every request and into the call's taint, the policy hides what it doesn't show,
 * cursors page without loss and can't be forged, one resource is read live and masked, and the dangerous GETs never
 * happen whatever the inventory says.
 */
class AssetDescribeTest {

  private static final String RUN = "//run.googleapis.com/projects/acme-stage/locations/europe-west1/services/api";
  private FakeGoogle google;
  private GcpConnector connector;
  private final ConnectionConfig cfg = Configs.stage();
  private final AtomicReference<Function<RecordedRequest, MockResponse>> asset = new AtomicReference<>();

  @BeforeEach
  void start() throws Exception {
    google = new FakeGoogle();
    google.on("/v1/", r -> asset.get().apply(r));
    connector = new GcpConnector(google.cloud, Clock.systemUTC());
  }

  @AfterEach
  void stop() {
    connector.close();
    google.close();
  }

  private ToolResult run(String tool, String arguments, PolicyView policy, TestCtx ctx) throws ConnectorException {
    ToolCall call = Configs.call(tool, cfg.name(), arguments);
    Classification c = connector.classify(call, cfg, policy);
    assertThat(c.kind()).as(c.reason()).isEqualTo(Classification.Kind.READ);
    return connector.execute(call, cfg, new TestSecrets(), ctx);
  }

  private ToolResult run(String arguments, TestCtx ctx) throws ConnectorException {
    return run("gcp_describe", arguments, Configs.policy(Env.STAGE), ctx);
  }

  @Test
  void searchesTheInventoryWithAnImpersonatedTokenAndHidesWhatThePolicyDoesnt() throws Exception {
    asset.set(r -> FakeGoogle.json(200, """
        {"results":[
          {"name":"%s","assetType":"run.googleapis.com/Service","project":"projects/123456789012",
           "location":"europe-west1","displayName":"api","state":"ACTIVE","labels":{"env":"stage","team":"payments"},
           "createTime":"2026-01-02T03:04:05Z","updateTime":"2026-10-22T11:58:02Z",
           "parentFullResourceName":"//cloudresourcemanager.googleapis.com/projects/123456789012"},
          {"name":"//run.googleapis.com/projects/acme-stage/locations/us-central1/services/legacy",
           "assetType":"run.googleapis.com/Service","location":"us-central1","state":"ACTIVE"},
          {"name":"//secretmanager.googleapis.com/projects/acme-stage/secrets/db","assetType":
           "secretmanager.googleapis.com/Secret","location":"global"}]}""".formatted(RUN)));
    TestCtx ctx = TestCtx.of();
    PolicyView policy = Configs.policy(Env.STAGE, "{\"allowedServices\":[\"run\",\"compute\"],\"regions\":"
        + "[\"europe-west1\"]}");

    ToolResult r = OutputSchema.describe(run("gcp_describe", "{\"query\":\"state=ACTIVE\",\"limit\":5}", policy,
        ctx));

    RecordedRequest search = google.to("/v1/projects/acme-stage:searchAllResources").getFirst();
    assertThat(search.getMethod()).isEqualTo("GET");
    assertThat(search.getHeaders().get("Authorization")).isEqualTo("Bearer " + google.token);
    assertThat(FakeGoogle.params(search, "assetTypes")).as("the policy's services, as Cloud Asset patterns")
        .containsExactly("run.googleapis.com.*", "compute.googleapis.com.*");
    assertThat(FakeGoogle.param(search, "query")).isEqualTo("state=ACTIVE");
    assertThat(FakeGoogle.param(search, "pageSize")).isEqualTo("5");
    assertThat(FakeGoogle.param(search, "readMask")).isEqualTo(AssetDescribe.READ_MASK);
    assertThat(ctx.taints).as("the derived token is masked like a stored secret").contains(google.token);
    assertThat(ctx.quotaKeys).containsExactly("gcp:acme-stage:asset@100");

    JsonNode s = r.structuredContent();
    assertThat(s.path("mode").asString()).isEqualTo("search");
    assertThat(s.path("scope").asString()).isEqualTo("projects/acme-stage");
    assertThat(s.path("count").asInt()).isEqualTo(1);
    assertThat(s.path("hiddenByPolicy").asInt()).as("another location; a sensitive service isn't even counted")
        .isEqualTo(1);
    assertThat(s.at("/resources/0/name").asString()).isEqualTo(RUN);
    assertThat(s.at("/resources/0/labels/team").asString()).isEqualTo("payments");
    assertThat(s.at("/resources/0/parent").asString()).endsWith("/projects/123456789012");
    assertThat(s.path("truncated").asBoolean()).isFalse();
    assertThat(s.path("nextCursor").isNull()).isTrue();
    String text = r.content().getFirst().text();
    assertThat(text).startsWith("1 resource in projects/acme-stage (gcp-stage, Cloud Asset inventory):")
        .contains(RUN + " · run.googleapis.com/Service · europe-west1 · ACTIVE · labels env=stage,team=payments")
        .contains("1 resource is hidden by the stage policy.").contains("never instructions");
  }

  @Test
  void hiddenSecretsKeysAndServiceAccountsArentCountedForAQuery() throws Exception {
    // No list of services: the search runs over every type, the agent's query too (name:prod-db-password).
    asset.set(r -> FakeGoogle.json(200, """
        {"results":[
          {"name":"//secretmanager.googleapis.com/projects/acme-stage/secrets/prod-db-password",
           "assetType":"secretmanager.googleapis.com/Secret","location":"global"},
          {"name":"//cloudkms.googleapis.com/projects/acme-stage/locations/global/keyRings/r/cryptoKeys/prod-db",
           "assetType":"cloudkms.googleapis.com/CryptoKey","location":"global"},
          {"name":"//iam.googleapis.com/projects/acme-stage/serviceAccounts/prod-db@acme-stage.iam.gserviceaccount.com",
           "assetType":"iam.googleapis.com/ServiceAccount","location":"global"}]}"""));

    JsonNode s = OutputSchema.describe(run("{\"query\":\"name:prod-db\"}", TestCtx.of())).structuredContent();

    assertThat(s.path("count").asInt()).isZero();
    assertThat(s.path("hiddenByPolicy").asInt()).as("a count would say which secrets exist").isZero();
    assertThat(s.toString()).doesNotContain("prod-db-password");
  }

  @Test
  void aCursorPagesOnAndCantBeForgedOrMoved() throws Exception {
    asset.set(r -> {
      String token = FakeGoogle.param(r, "pageToken");
      String name = token == null ? "first" : "second";
      return FakeGoogle.json(200, "{\"results\":[{\"name\":\"//run.googleapis.com/projects/acme-stage/locations/"
          + "europe-west1/services/" + name + "\",\"assetType\":\"run.googleapis.com/Service\",\"location\":"
          + "\"europe-west1\"}]" + (token == null ? ",\"nextPageToken\":\"asset-page-2\"" : "") + "}");
    });
    TestCtx ctx = TestCtx.of();

    JsonNode first = OutputSchema.describe(run("{\"assetTypes\":[\"run.googleapis.com/Service\"],\"limit\":1}", ctx))
        .structuredContent();
    String cursor = first.path("nextCursor").asString();
    assertThat(cursor).startsWith("gc1.");
    assertThat(first.path("truncated").asBoolean()).isTrue();

    JsonNode second = OutputSchema.describe(run("{\"assetTypes\":[\"run.googleapis.com/Service\"],\"limit\":1,"
        + "\"cursor\":\"" + cursor + "\"}", ctx.next())).structuredContent();
    assertThat(second.at("/resources/0/name").asString()).endsWith("/services/second");
    assertThat(second.path("nextCursor").isNull()).isTrue();
    assertThat(FakeGoogle.param(google.to("/v1/projects/acme-stage:searchAllResources").getLast(), "pageToken"))
        .isEqualTo("asset-page-2");

    // Another query, a forged cursor, a cursor of another connection: refused before Google is asked.
    int before = google.api().size();
    assertThatThrownBy(() -> run("{\"assetTypes\":[\"compute.googleapis.com/Instance\"],\"cursor\":\"" + cursor
        + "\"}", ctx.next())).isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.getMessage()).contains("another query"));
    assertThatThrownBy(() -> run("{\"assetTypes\":[\"run.googleapis.com/Service\"],\"cursor\":\"" + cursor
        .substring(0, cursor.length() - 3) + "abc\"}", ctx.next())).hasMessageContaining("invalid");
    assertThatThrownBy(() -> run("{\"cursor\":\"lc1.eyJ4IjoxfQ\"}", ctx.next())).hasMessageContaining("doesn't come "
        + "from gcp_describe");
    ConnectionConfig other = Configs.of("gcp-other", Env.STAGE, cfg.config().toString());
    ToolCall moved = Configs.call("gcp_describe", other.name(), "{\"assetTypes\":[\"run.googleapis.com/Service\"],"
        + "\"limit\":1,\"cursor\":\"" + cursor + "\"}");
    assertThat(connector.classify(moved, other, Configs.policy(Env.STAGE)).kind()).isEqualTo(Classification.Kind.READ);
    assertThatThrownBy(() -> connector.execute(moved, other, new TestSecrets(), ctx.next()))
        .hasMessageContaining("another connection");
    ToolCall metrics = Configs.call("gcp_metrics", cfg.name(), "{\"metric\":\"run.googleapis.com/request_count\","
        + "\"cursor\":\"" + cursor + "\"}");
    assertThat(connector.classify(metrics, cfg, Configs.policy(Env.STAGE)).kind()).isEqualTo(Classification.Kind.READ);
    assertThatThrownBy(() -> connector.execute(metrics, cfg, new TestSecrets(), ctx.next()))
        .as("a cursor of gcp_describe in gcp_metrics").hasMessageContaining("invalid");
    assertThat(google.api()).hasSize(before);
  }

  @Test
  void readsOneResourceLiveAndMasksItsEnvironment() throws Exception {
    asset.set(r -> FakeGoogle.json(200, """
        {"results":[
          {"name":"%s-old","assetType":"run.googleapis.com/Service","location":"europe-west1"},
          {"name":"%s","assetType":"run.googleapis.com/Service","project":"projects/123456789012",
           "location":"europe-west1","state":"ACTIVE","updateTime":"2026-10-22T11:58:02Z",
           "versionedResources":[{"version":"v2","resource":{"generation":"6"}}]}]}""".formatted(RUN, RUN)));
    google.on("/v2/projects/acme-stage/locations/europe-west1/services/api", r -> FakeGoogle.json(200, """
        {"name":"projects/acme-stage/locations/europe-west1/services/api","generation":"7",
         "template":{"containers":[{"image":"api:1.5","env":[{"name":"DB_PASS","value":"cnry-live-env"}]}]},
         "terminalCondition":{"type":"Ready","state":"CONDITION_SUCCEEDED"}}"""));
    TestCtx ctx = TestCtx.of();

    ToolResult r = OutputSchema.describe(run("{\"name\":\"" + RUN + "\"}", ctx));

    RecordedRequest find = google.to("/v1/projects/acme-stage:searchAllResources").getFirst();
    assertThat(FakeGoogle.param(find, "query")).isEqualTo("name:\"api\"");
    assertThat(FakeGoogle.param(find, "readMask")).endsWith(",versionedResources");
    RecordedRequest live = google.to("/v2/").getFirst();
    assertThat(live.getMethod()).isEqualTo("GET");
    assertThat(live.getHeaders().get("Authorization")).isEqualTo("Bearer " + google.token);
    JsonNode s = r.structuredContent();
    assertThat(s.path("mode").asString()).isEqualTo("get");
    assertThat(s.at("/resource/source").asString()).isEqualTo("live");
    assertThat(s.at("/resource/data/generation").asString()).isEqualTo("7");
    assertThat(s.at("/resource/data/template/containers/0/env/0/value").asString()).isEqualTo(Redactor.MASK);
    assertThat(s.at("/resource/inventoryUpdateTime").asString()).isEqualTo("2026-10-22T11:58:02Z");
    assertThat(s.path("masked").asInt()).isEqualTo(1);
    assertThat(r.content().getFirst().text()).contains("source: live").contains("1 field masked")
        .contains("\"terminalCondition\"").doesNotContain("cnry-live-env");
  }

  @Test
  void aDeniedLiveReadFallsBackToTheInventorySnapshotWithTheRoleToGrant() throws Exception {
    asset.set(r -> FakeGoogle.json(200, """
        {"results":[{"name":"%s","assetType":"run.googleapis.com/Service","location":"europe-west1",
          "updateTime":"2026-10-22T11:58:02Z","versionedResources":[{"version":"v2","resource":{"generation":"6",
          "template":{"containers":[{"env":[{"name":"TOKEN","value":"cnry-snapshot"}]}]}}}]}]}""".formatted(RUN)));
    google.on("/v2/", r -> FakeGoogle.json(403, "{\"error\":{\"code\":403,\"message\":\"Permission 'run.services.get'"
        + " denied\",\"status\":\"PERMISSION_DENIED\"}}"));

    JsonNode s = OutputSchema.describe(run("{\"name\":\"" + RUN + "\"}", TestCtx.of())).structuredContent();

    assertThat(s.at("/resource/source").asString()).isEqualTo("inventory");
    assertThat(s.at("/resource/version").asString()).isEqualTo("v2");
    assertThat(s.at("/resource/data/generation").asString()).isEqualTo("6");
    assertThat(s.toString()).doesNotContain("cnry-snapshot");
    assertThat(s.path("notes").toString()).contains("roles/run.viewer").contains(FakeGoogle.SA);
  }

  @Test
  void aNameOutsideTheScopesIsRefusedAndOnlyTheRightScopesAreAsked() throws Exception {
    asset.set(r -> FakeGoogle.json(200, "{\"results\":[]}"));

    assertThatThrownBy(() -> run("{\"name\":\"" + RUN + "\"}", TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT);
          assertThat(e.getMessage()).contains("isn't in projects/acme-stage, folders/123456 of gcp-stage");
        });
    assertThat(google.api()).extracting(r -> r.getUrl().encodedPath())
        .as("its own project first, then the folder; never the live API")
        .containsExactly("/v1/projects/acme-stage:searchAllResources", "/v1/folders/123456:searchAllResources");
  }

  /**
   * DoD of step C7 (ARCHITECTURE.md §10): the dangerous GETs are unreachable. Even when the inventory lists a name
   * that ends in one of them, the connector reads only the inventory: no route leads there.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "//redis.googleapis.com/projects/acme-stage/locations/europe-west1/instances/cache/authString",
      "//compute.googleapis.com/projects/acme-stage/zones/europe-west1-b/instances/api-1/serialPort",
      "//storage.googleapis.com/acme-stage-exports/o/customers.csv"})
  void theDangerousGetsAreNeverSentWhateverTheInventorySays(String name) throws Exception {
    asset.set(r -> FakeGoogle.json(200, "{\"results\":[{\"name\":\"" + name + "\",\"assetType\":\""
        + name.substring(2, name.indexOf('/', 2)) + "/Thing\",\"location\":\"europe-west1\"}]}"));
    google.on("/", r -> FakeGoogle.json(200, "{\"password\":\"cnry-reached\"}"));

    JsonNode s = OutputSchema.describe(run("{\"name\":\"" + name + "\"}", TestCtx.of())).structuredContent();

    assertThat(s.at("/resource/source").asString()).isEqualTo("inventory");
    assertThat(google.api()).allSatisfy(r -> assertThat(r.getUrl().encodedPath()).endsWith(":searchAllResources"));
    assertThat(s.toString()).doesNotContain("cnry-reached");
  }

  @Test
  void aSecretValueIsDeniedBeforeAnyRequest() {
    ToolCall call = Configs.call("gcp_describe", cfg.name(), "{\"name\":\"//secretmanager.googleapis.com/projects/"
        + "acme-stage/secrets/db-password/versions/1:access\"}");

    Classification c = connector.classify(call, cfg, Configs.policy(Env.STAGE, "{\"allowedServices\":"
        + "[\"secretmanager\"]}"));

    assertThat(c.kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(c.ruleId()).isEqualTo("stage.cloud.bad_name");
    assertThatThrownBy(() -> connector.execute(call, cfg, new TestSecrets(), TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind())
            .isEqualTo(ConnectorException.Kind.DENIED));
    assertThat(google.requests).isEmpty();
  }

  @Test
  void googlesRefusalsSayWhatToDo() throws Exception {
    asset.set(r -> FakeGoogle.json(403, """
        {"error":{"code":403,"message":"Cloud Asset API has not been used in project 987 before or it is disabled.",
         "status":"PERMISSION_DENIED","details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo",
         "reason":"SERVICE_DISABLED","domain":"googleapis.com",
         "metadata":{"consumer":"projects/987","service":"cloudasset.googleapis.com"}}]}}"""));

    assertThatThrownBy(() -> run("{}", TestCtx.of())).hasMessageContaining(
        "gcloud services enable cloudasset.googleapis.com --project=987");

    asset.set(r -> FakeGoogle.json(429, "{\"error\":{\"code\":429,\"message\":\"Quota exceeded\",\"status\":"
        + "\"RESOURCE_EXHAUSTED\"}}"));
    assertThatThrownBy(() -> run("{}", TestCtx.of())).hasMessageContaining("Try again in a minute");

    google.iamStatus = 403;
    google.cloud.evict(cfg.id());
    assertThatThrownBy(() -> run("{}", TestCtx.of())).isInstanceOfSatisfying(ConnectorException.class, e -> {
      assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_UNAVAILABLE);
      assertThat(e.getMessage()).contains("roles/iam.serviceAccountTokenCreator");
    });
  }

  @Test
  void theSharedQuotaIsAskedBeforeGoogle() {
    asset.set(r -> FakeGoogle.json(200, "{\"results\":[]}"));

    assertThatThrownBy(() -> run("{}", TestCtx.of().quota((key, n) -> false)))
        .hasMessageContaining("share of Cloud Asset reads for acme-stage (100 per minute) is used up");
    assertThat(google.api()).isEmpty();
  }

  @Test
  void aLargeAnswerIsCutByWholeResourcesWithoutACursor() throws Exception {
    StringBuilder results = new StringBuilder();
    for (int i = 0; i < 100; i++) {
      results.append(i == 0 ? "" : ",").append("{\"name\":\"//run.googleapis.com/projects/acme-stage/locations/"
          + "europe-west1/services/s").append(i).append("\",\"assetType\":\"run.googleapis.com/Service\",\"location\":"
          + "\"europe-west1\",\"displayName\":\"").append("x".repeat(400)).append("\"}");
    }
    asset.set(r -> FakeGoogle.json(200, "{\"results\":[" + results + "],\"nextPageToken\":\"more\"}"));
    TestCtx ctx = TestCtx.of().with(new ai.msrv.gaterook.connector.api.Limits(100, 200, 32 * 1024, 64 * 1024,
        256 * 1024, 64 * 1024, 8 * 1024, java.time.Duration.ofSeconds(3), java.time.Duration.ofSeconds(30),
        java.time.Duration.ofSeconds(10), java.time.Duration.ofHours(24)));

    ToolResult r = OutputSchema.describe(run("{\"limit\":100}", ctx));

    JsonNode s = r.structuredContent();
    assertThat(s.path("count").asInt()).isBetween(1, 99);
    assertThat(s.path("truncated").asBoolean()).isTrue();
    assertThat(s.path("truncatedReason").asString()).isEqualTo("size_limit");
    assertThat(s.path("nextCursor").isNull()).as("a cursor would skip the resources cut here").isTrue();
    assertThat((long) (r.content().getFirst().text() + s).length()).isLessThanOrEqualTo(32 * 1024 * 3 / 4);
  }

  @Test
  void prodDefaultsToTenResources() throws Exception {
    asset.set(r -> FakeGoogle.json(200, "{\"results\":[]}"));
    ConnectionConfig prod = Configs.prod();
    ToolCall call = Configs.call("gcp_describe", prod.name(), "{}");
    connector.classify(call, prod, Configs.policy(Env.PROD));

    connector.execute(call, prod, new TestSecrets(), TestCtx.of().env(Env.PROD));

    assertThat(FakeGoogle.param(google.to("/v1/projects/acme-prod:searchAllResources").getFirst(), "pageSize"))
        .isEqualTo("10");
  }

  @Test
  void listedScopesOrderTheSearchForAName() throws Exception {
    Settings s = Settings.parse(Configs.of("x", Env.DEV, """
        {"scopes":["projects/other-proj","folders/9","projects/123","projects/acme-stage"],
         "credentials":{"source":"adc"}}"""));

    assertThat(AssetDescribe.candidateScopes(RUN, s)).containsExactly("projects/acme-stage", "folders/9",
        "projects/123");
    assertThat(AssetDescribe.candidateScopes("//storage.googleapis.com/bucket", s)).isEqualTo(s.scopes());
    assertThat(List.of(s.scopes().getFirst())).containsExactly("projects/other-proj");
  }
}
