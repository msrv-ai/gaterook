package ai.msrv.gaterook.e2e.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import ai.msrv.gaterook.e2e.platform.CoreProcess;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Kubernetes connector through the assembled core (step C6, ARCHITECTURE.md §10): the window's admin API tests
 * and saves a connection to an API server, puts it on an endpoint and issues a token; an agent lists pods (and pages
 * with the sealed cursor), reads one, asks for a rollout restart and an apply, and a person approves each after
 * reading the command or the dry-run diff. The API server is a fake over TLS on this computer whose answers echo the
 * service-account token: the agent reads {@code ‹masked›} (§9 "the agent never sees a secret").
 *
 * <p>On the JVM this runs {@code app.jar}; with {@code -Dgateway.core.binary=<path>} it is the native touch of the
 * connector: Fabric8's JDK HTTP client with the connection's own trust, JSON trees without model classes, the YAML
 * of manifests and diffs, and the cursor's sealed handles in the image.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KubernetesCoreProcessIT {

  private static final Duration READY = Duration.ofSeconds(90);
  private static final String TOKEN = "eyJhbGciOiJSUzI1NiJ9.cnry-sa-" + Long.toHexString(System.nanoTime())
      + ".c2lnbmF0dXJl";
  private static final String CONTINUE = "eyJ2IjoibWV0YS5rOHMuaW8vdjEiLCJydiI6MTAwLCJzdGFydCI6Indvcmtlci0x" + "In0K";
  private static final HttpClient HTTP = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY)
      .connectTimeout(Duration.ofSeconds(5)).build();
  private static final HeldCertificate CA = new HeldCertificate.Builder().certificateAuthority(0)
      .commonName("kubernetes").build();
  private static final HeldCertificate LEAF = new HeldCertificate.Builder().commonName("kube-apiserver")
      .addSubjectAlternativeName("localhost").signedBy(CA).build();

  /** What reached the API server, in order. */
  record Seen(String method, String path, Map<String, String> query, String authorization, String contentType,
      String body) {}

  private static final List<Seen> SEEN = new CopyOnWriteArrayList<>();
  private static HttpsServer api;
  private static CoreProcess core;
  private static String server;
  private static String token;
  private static volatile boolean configMapExists;

  @BeforeAll
  static void start() throws Exception {
    api = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    api.setHttpsConfigurator(new HttpsConfigurator(new HandshakeCertificates.Builder()
        .heldCertificate(LEAF, CA.certificate()).build().sslContext()));
    api.createContext("/", KubernetesCoreProcessIT::answer);
    api.start();
    server = "https://localhost:" + api.getAddress().getPort();
    core = CoreProcess.start(CoreProcess.newHome("k8s-e2e"), CoreProcess.freePort(), List.of()).bootstrap();
  }

  @AfterAll
  static void stop() {
    if (core != null) {
      core.close();
    }
    if (api != null) {
      api.stop(0);
    }
  }

  // ── Setup ───────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(1)
  void theConnectionIsTestedSavedAndPutOnAnEndpoint() throws Exception {
    assertThat(core.untilReady(READY).getLast().path("ready").asBoolean()).as(core.stderr()).isTrue();
    ObjectNode config = CoreProcess.JSON.createObjectNode().put("auth", "token").put("server", server)
        .put("namespace", "app-dev").put("caCert", CA.certificatePem());
    String secrets = "{\"/token\":{\"source\":\"local\",\"value\":\"" + TOKEN + "\"}}";

    JsonNode tested = json(admin("POST", "/api/connections/test", "{\"type\":\"kubernetes\",\"env\":\"dev\","
        + "\"config\":" + config + ",\"secrets\":" + secrets + "}"), 200);

    assertThat(tested.path("ok").asBoolean()).as(tested.toString()).isTrue();
    assertThat(tested.path("serverVersion").asString()).isEqualTo("v1.37.0");
    assertThat(tested.path("identity").asString()).isEqualTo("system:serviceaccount:app-dev:gw");
    assertThat(tested.toString()).doesNotContain(TOKEN);
    assertThat(SEEN).filteredOn(s -> s.path().equals("/version")).allSatisfy(s ->
        assertThat(s.authorization()).isEqualTo("Bearer " + TOKEN));

    json(admin("POST", "/api/connections", "{\"name\":\"dev-cluster\",\"type\":\"kubernetes\",\"env\":\"dev\","
        + "\"config\":" + config + ",\"secrets\":" + secrets + "}"), 201);
    json(admin("POST", "/api/endpoints", "{\"slug\":\"k8s\",\"startFrom\":\"empty\"}"), 201);
    json(admin("POST", "/api/endpoints/k8s/connections/dev-cluster", "{}"), 200);
    JsonNode settings = json(admin("GET", "/api/endpoints/k8s/settings", null), 200);
    json(admin("PUT", "/api/endpoints/k8s/settings", "{\"approvalWait\":{\"firstWaitSeconds\":0},\"version\":"
        + settings.path("version").asLong() + "}"), 200);
    // k8s_apply starts Off (the kit's tool list); a person switches it to Approval for this cluster.
    JsonNode apply = null;
    for (JsonNode g : json(admin("GET", "/api/endpoints/k8s/tools", null), 200)) {
      for (JsonNode t : g.path("tools")) {
        if (t.path("name").asString().equals("k8s_apply")) {
          apply = t;
        }
      }
    }
    assertThat(apply).isNotNull();
    assertThat(apply.path("mode").asString()).isEqualTo("OFF");
    ObjectNode on = CoreProcess.JSON.createObjectNode().put("toolKey", apply.path("toolKey").asString())
        .put("connection", "dev-cluster").put("mode", "APPROVAL").put("version", apply.path("version").asLong())
        .put("reason", "Config changes through the gateway");
    json(admin("PUT", "/api/endpoints/k8s/tools", on.toString()), 200);
    token = json(admin("POST", "/api/endpoints/k8s/tokens", "{\"name\":\"k8s-e2e\",\"ttlDays\":1}"), 201)
        .path("token").asString();
    mcp("""
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},
         "clientInfo":{"name":"k8s-e2e","version":"1"}}}""");
    List<String> names = new ArrayList<>();
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (!names.contains("k8s_apply") && System.nanoTime() < deadline) {
      names.clear();
      mcp("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}").at("/result/tools")
          .forEach(t -> names.add(t.path("name").asString()));
      if (!names.contains("k8s_apply")) {
        Thread.sleep(200);
      }
    }
    // k8s_logs is Off by default, like in the kit.
    assertThat(names).contains("k8s_get", "k8s_rollout_restart", "k8s_apply").doesNotContain("k8s_logs");
  }

  // ── Reads ───────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(2)
  void podsAreListedAndPagedWithTheSealedCursor() throws Exception {
    JsonNode first = call(3, "k8s_get", "{\"connection\":\"dev-cluster\",\"kind\":\"pods\",\"limit\":1}");

    assertThat(first.at("/result/isError").asBoolean(true)).as(first.toString()).isFalse();
    assertThat(first.at("/result/structuredContent/rows/0/name").asString()).isEqualTo("api-7d9c-xk2");
    String cursor = first.at("/result/structuredContent/cursor").asString();
    assertThat(cursor).as(first.toString()).startsWith("k8c.").doesNotContain("‹masked›");
    assertThat(first.toString()).doesNotContain(CONTINUE);

    JsonNode next = call(4, "k8s_get", "{\"connection\":\"dev-cluster\",\"kind\":\"pods\",\"limit\":1,\"cursor\":\""
        + cursor + "\"}");

    assertThat(next.at("/result/structuredContent/rows/0/name").asString()).as(next.toString())
        .isEqualTo("worker-1");
    assertThat(next.at("/result/structuredContent/cursor").isNull()).isTrue();
    assertThat(SEEN).filteredOn(s -> s.path().equals("/api/v1/namespaces/app-dev/pods")).last()
        .satisfies(s -> assertThat(s.query()).containsEntry("continue", CONTINUE));
  }

  @Test
  @Order(3)
  void oneObjectShowsItsEventsAndTheEchoedTokenIsMasked() throws Exception {
    JsonNode pod = call(5, "k8s_get", "{\"connection\":\"dev-cluster\",\"kind\":\"po\",\"name\":\"api-7d9c-xk2\"}");

    assertThat(pod.at("/result/isError").asBoolean(true)).as(pod.toString()).isFalse();
    String text = pod.at("/result/content/0/text").asString();
    assertThat(text).startsWith("pod/api-7d9c-xk2 in app-dev on dev-cluster (dev) · Running · 1/1 ready");
    assertThat(text).contains("Events:").contains("BackOff").contains("‹masked›");
    assertThat(pod.toString()).doesNotContain(TOKEN).doesNotContain("last-applied-configuration");

    JsonNode secret = call(6, "k8s_get", "{\"connection\":\"dev-cluster\",\"kind\":\"secrets\"}");
    assertThat(secret.at("/result/content/0/text").asString()).contains("secrets is not on the list, so agents "
        + "can't read Kubernetes Secrets.");
  }

  // ── Changes ─────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(4)
  void aRestartRunsAfterAPersonApprovedTheCommand() throws Exception {
    JsonNode request = call(7, "k8s_rollout_restart", "{\"connection\":\"dev-cluster\",\"kind\":\"deployment\","
        + "\"name\":\"api\",\"note\":\"The API leaks memory\"}");
    JsonNode handle = request.at("/result/structuredContent");
    assertThat(handle.path("status").asString()).as(request.toString()).isEqualTo("pending");
    assertThat(SEEN).noneMatch(s -> s.method().equals("PATCH"));

    String id = handle.path("handle").asString();
    JsonNode detail = json(admin("GET", "/api/approvals/" + id, null), 200);
    assertThat(detail.at("/action/text").asString()).as(detail.toString())
        .isEqualTo("kubectl rollout restart deployment/api --namespace app-dev");
    assertThat(detail.path("target").asString()).isEqualTo("dev-cluster · localhost:" + api.getAddress().getPort()
        + " · ns app-dev");
    assertThat(detail.toString()).contains("3, up to 1 new at a time, at most 0 unavailable");
    approve(id, detail);
    JsonNode status = call(8, "approval_status", "{\"handle\":\"" + id + "\",\"wait_seconds\":20}");

    assertThat(status.at("/result/structuredContent/status").asString()).as(status.toString())
        .isEqualTo("completed");
    Seen patch = SEEN.stream().filter(s -> s.method().equals("PATCH")).findFirst().orElseThrow();
    assertThat(patch.contentType()).startsWith("application/merge-patch+json");
    assertThat(patch.query()).containsEntry("fieldManager", "gaterook-rollout");
    assertThat(CoreProcess.JSON.readTree(patch.body()).at("/metadata/uid").asString()).isEqualTo("u-api-1");
  }

  @Test
  @Order(5)
  void anApplyRunsAfterAPersonApprovedTheDryRunDiff() throws Exception {
    String manifest = "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: settings\ndata:\n  mode: fast\n";
    JsonNode request = call(9, "k8s_apply", CoreProcess.JSON.createObjectNode().put("connection", "dev-cluster")
        .put("manifest", manifest).toString());
    String id = request.at("/result/structuredContent/handle").asString();
    assertThat(id).as(request.toString()).isNotEmpty();
    assertThat(SEEN).filteredOn(s -> s.method().equals("PATCH") && s.path().endsWith("/configmaps/settings"))
        .allSatisfy(s -> assertThat(s.query()).containsEntry("dryRun", "All"));

    JsonNode detail = json(admin("GET", "/api/approvals/" + id, null), 200);
    String diff = detail.at("/action/text").asString();
    assertThat(diff).as(detail.toString()).startsWith("--- configmap/settings (live)\n+++ configmap/settings (after "
        + "apply)\n@@ -0,0 +1,").contains("+data:\n+  mode: fast\n");
    approve(id, detail);
    JsonNode status = call(10, "approval_status", "{\"handle\":\"" + id + "\",\"wait_seconds\":20}");

    assertThat(status.at("/result/structuredContent/status").asString()).as(status.toString())
        .isEqualTo("completed");
    Seen applied = SEEN.stream().filter(s -> s.method().equals("PATCH") && s.path().endsWith("/configmaps/settings")
        && !s.query().containsKey("dryRun")).findFirst().orElseThrow();
    assertThat(applied.contentType()).startsWith("application/apply-patch+yaml");
    assertThat(applied.query()).containsEntry("fieldManager", "gaterook").containsEntry("fieldValidation",
        "Strict");
  }

  @Test
  @Order(6)
  void noCredentialReachesAnAnswerOrTheLog() throws Exception {
    List<String> seen = new ArrayList<>(core.stdoutSoFar());
    seen.add(core.coreLog());
    seen.add(core.stderr());
    seen.add(json(admin("GET", "/api/connections", null), 200).toString());
    seen.add(json(admin("GET", "/api/activity/events", null), 200).toString());
    String everything = String.join("\n", seen);
    assertThat(everything).doesNotContain(TOKEN).doesNotContain(token);
    assertThat(core.coreLog()).doesNotContainPattern("Missing[A-Za-z]*Registration")
        .doesNotContain("UnsupportedFeatureError");
  }

  // ── The fake API server ─────────────────────────────────────────────────────────────────────────────────────

  private static void answer(HttpExchange exchange) throws IOException {
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    String path = exchange.getRequestURI().getRawPath();
    Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
    String method = exchange.getRequestMethod();
    SEEN.add(new Seen(method, path, query, exchange.getRequestHeaders().getFirst("Authorization"),
        exchange.getRequestHeaders().getFirst("Content-Type"), body));
    int status = 200;
    String out;
    if (!("Bearer " + TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
      status = 401;
      out = status("Unauthorized", "Unauthorized", 401);
    } else {
      out = switch (method + " " + path) {
        case "GET /version" -> "{\"gitVersion\":\"v1.37.0\",\"platform\":\"linux/amd64\"}";
        case "POST /apis/authentication.k8s.io/v1/selfsubjectreviews" -> "{\"status\":{\"userInfo\":{\"username\":"
            + "\"system:serviceaccount:app-dev:gw\",\"groups\":[\"system:serviceaccounts\"]}}}";
        case "GET /api" -> discovery("", "v1", "Pod:pods:po", "ConfigMap:configmaps:cm", "Event:events:ev",
            "Secret:secrets:", "Namespace:namespaces:ns!");
        case "GET /apis" -> discovery("apps", "v1", "Deployment:deployments:deploy");
        case "GET /api/v1/namespaces/app-dev" -> "{\"kind\":\"Namespace\",\"metadata\":{\"name\":\"app-dev\"}}";
        case "POST /apis/authorization.k8s.io/v1/selfsubjectrulesreviews" -> """
            {"status":{"incomplete":false,"resourceRules":[
              {"verbs":["get","list","watch"],"apiGroups":[""],"resources":["pods","pods/log","configmaps","events"]},
              {"verbs":["get","list","patch"],"apiGroups":["apps"],"resources":["deployments"]}]}}""";
        case "GET /api/v1/namespaces/app-dev/pods" -> pods(query.containsKey("continue"));
        case "GET /api/v1/namespaces/app-dev/pods/api-7d9c-xk2" -> """
            {"apiVersion":"v1","kind":"Pod","metadata":{"name":"api-7d9c-xk2","namespace":"app-dev","uid":"p-1",
              "annotations":{"kubectl.kubernetes.io/last-applied-configuration":"{\\"x\\":1}",
                             "debug/last-request":"Authorization: Bearer %s"}},
             "spec":{"containers":[{"name":"api","image":"api:1.4.2"}]},
             "status":{"phase":"Running","containerStatuses":[{"name":"api","ready":true,"restartCount":0,
               "state":{"running":{}}}]}}""".formatted(TOKEN);
        case "GET /api/v1/namespaces/app-dev/events" -> """
            {"kind":"EventList","items":[{"type":"Warning","reason":"BackOff","count":2,
              "lastTimestamp":"%s","message":"Back-off pulling image with token %s"}]}"""
            .formatted(Instant.now().minusSeconds(30), TOKEN);
        case "GET /apis/apps/v1/namespaces/app-dev/deployments/api" -> """
            {"apiVersion":"apps/v1","kind":"Deployment","metadata":{"name":"api","namespace":"app-dev",
              "uid":"u-api-1","generation":4},
             "spec":{"replicas":3,"template":{"spec":{"containers":[{"name":"api","image":"api:1.4.2"}]}}}}""";
        case "PATCH /apis/apps/v1/namespaces/app-dev/deployments/api" ->
            "{\"apiVersion\":\"apps/v1\",\"kind\":\"Deployment\",\"metadata\":{\"name\":\"api\",\"generation\":5}}";
        case "GET /api/v1/namespaces/app-dev/configmaps/settings" -> {
          if (!configMapExists) {
            status = 404;
            yield status("NotFound", "configmaps \"settings\" not found", 404);
          }
          yield configMap("2");
        }
        case "PATCH /api/v1/namespaces/app-dev/configmaps/settings" -> {
          if (!query.containsKey("dryRun")) {
            configMapExists = true;
          }
          yield configMap("1");
        }
        default -> {
          status = 404;
          yield status("NotFound", "the server could not find the requested resource", 404);
        }
      };
    }
    byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private static String pods(boolean second) {
    String row = second
        ? "{\"cells\":[\"worker-1\",\"0/1\",\"Pending\",\"0\",\"%s\"],\"object\":{\"metadata\":{\"name\":"
            + "\"worker-1\"}}}"
        : "{\"cells\":[\"api-7d9c-xk2\",\"1/1\",\"Running\",\"0\",\"%s\"],\"object\":{\"metadata\":{\"name\":"
            + "\"api-7d9c-xk2\"}}}";
    return """
        {"kind":"Table","apiVersion":"meta.k8s.io/v1","metadata":{"continue":"%s"},
         "columnDefinitions":[{"name":"Name","type":"string","priority":0},
           {"name":"Ready","type":"string","priority":0},{"name":"Status","type":"string","priority":0},
           {"name":"Restarts","type":"string","priority":0},
           {"name":"Age","type":"string","format":"date","priority":0}],
         "rows":[%s]}""".formatted(second ? "" : CONTINUE, row.formatted(Instant.now().minusSeconds(600)));
  }

  private static String configMap(String rv) {
    return "{\"apiVersion\":\"v1\",\"kind\":\"ConfigMap\",\"metadata\":{\"name\":\"settings\",\"namespace\":"
        + "\"app-dev\",\"resourceVersion\":\"" + rv + "\"},\"data\":{\"mode\":\"fast\"}}";
  }

  /** An aggregated discovery list of one group: {@code Kind:plural:shortName}, {@code !} for cluster-scoped. */
  private static String discovery(String group, String version, String... resources) {
    StringBuilder rs = new StringBuilder();
    for (String r : resources) {
      String[] p = r.replace("!", "").split(":", -1);
      if (!rs.isEmpty()) {
        rs.append(',');
      }
      rs.append("{\"resource\":\"").append(p[1]).append("\",\"responseKind\":{\"kind\":\"").append(p[0])
          .append("\"},\"scope\":\"").append(r.endsWith("!") ? "Cluster" : "Namespaced")
          .append("\",\"verbs\":[\"get\",\"list\"]").append(p[2].isEmpty() ? "" : ",\"shortNames\":[\"" + p[2]
              + "\"]").append('}');
    }
    return "{\"kind\":\"APIGroupDiscoveryList\",\"items\":[{\"metadata\":{\"name\":\"" + group + "\"},\"versions\":"
        + "[{\"version\":\"" + version + "\",\"resources\":[" + rs + "]}]}]}";
  }

  private static String status(String reason, String message, int code) {
    return "{\"kind\":\"Status\",\"apiVersion\":\"v1\",\"status\":\"Failure\",\"reason\":\"" + reason
        + "\",\"message\":\"" + message.replace("\"", "\\\"") + "\",\"code\":" + code + "}";
  }

  private static Map<String, String> query(String raw) {
    Map<String, String> out = new LinkedHashMap<>();
    if (raw != null) {
      for (String p : raw.split("&")) {
        int eq = p.indexOf('=');
        out.put(URLDecoder.decode(eq < 0 ? p : p.substring(0, eq), StandardCharsets.UTF_8),
            eq < 0 ? "" : URLDecoder.decode(p.substring(eq + 1), StandardCharsets.UTF_8));
      }
    }
    return out;
  }

  // ── The window's admin API and the agent's MCP ──────────────────────────────────────────────────────────────

  private record Answer(int status, String body) {}

  private static void approve(String id, JsonNode detail) throws Exception {
    json(admin("POST", "/api/approvals/" + id + "/decision", "{\"decision\":\"approve\",\"digest\":\""
        + detail.path("digest").asString() + "\",\"via\":\"app\"}"), 200);
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
    HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(uri("/mcp/k8s")).timeout(Duration.ofSeconds(40))
        .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
        .header("MCP-Protocol-Version", "2025-11-25").header("Authorization", "Bearer " + token)
        .POST(HttpRequest.BodyPublishers.ofString(message)).build(), HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
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
