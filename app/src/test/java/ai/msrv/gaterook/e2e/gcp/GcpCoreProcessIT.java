package ai.msrv.gaterook.e2e.gcp;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.e2e.platform.CoreProcess;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The GCP connector through the assembled core (step C7, ARCHITECTURE.md §10): the window's admin API tests and saves
 * a GCP connection (a service account key impersonating another account), an endpoint and a token; an agent then
 * searches the inventory, reads one resource live, reads a metric and tries the dangerous reads. Google is a fake on
 * this computer: the token server, IAM, Cloud Resource Manager, Cloud Asset, Cloud Run and Cloud Monitoring.
 *
 * <p>The live resource echoes the impersonated token in its configuration and carries a password in a container's
 * environment: the agent sees {@code ‹masked›} for both (§9 "the agent never sees a secret"); no request ever reaches
 * {@code …/authString} or {@code :access} (C7 "Completion criteria").
 *
 * <p>On the JVM this runs {@code app.jar}; with {@code -Dgateway.core.binary=<path>} it is the native touch of the
 * connector: google-auth's key signing and impersonation, {@code java.net.http}, the sealed cursor.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GcpCoreProcessIT {

  private static final Duration READY = Duration.ofSeconds(90);
  private static final String GCP_TOKEN = "ya29.cnry-impersonated-" + Long.toHexString(System.nanoTime());
  private static final String ENV_SECRET = "cnry-run-env-" + Long.toHexString(System.nanoTime());
  private static final String SA = "gw-reader@acme-dev.iam.gserviceaccount.com";
  private static final String RUN = "//run.googleapis.com/projects/acme-dev/locations/europe-west1/services/api";
  private static final String REDIS = "//redis.googleapis.com/projects/acme-dev/locations/europe-west1/instances/"
      + "cache";
  private static final HttpClient HTTP = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY)
      .connectTimeout(Duration.ofSeconds(5)).build();

  private static final List<RecordedRequest> REQUESTS = new CopyOnWriteArrayList<>();
  private static MockWebServer fake;
  private static CoreProcess core;
  private static String token;
  private static String keyJson;

  @BeforeAll
  static void start() throws Exception {
    KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
    rsa.initialize(2048);
    String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
        .encodeToString(rsa.generateKeyPair().getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n";
    keyJson = JsonNodeFactory.instance.objectNode().put("type", "service_account").put("project_id", "acme-dev")
        .put("private_key_id", "k1").put("private_key", pem)
        .put("client_email", "gw-key@acme-dev.iam.gserviceaccount.com").put("client_id", "1")
        .put("token_uri", "https://oauth2.googleapis.com/token").toString();

    fake = new MockWebServer();
    fake.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        REQUESTS.add(request);
        return answer(request);
      }
    });
    fake.start(InetAddress.getByName("127.0.0.1"), 0);
    String base = "http://127.0.0.1:" + fake.getPort();
    StringBuilder services = new StringBuilder();
    for (String api : List.of("cloudasset", "monitoring", "cloudresourcemanager", "run", "redis", "compute")) {
      services.append(services.isEmpty() ? "" : ",").append("gcp.").append(api).append('=').append(base);
    }
    core = CoreProcess.start(CoreProcess.newHome("gcp"), CoreProcess.freePort(), List.of(
        "-Dgateway.cloud.gcp.token-uri=" + base + "/token",
        "-Dgateway.cloud.gcp.iam-endpoint=" + base + "/v1/projects/-/serviceAccounts/%s:generateAccessToken",
        "-Dgateway.cloud.endpoints=" + services)).bootstrap();
  }

  @AfterAll
  static void stop() {
    if (core != null) {
      core.close();
    }
    if (fake != null) {
      fake.close();
    }
  }

  @Test
  @Order(1)
  void theConnectionIsTestedAndSavedAndTheEndpointServesIt() throws Exception {
    assertThat(core.untilReady(READY).getLast().path("ready").asBoolean()).as(core.stderr()).isTrue();
    String config = """
        {"scopes":["projects/acme-dev"],"credentials":{"source":"serviceAccountKey","impersonate":"%s"}}"""
        .formatted(SA);
    String secrets = "{\"/credentials/serviceAccountKey\":{\"source\":\"local\",\"value\":"
        + JsonNodeFactory.instance.textNode(keyJson) + "}}";

    JsonNode tested = json(admin("POST", "/api/connections/test", "{\"type\":\"gcp\",\"env\":\"dev\",\"config\":"
        + config + ",\"secrets\":" + secrets + "}"), 200);
    assertThat(tested.path("ok").asBoolean()).as(tested.toString()).isTrue();
    assertThat(tested.path("identity").asString()).isEqualTo(SA);
    assertThat(tested.path("serverVersion").asString()).isEqualTo("Cloud Asset API v1 · Cloud Monitoring API v3");
    assertThat(tested.path("permissions").path("level").asString()).isEqualToIgnoringCase("read");
    json(admin("POST", "/api/connections", "{\"name\":\"gcp-dev\",\"type\":\"gcp\",\"env\":\"dev\",\"config\":"
        + config + ",\"secrets\":" + secrets + "}"), 201);

    // Test connection of the saved connection keeps its facts (the project's number) for classify.
    JsonNode stored = json(admin("POST", "/api/connections/gcp-dev/test", "{}"), 200);
    assertThat(stored.path("ok").asBoolean()).as(stored.toString()).isTrue();

    json(admin("POST", "/api/endpoints", "{\"slug\":\"cloud\",\"startFrom\":\"empty\"}"), 201);
    json(admin("POST", "/api/endpoints/cloud/connections/gcp-dev", "{}"), 200);
    token = json(admin("POST", "/api/endpoints/cloud/tokens", "{\"name\":\"gcp-e2e\",\"ttlDays\":1}"), 201)
        .path("token").asString();
    assertThat(token).startsWith("gwt_");

    mcp("""
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},
         "clientInfo":{"name":"gcp-e2e","version":"1"}}}""");
    JsonNode describe = null;
    JsonNode metrics = null;
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while ((describe == null || metrics == null) && System.nanoTime() < deadline) {
      for (JsonNode t : mcp("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}")
          .at("/result/tools")) {
        if (t.path("name").asString().equals("gcp_describe")) {
          describe = t;
        } else if (t.path("name").asString().equals("gcp_metrics")) {
          metrics = t;
        }
      }
      if (describe == null || metrics == null) {
        Thread.sleep(200);
      }
    }
    assertThat(describe).as("gcp_describe on the endpoint").isNotNull();
    assertThat(metrics).as("gcp_metrics on the endpoint").isNotNull();
    assertThat(describe.path("description").asString()).contains("Scopes: gcp-dev: projects/acme-dev")
        .contains("Connections: gcp-dev (GCP");
    assertThat(describe.at("/inputSchema/properties/scope/enum").toString()).isEqualTo("[\"projects/acme-dev\"]");
    assertThat(describe.at("/inputSchema/properties/connection/enum").toString()).isEqualTo("[\"gcp-dev\"]");
    assertThat(describe.at("/annotations/readOnlyHint").asBoolean()).isTrue();
    assertThat(describe.has("outputSchema")).as("Read only: the schema is published").isTrue();
  }

  @Test
  @Order(2)
  void anAgentSearchesAndReadsOneResourceLiveWithItsSecretsMasked() throws Exception {
    JsonNode search = call(3, "gcp_describe", "{\"connection\":\"gcp-dev\",\"assetTypes\":"
        + "[\"run.googleapis.com/Service\"],\"query\":\"state=ACTIVE\",\"limit\":1}");
    assertThat(search.at("/result/isError").asBoolean(true)).as(search.toString()).isFalse();
    assertThat(search.at("/result/structuredContent/count").asInt()).isEqualTo(1);
    assertThat(search.at("/result/content/0/text").asString()).contains(RUN);
    RecordedRequest asset = REQUESTS.stream().filter(r -> r.getUrl().encodedPath().equals(
        "/v1/projects/acme-dev:searchAllResources")).findFirst().orElseThrow();
    assertThat(asset.getHeaders().get("Authorization")).isEqualTo("Bearer " + GCP_TOKEN);
    // The cursor survives the core's masking in both forms the agent sees, and pages on.
    String cursor = search.at("/result/structuredContent/nextCursor").asString();
    assertThat(cursor).startsWith("gc1.");
    assertThat(search.at("/result/content/0/text").asString()).contains("cursor " + cursor);
    JsonNode next = call(31, "gcp_describe", "{\"connection\":\"gcp-dev\",\"assetTypes\":"
        + "[\"run.googleapis.com/Service\"],\"query\":\"state=ACTIVE\",\"cursor\":\"" + cursor + "\"}");
    assertThat(next.at("/result/isError").asBoolean(true)).as(next.toString()).isFalse();
    assertThat(next.at("/result/structuredContent/resources/0/name").asString()).endsWith("/services/billing");

    JsonNode get = call(4, "gcp_describe", "{\"connection\":\"gcp-dev\",\"name\":\"" + RUN + "\"}");
    assertThat(get.at("/result/isError").asBoolean(true)).as(get.toString()).isFalse();
    JsonNode resource = get.at("/result/structuredContent/resource");
    assertThat(resource.path("source").asString()).isEqualTo("live");
    assertThat(resource.at("/data/template/containers/0/env/0/value").asString()).isEqualTo("‹masked›");
    assertThat(resource.at("/data/annotations/run.googleapis.com~1note").asString())
        .as("the impersonated token, echoed by the service, is masked by the core").contains("‹masked›");
    assertThat(get.toString()).doesNotContain(ENV_SECRET).doesNotContain(GCP_TOKEN);
    assertThat(get.at("/result/content/0/text").asString()).contains("source: live");
  }

  @Test
  @Order(3)
  void theDangerousReadsNeverLeaveTheGateway() throws Exception {
    JsonNode secret = call(5, "gcp_describe", "{\"connection\":\"gcp-dev\",\"name\":\"//secretmanager.googleapis.com/"
        + "projects/acme-dev/secrets/db/versions/1:access\"}");
    assertThat(secret.at("/result/isError").asBoolean()).isTrue();
    assertThat(secret.at("/result/content/0/text").asString()).startsWith("Denied by policy dev.cloud.bad_name");

    JsonNode auth = call(6, "gcp_describe", "{\"connection\":\"gcp-dev\",\"name\":\"" + REDIS + "/authString\"}");
    assertThat(auth.at("/result/isError").asBoolean(false)).as(auth.toString()).isFalse();
    assertThat(auth.at("/result/structuredContent/resource/source").asString()).isEqualTo("inventory");

    assertThat(REQUESTS).extracting(r -> r.getUrl().encodedPath())
        .noneMatch(p -> p.contains("authString") || p.contains(":access") || p.contains("serialPort"));
  }

  @Test
  @Order(4)
  void anAgentReadsAMetricWithItsKindsAligner() throws Exception {
    JsonNode m = call(7, "gcp_metrics", "{\"connection\":\"gcp-dev\",\"metric\":\"run.googleapis.com/request_count\","
        + "\"filter\":\"resource.labels.service_name = \\\"api\\\"\",\"from\":\"-1h\"}");
    assertThat(m.at("/result/isError").asBoolean(true)).as(m.toString()).isFalse();
    assertThat(m.at("/result/structuredContent/aligner").asString()).isEqualTo("ALIGN_RATE");
    assertThat(m.at("/result/structuredContent/series/0/points/0/v").asDouble()).isEqualTo(0.5);
    RecordedRequest list = REQUESTS.stream().filter(r -> r.getUrl().encodedPath().equals(
        "/v3/projects/acme-dev/timeSeries")).findFirst().orElseThrow();
    assertThat(list.getUrl().queryParameter("filter")).isEqualTo("metric.type = \"run.googleapis.com/request_count\" "
        + "AND (resource.labels.service_name = \"api\")");

    JsonNode scoped = call(8, "gcp_metrics", "{\"connection\":\"gcp-dev\",\"metric\":\"run.googleapis.com/"
        + "request_count\",\"filter\":\"project = \\\"other\\\"\"}");
    assertThat(scoped.at("/result/content/0/text").asString()).startsWith("Denied by policy dev.cloud.filter_scope");
  }

  @Test
  @Order(5)
  void noCredentialReachesAnAnswerOrTheLog() throws Exception {
    List<String> seen = new ArrayList<>(core.stdoutSoFar());
    seen.add(core.coreLog());
    seen.add(core.stderr());
    seen.add(json(admin("GET", "/api/connections", null), 200).toString());
    seen.add(json(admin("GET", "/api/activity/events", null), 200).toString());
    String everything = String.join("\n", seen);
    for (String canary : List.of(GCP_TOKEN, ENV_SECRET, token)) {
      assertThat(everything).doesNotContain(canary);
    }
    assertThat(core.coreLog()).doesNotContainPattern("Missing[A-Za-z]*Registration")
        .doesNotContain("UnsupportedFeatureError");
  }

  // ── The fakes ───────────────────────────────────────────────────────────────────────────────────────────────

  private static MockResponse answer(RecordedRequest r) {
    String path = r.getUrl().encodedPath();
    if (path.equals("/token")) {
      return json(200, "{\"access_token\":\"ya29.key-token\",\"expires_in\":3600,\"token_type\":\"Bearer\"}");
    }
    if (path.endsWith(":generateAccessToken")) {
      return json(200, "{\"accessToken\":\"" + GCP_TOKEN + "\",\"expireTime\":\"" + Instant.now().plusSeconds(900)
          + "\"}");
    }
    if (path.equals("/v3/projects/acme-dev")) {
      return json(200, "{\"name\":\"projects/123456789012\",\"projectId\":\"acme-dev\",\"state\":\"ACTIVE\"}");
    }
    if (path.equals("/v3/projects/acme-dev:testIamPermissions")) {
      return json(200, "{\"permissions\":[\"cloudasset.assets.searchAllResources\",\"run.services.get\"]}");
    }
    if (path.equals("/v1/projects/acme-dev:searchAllResources")) {
      String query = r.getUrl().queryParameter("query");
      if ("name:\"authString\"".equals(query)) {
        return json(200, "{\"results\":[{\"name\":\"" + REDIS + "/authString\",\"assetType\":\"redis.googleapis.com/"
            + "Instance\",\"location\":\"europe-west1\"}]}");
      }
      if ("asset-page-2".equals(r.getUrl().queryParameter("pageToken"))) {
        return json(200, "{\"results\":[{\"name\":\"" + RUN.replace("/api", "/billing") + "\",\"assetType\":"
            + "\"run.googleapis.com/Service\",\"location\":\"europe-west1\",\"state\":\"ACTIVE\"}]}");
      }
      return json(200, """
          {"results":[{"name":"%s","assetType":"run.googleapis.com/Service","project":"projects/123456789012",
            "location":"europe-west1","state":"ACTIVE","updateTime":"2026-10-22T11:58:02Z"}]%s}""".formatted(RUN,
          "state=ACTIVE".equals(query) ? ",\"nextPageToken\":\"asset-page-2\"" : ""));
    }
    if (path.equals("/v2/projects/acme-dev/locations/europe-west1/services/api")) {
      return json(200, """
          {"name":"projects/acme-dev/locations/europe-west1/services/api","generation":"7",
           "annotations":{"run.googleapis.com/note":"deployed with Bearer %s"},
           "template":{"containers":[{"image":"api:1.5","env":[{"name":"DB_PASS","value":"%s"}]}]}}"""
          .formatted(GCP_TOKEN, ENV_SECRET));
    }
    if (path.equals("/v3/projects/acme-dev/metricDescriptors")) {
      return json(200, "{\"metricDescriptors\":[]}");
    }
    if (path.equals("/v3/projects/acme-dev/metricDescriptors/run.googleapis.com/request_count")) {
      return json(200, "{\"metricKind\":\"DELTA\",\"valueType\":\"INT64\",\"unit\":\"1\"}");
    }
    if (path.equals("/v3/projects/acme-dev/timeSeries")) {
      return json(200, "{\"timeSeries\":[{\"metric\":{\"type\":\"run.googleapis.com/request_count\"},\"resource\":"
          + "{\"type\":\"cloud_run_revision\",\"labels\":{\"service_name\":\"api\"}},\"points\":[{\"interval\":"
          + "{\"endTime\":\"" + Instant.now().minusSeconds(60) + "\"},\"value\":{\"doubleValue\":0.5}}]}]}");
    }
    return json(404, "{\"error\":{\"code\":404,\"message\":\"no such fake: " + path + "\",\"status\":\"NOT_FOUND\"}}");
  }

  private static MockResponse json(int code, String body) {
    return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build();
  }

  // ── The window's admin API and the agent's MCP ──────────────────────────────────────────────────────────────

  private record Answer(int status, String body) {}

  private static URI uri(String target) {
    return URI.create("http://127.0.0.1:" + core.port + target);
  }

  private static Answer admin(String method, String target, String body) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(uri(target)).timeout(Duration.ofSeconds(30))
        .header("X-Gateway-Auth", core.secret().sign(method, target, System.currentTimeMillis(), nonce()))
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    if (body != null) {
      b.header("Content-Type", "application/json");
    }
    HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    return new Answer(r.statusCode(), r.body());
  }

  private static JsonNode json(Answer answer, int status) {
    assertThat(answer.status()).as(answer.body()).isEqualTo(status);
    return CoreProcess.JSON.readTree(answer.body());
  }

  private static JsonNode mcp(String message) throws Exception {
    HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(uri("/mcp/cloud")).timeout(Duration.ofSeconds(40))
        .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
        .header("MCP-Protocol-Version", "2025-11-25").header("Authorization", "Bearer " + token)
        .POST(HttpRequest.BodyPublishers.ofString(message)).build(), HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    return CoreProcess.JSON.readTree(r.body());
  }

  private static JsonNode call(int id, String tool, String arguments) throws Exception {
    ObjectNode params = JsonNodeFactory.instance.objectNode().put("name", tool);
    params.set("arguments", CoreProcess.JSON.readTree(arguments));
    return mcp("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":" + params + "}");
  }

  private static String nonce() {
    byte[] bytes = new byte[16];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
