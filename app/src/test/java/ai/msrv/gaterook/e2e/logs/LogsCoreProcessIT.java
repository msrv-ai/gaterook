package ai.msrv.gaterook.e2e.logs;

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
 * The Logs connector through the assembled core (step C5, ARCHITECTURE.md §10): the window's admin API saves and
 * tests a Loki, a Cloud Logging, a CloudWatch and an OpenSearch connection, an endpoint and a token; an agent then
 * searches and tails them. The backends are fakes on this computer: Loki's HTTP API, Google's token server, IAM and
 * {@code entries:list}, STS and the CloudWatch Logs JSON protocol. Each backend echoes a credential into a log line
 * — the Loki token, the impersonated Google token, the AWS secret key — and the agent sees {@code ‹masked›} instead
 * (§9 "the agent never sees a secret").
 *
 * <p>On the JVM this runs {@code app.jar}; with {@code -Dgateway.core.binary=<path>} it is the native touch of the
 * connector: {@code java.net.http}, Apache HttpClient for OpenSearch REST, google-auth's key signing and
 * impersonation, the AWS SDK's JSON protocol with the URL-connection client, and the cursor's sealed handles.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LogsCoreProcessIT {

  private static final Duration READY = Duration.ofSeconds(90);
  private static final String LOKI_TOKEN = "glc_cnry-loki-" + Long.toHexString(System.nanoTime());
  private static final String GCP_TOKEN = "ya29.cnry-impersonated-" + Long.toHexString(System.nanoTime());
  private static final String AWS_SECRET = "cnry-aws-secret-" + Long.toHexString(System.nanoTime());
  private static final String OPENSEARCH_USER = "search-reader";
  private static final String OPENSEARCH_PASSWORD = "cnry-search-password-" + Long.toHexString(System.nanoTime());
  private static final String OPENSEARCH_BASIC = Base64.getEncoder()
      .encodeToString((OPENSEARCH_USER + ":" + OPENSEARCH_PASSWORD).getBytes(StandardCharsets.UTF_8));
  private static final String SA = "gw-logs@acme-dev.iam.gserviceaccount.com";
  private static final HttpClient HTTP = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY)
      .connectTimeout(Duration.ofSeconds(5)).build();

  private static final Instant T = Instant.now().minusSeconds(300);
  private static final List<RecordedRequest> REQUESTS = new CopyOnWriteArrayList<>();
  private static final List<String> MCP_ANSWERS = new CopyOnWriteArrayList<>();
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
    ObjectNode key = JsonNodeFactory.instance.objectNode().put("type", "service_account")
        .put("project_id", "acme-dev").put("private_key_id", "k1").put("private_key", pem)
        .put("client_email", "gw-key@acme-dev.iam.gserviceaccount.com").put("client_id", "1")
        .put("token_uri", "https://oauth2.googleapis.com/token");
    keyJson = key.toString();

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
    core = CoreProcess.start(CoreProcess.newHome("logs"), CoreProcess.freePort(), List.of(
        "-Dgateway.cloud.gcp.token-uri=" + base + "/token",
        "-Dgateway.cloud.gcp.iam-endpoint=" + base + "/v1/projects/-/serviceAccounts/%s:generateAccessToken",
        "-Dgateway.cloud.aws.sts-endpoint=" + base,
        "-Dgateway.cloud.endpoints=gcp.logging=" + base + ",gcp.cloudresourcemanager=" + base + ",aws.logs=" + base))
        .bootstrap();
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
  void fourConnectionsAreTestedAndSaved() throws Exception {
    assertThat(core.untilReady(READY).getLast().path("ready").asBoolean()).as(core.stderr()).isTrue();
    String base = "http://127.0.0.1:" + fake.getPort();

    JsonNode loki = save("app-logs", """
        {"backend":"loki","url":"%s","tenant":"team-a","auth":{"mode":"bearer"}}""".formatted(base),
        "{\"/auth/secret\":{\"source\":\"local\",\"value\":\"" + LOKI_TOKEN + "\"}}");
    assertThat(loki.path("serverVersion").asString()).isEqualTo("Loki 3.7.9");
    assertThat(loki.path("permissions").path("level").asString()).isEqualToIgnoringCase("read");

    JsonNode gcp = save("gcp-logs", """
        {"backend":"gcp-logging","resourceNames":["projects/acme-dev"],
         "credentials":{"source":"serviceAccountKey","impersonate":"%s"}}""".formatted(SA),
        "{\"/credentials/serviceAccountKey\":{\"source\":\"local\",\"value\":" + JsonNodeFactory.instance
            .textNode(keyJson) + "}}");
    assertThat(gcp.path("identity").asString()).isEqualTo(SA);

    JsonNode aws = save("aws-logs", """
        {"backend":"cloudwatch","region":"eu-central-1","logGroups":["/aws/ecs/api"],"searchApi":"filter",
         "credentials":{"source":"accessKey","accessKeyId":"AKIAIOSFODNN7EXAMPLE"}}""",
        "{\"/credentials/secretAccessKey\":{\"source\":\"local\",\"value\":\"" + AWS_SECRET + "\"}}");
    assertThat(aws.path("identity").asString()).isEqualTo("arn:aws:iam::123456789012:user/gw-logs");

    json(admin("POST", "/api/connections", """
        {"name":"search-logs","type":"logs","env":"dev","config":{"backend":"opensearch","url":"%s",
         "apiAccess":"logs","indices":["logs-app-*"],"auth":{"mode":"none"}}}""".formatted(base)), 201);

    json(admin("POST", "/api/endpoints", "{\"slug\":\"logs\",\"startFrom\":\"empty\"}"), 201);
    for (String c : List.of("app-logs", "gcp-logs", "aws-logs", "search-logs")) {
      json(admin("POST", "/api/endpoints/logs/connections/" + c, "{}"), 200);
    }
    // logs_tail starts Off on every connection (the kit's tool list); a person switches it to Read only for Loki.
    JsonNode tail = null;
    for (JsonNode g : json(admin("GET", "/api/endpoints/logs/tools", null), 200)) {
      for (JsonNode t : g.path("tools")) {
        if (t.path("name").asString().equals("logs_tail")) {
          assertThat(t.path("mode").asString()).as(g.path("connection").asString()).isEqualTo("OFF");
          if (g.path("connection").asString("").equals("app-logs")) {
            tail = t;
          }
        }
      }
    }
    assertThat(tail).isNotNull();
    ObjectNode on = JsonNodeFactory.instance.objectNode().put("toolKey", tail.path("toolKey").asString())
        .put("connection", "app-logs").put("mode", "READ_ONLY").put("version", tail.path("version").asLong())
        .put("reason", "Follow the API's logs while it deploys");
    json(admin("PUT", "/api/endpoints/logs/tools", on.toString()), 200);
    token = json(admin("POST", "/api/endpoints/logs/tokens", "{\"name\":\"logs-e2e\",\"ttlDays\":1}"), 201)
        .path("token").asString();
    assertThat(token).startsWith("gwt_");
  }

  @Test
  @Order(2)
  void theToolsNameEachConnectionsQueryLanguage() throws Exception {
    mcp("""
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},
         "clientInfo":{"name":"logs-e2e","version":"1"}}}""");
    // The tools reach the list once the endpoint's pairs are reconciled (asynchronously, after the saves).
    JsonNode search = null;
    JsonNode tail = null;
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while ((search == null || tail == null) && System.nanoTime() < deadline) {
      search = null;
      tail = null;
      for (JsonNode t : mcp("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}")
          .at("/result/tools")) {
        if (t.path("name").asString().equals("logs_search") && t.path("description").asString()
            .contains("search-logs")) {
          search = t;
        }
        if (t.path("name").asString().equals("logs_tail")) {
          tail = t;
        }
      }
      if (search == null || tail == null) {
        Thread.sleep(200);
      }
    }
    assertThat(search).as("logs_search with all four connections").isNotNull();
    assertThat(tail).as("logs_tail, switched on").isNotNull();
    assertThat(search.path("description").asString()).contains("app-logs: LogQL (Loki)")
        .contains("gcp-logs: Logging query language (GCP Cloud Logging)").contains("search-logs: Lucene");
  }

  @Test
  @Order(3)
  void searchesEveryBackendAndTheEchoedCredentialsAreMasked() throws Exception {
    JsonNode loki = call(3, "logs_search", "{\"connection\":\"app-logs\",\"query\":\"{app=\\\"api\\\"}\"}");
    assertThat(loki.at("/result/isError").asBoolean(true)).as(loki.toString()).isFalse();
    assertThat(loki.at("/result/structuredContent/count").asInt()).isEqualTo(2);
    assertThat(loki.at("/result/content/0/text").asString()).contains("payment failed").contains("‹masked›");
    RecordedRequest range = REQUESTS.stream().filter(r -> r.getUrl().encodedPath().equals("/loki/api/v1/query_range"))
        .findFirst().orElseThrow();
    assertThat(range.getHeaders().get("X-Scope-OrgID")).isEqualTo("team-a");

    JsonNode gcp = call(4, "logs_search", "{\"connection\":\"gcp-logs\",\"query\":\"severity>=ERROR\"}");
    assertThat(gcp.at("/result/isError").asBoolean(true)).as(gcp.toString()).isFalse();
    assertThat(gcp.at("/result/structuredContent/entries/0/message").asString()).contains("‹masked›");
    RecordedRequest list = REQUESTS.stream().filter(r -> r.getUrl().encodedPath().equals("/v2/entries:list"))
        .reduce((a, b) -> b).orElseThrow();
    assertThat(list.getHeaders().get("Authorization")).isEqualTo("Bearer " + GCP_TOKEN);
    assertThat(list.getBody().utf8()).contains("(severity>=ERROR)\\nAND timestamp>=");

    JsonNode aws = call(5, "logs_search", "{\"connection\":\"aws-logs\",\"query\":\"ERROR\"}");
    assertThat(aws.at("/result/isError").asBoolean(true)).as(aws.toString()).isFalse();
    assertThat(aws.at("/result/structuredContent/entries/0/severity").asString()).isEqualTo("ERROR");
    assertThat(aws.at("/result/content/0/text").asString()).contains("‹masked›");

    JsonNode dsl = call(6, "logs_search", "{\"connection\":\"search-logs\",\"query\":\"{\\\"match_all\\\":{}}\"}");
    assertThat(dsl.at("/result/isError").asBoolean()).isTrue();
    assertThat(dsl.at("/result/content/0/text").asString()).startsWith("Denied by policy dev.logs.os_dsl");
    assertThat(REQUESTS).noneMatch(r -> r.getUrl().encodedPath().contains("_search"));
  }

  @Test
  @Order(4)
  void aTailPicksUpWhatArrivesAndItsCursorComesBack() throws Exception {
    JsonNode first = call(7, "logs_tail", "{\"connection\":\"app-logs\",\"query\":\"{app=\\\"api\\\"}\",\"wait\":0}");
    String cursor = first.at("/result/structuredContent/nextCursor").asString();
    assertThat(cursor).as(first.toString()).startsWith("lc1.");

    JsonNode next = call(8, "logs_tail", "{\"connection\":\"app-logs\",\"query\":\"{app=\\\"api\\\"}\",\"wait\":6,"
        + "\"cursor\":\"" + cursor + "\"}");
    assertThat(next.at("/result/isError").asBoolean(true)).as(next.toString()).isFalse();
    assertThat(next.at("/result/structuredContent/entries/0/message").asString()).isEqualTo("tailed line");
    assertThat(next.at("/result/content/0/text").asString()).contains("Next: call logs_tail again with cursor lc1.");
  }

  @Test
  @Order(5)
  void fullOpenSearchApisAreAvailableByDefaultAndSettingsWaitForExactApproval() throws Exception {
    String base = "http://127.0.0.1:" + fake.getPort();
    JsonNode tested = save("search-api", """
        {"backend":"opensearch","url":"%s","auth":{"mode":"basic","username":"%s"}}
        """.formatted(base, OPENSEARCH_USER),
        "{\"/auth/secret\":{\"source\":\"local\",\"value\":\"" + OPENSEARCH_PASSWORD + "\"}}");
    assertThat(tested.path("serverVersion").asString()).isEqualTo("OpenSearch 3.9.0");
    assertThat(REQUESTS.stream().filter(r -> r.getUrl().encodedPath().equals("/")
        && r.getMethod().equals("GET")).toList()).isNotEmpty().allSatisfy(r ->
        assertThat(r.getHeaders().get("Authorization")).isEqualTo("Basic " + OPENSEARCH_BASIC));
    json(admin("POST", "/api/endpoints/logs/connections/search-api", "{}"), 200);
    JsonNode settings = json(admin("GET", "/api/endpoints/logs/settings", null), 200);
    json(admin("PUT", "/api/endpoints/logs/settings", "{\"approvalWait\":{\"firstWaitSeconds\":0},\"version\":"
        + settings.path("version").asLong() + "}"), 200);

    JsonNode queryTool = null;
    JsonNode requestTool = null;
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while ((queryTool == null || requestTool == null) && System.nanoTime() < deadline) {
      for (JsonNode tool : mcp("{\"jsonrpc\":\"2.0\",\"id\":20,\"method\":\"tools/list\",\"params\":{}}")
          .at("/result/tools")) {
        if (tool.path("name").asString().equals("opensearch_query")) {
          queryTool = tool;
        }
        if (tool.path("name").asString().equals("opensearch_request")) {
          requestTool = tool;
        }
      }
      if (queryTool == null || requestTool == null) {
        Thread.sleep(200);
      }
    }
    assertThat(queryTool).as("full API query published").isNotNull();
    assertThat(requestTool).as("full API request published").isNotNull();
    for (JsonNode tool : List.of(queryTool, requestTool)) {
      assertThat(tool.at("/inputSchema/properties/connection/enum")).containsExactly(
          JsonNodeFactory.instance.textNode("search-api"));
    }

    JsonNode indices = call(21, "opensearch_indices", "{\"connection\":\"search-api\"}");
    assertThat(indices.at("/result/structuredContent/body/indices/0/name").asString()).isEqualTo("products");
    assertThat(REQUESTS.stream().filter(r -> r.getUrl().encodedPath().equals("/_resolve/index/*")).toList())
        .isNotEmpty().allSatisfy(r ->
            assertThat(r.getHeaders().get("Authorization")).isEqualTo("Basic " + OPENSEARCH_BASIC));
    JsonNode queried = call(22, "opensearch_query", """
        {"connection":"search-api","method":"POST","path":"/products/_search",
         "body":{"size":0,"query":{"match_all":{}},"aggs":{"categories":{"terms":{"field":"category.keyword"}}}}}
        """);
    assertThat(queried.at("/result/isError").asBoolean(true)).as(queried.toString()).isFalse();
    assertThat(queried.at("/result/structuredContent/body/aggregations/categories/buckets/0/key").asString())
        .isEqualTo("books");
    RecordedRequest search = REQUESTS.stream().filter(r -> r.getUrl().encodedPath().equals("/products/_search"))
        .findFirst().orElseThrow();
    assertThat(search.getMethod()).isEqualTo("POST");
    assertThat(search.getHeaders().get("Authorization")).isEqualTo("Basic " + OPENSEARCH_BASIC);
    assertThat(CoreProcess.JSON.readTree(search.getBody().utf8()).at("/aggs/categories/terms/field").asString())
        .isEqualTo("category.keyword");

    JsonNode requested = call(23, "opensearch_request", """
        {"connection":"search-api","method":"PUT","path":"/products/_settings",
         "query":{"preserve_existing":false},"body":{"index":{"number_of_replicas":1}}}
        """);
    JsonNode handle = requested.at("/result/structuredContent");
    assertThat(handle.path("status").asString()).as(requested.toString()).isEqualTo("pending");
    assertThat(REQUESTS).noneMatch(r -> r.getUrl().encodedPath().equals("/products/_settings"));
    String id = handle.path("handle").asString();
    JsonNode approval = json(admin("GET", "/api/approvals/" + id, null), 200);
    assertThat(approval.toString()).doesNotContain(OPENSEARCH_PASSWORD, OPENSEARCH_BASIC);
    String exact = approval.at("/action/text").asString();
    assertThat(exact).startsWith("PUT " + base + "/products/_settings?preserve_existing=false\n");
    json(admin("POST", "/api/approvals/" + id + "/decision", "{\"decision\":\"approve\",\"digest\":\""
        + approval.path("digest").asString() + "\",\"via\":\"app\"}"), 200);
    JsonNode status = call(24, "approval_status", "{\"handle\":\"" + id + "\",\"wait_seconds\":20}");
    assertThat(status.at("/result/structuredContent/status").asString()).as(status.toString()).isEqualTo("completed");
    assertThat(status.at("/result/structuredContent/result/body/acknowledged").asBoolean()).isTrue();
    assertThat(REQUESTS.stream().filter(r -> r.getUrl().encodedPath().equals("/products/_settings")).toList())
        .singleElement().satisfies(r -> {
          assertThat(r.getMethod()).isEqualTo("PUT");
          assertThat(r.getHeaders().get("Authorization")).isEqualTo("Basic " + OPENSEARCH_BASIC);
          assertThat(r.getUrl().encodedQuery()).isEqualTo("preserve_existing=false");
          assertThat(r.getBody().utf8()).isEqualTo(exact.substring(exact.indexOf("\n\n") + 2));
        });
  }

  @Test
  @Order(6)
  void noCredentialReachesAnAnswerOrTheLog() throws Exception {
    List<String> seen = new ArrayList<>(core.stdoutSoFar());
    seen.addAll(MCP_ANSWERS);
    seen.add(core.coreLog());
    seen.add(core.stderr());
    seen.add(json(admin("GET", "/api/connections", null), 200).toString());
    seen.add(json(admin("GET", "/api/activity/events", null), 200).toString());
    String everything = String.join("\n", seen);
    for (String canary : List.of(LOKI_TOKEN, GCP_TOKEN, AWS_SECRET, OPENSEARCH_PASSWORD, OPENSEARCH_BASIC, token)) {
      assertThat(everything).doesNotContain(canary);
    }
    assertThat(core.coreLog()).doesNotContainPattern("Missing[A-Za-z]*Registration")
        .doesNotContain("UnsupportedFeatureError");
  }

  // ── The fakes ───────────────────────────────────────────────────────────────────────────────────────────────

  private static MockResponse answer(RecordedRequest r) {
    String path = r.getUrl().encodedPath();
    String body = r.getBody() == null ? "" : r.getBody().utf8();
    return switch (path) {
      case "/ready" -> text(200, "ready\n");
      case "/loki/api/v1/status/buildinfo" -> json(200, "{\"version\":\"3.7.9\",\"revision\":\"d3b4a2f7\"}");
      case "/loki/api/v1/labels" -> json(200, "{\"status\":\"success\",\"data\":[\"app\",\"pod\"]}");
      case "/loki/api/v1/push" -> text(403, "write access denied\n");
      case "/loki/api/v1/query_range" -> lokiRange(r);
      case "/_resolve/index/*" -> json(200,
          "{\"indices\":[{\"name\":\"products\",\"aliases\":[]}],\"aliases\":[],\"data_streams\":[]}");
      case "/products/_search" -> json(200, """
          {"hits":{"hits":[]},"aggregations":{"categories":{"buckets":[{"key":"books","doc_count":2}]}}}
          """);
      case "/products/_settings" -> json(200, "{\"acknowledged\":true}");
      case "/token" -> json(200, "{\"access_token\":\"ya29.key-token\",\"expires_in\":3600,\"token_type\":\"Bearer\"}");
      case "/v2/entries:list" -> json(200, """
          {"entries":[{"insertId":"g1","logName":"projects/acme-dev/logs/stdout","timestamp":"%s","severity":"ERROR",
           "resource":{"type":"k8s_container","labels":{"pod_name":"api-1"}},
           "textPayload":"upstream said: Authorization: Bearer %s"}]}""".formatted(T, GCP_TOKEN));
      default -> {
        if (path.equals("/") && r.getMethod().equals("GET")) {
          yield json(200, "{\"version\":{\"distribution\":\"opensearch\",\"number\":\"3.9.0\"}}");
        }
        if (path.endsWith(":generateAccessToken")) {
          yield json(200, "{\"accessToken\":\"" + GCP_TOKEN + "\",\"expireTime\":\"" + Instant.now().plusSeconds(900)
              + "\"}");
        }
        if (path.endsWith(":testIamPermissions")) {
          yield json(200, "{\"permissions\":[\"logging.logEntries.list\"]}");
        }
        if (body.contains("Action=GetCallerIdentity")) {
          yield new MockResponse.Builder().code(200).addHeader("Content-Type", "text/xml").body(
              "<GetCallerIdentityResponse xmlns=\"https://sts.amazonaws.com/doc/2011-06-15/\"><GetCallerIdentityResult>"
              + "<Arn>arn:aws:iam::123456789012:user/gw-logs</Arn><UserId>AIDA1</UserId><Account>123456789012"
              + "</Account></GetCallerIdentityResult><ResponseMetadata><RequestId>1</RequestId></ResponseMetadata>"
              + "</GetCallerIdentityResponse>").build();
        }
        String target = r.getHeaders().get("X-Amz-Target");
        if ("Logs_20140328.FilterLogEvents".equals(target)) {
          yield amz("{\"events\":[{\"logStreamName\":\"ecs/api/1\",\"timestamp\":" + T.toEpochMilli()
              + ",\"message\":\"ERROR signing failed with secret " + AWS_SECRET + "\",\"eventId\":\"e1\"}]}");
        }
        if ("Logs_20140328.DescribeLogGroups".equals(target)) {
          yield amz("{\"logGroups\":[{\"logGroupName\":\"/aws/ecs/api\",\"retentionInDays\":14,\"storedBytes\":"
              + "1048576}]}");
        }
        yield text(404, "no such fake: " + path);
      }
    };
  }

  /** Two entries for a search; for a tail, one line just after where it reads from. */
  private static MockResponse lokiRange(RecordedRequest r) {
    String query = r.getUrl().encodedQuery();
    long start = Long.parseLong(param(query, "start"));
    if ("forward".equals(param(query, "direction"))) {
      long at = start + 1;
      long end = Long.parseLong(param(query, "end"));
      String values = at < end ? "[\"" + at + "\",\"tailed line\"]" : "";
      return json(200, "{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":[{\"stream\":"
          + "{\"app\":\"api\"},\"values\":[" + values + "]}]}}");
    }
    long ns = T.getEpochSecond() * 1_000_000_000L + T.getNano();
    return json(200, """
        {"status":"success","data":{"resultType":"streams","result":[
          {"stream":{"app":"api","pod":"api-1"},"values":[
            ["%d","payment failed: retry with Authorization: Bearer %s",{"structuredMetadata":{"detected_level":"error"}}],
            ["%d","GET /health 200",{}]]}]}}""".formatted(ns, LOKI_TOKEN, ns - 1_000_000_000L));
  }

  private static String param(String query, String name) {
    for (String p : query.split("&")) {
      if (p.startsWith(name + "=")) {
        return java.net.URLDecoder.decode(p.substring(name.length() + 1), StandardCharsets.UTF_8);
      }
    }
    return "";
  }

  private static MockResponse json(int code, String body) {
    return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build();
  }

  private static MockResponse text(int code, String body) {
    return new MockResponse.Builder().code(code).addHeader("Content-Type", "text/plain").body(body).build();
  }

  private static MockResponse amz(String body) {
    return new MockResponse.Builder().code(200).addHeader("Content-Type", "application/x-amz-json-1.1").body(body)
        .build();
  }

  // ── The window's admin API and the agent's MCP ──────────────────────────────────────────────────────────────

  private record Answer(int status, String body) {}

  /** Tests a draft as the form does, then saves it; answers the test's result. */
  private static JsonNode save(String name, String config, String secrets) throws Exception {
    JsonNode tested = json(admin("POST", "/api/connections/test", "{\"type\":\"logs\",\"env\":\"dev\",\"config\":"
        + config + ",\"secrets\":" + secrets + "}"), 200);
    assertThat(tested.path("ok").asBoolean()).as(tested.toString()).isTrue();
    json(admin("POST", "/api/connections", "{\"name\":\"" + name + "\",\"type\":\"logs\",\"env\":\"dev\",\"config\":"
        + config + ",\"secrets\":" + secrets + "}"), 201);
    return tested;
  }

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
    HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(uri("/mcp/logs")).timeout(Duration.ofSeconds(40))
        .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
        .header("MCP-Protocol-Version", "2025-11-25").header("Authorization", "Bearer " + token)
        .POST(HttpRequest.BodyPublishers.ofString(message)).build(), HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    MCP_ANSWERS.add(r.body());
    return CoreProcess.JSON.readTree(r.body());
  }

  private static JsonNode call(int id, String tool, String arguments) throws Exception {
    return mcp("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
        + "\",\"arguments\":" + arguments + "}}");
  }

  private static String nonce() {
    byte[] bytes = new byte[16];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
