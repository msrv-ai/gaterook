package ai.msrv.gaterook.e2e.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import ai.msrv.gaterook.e2e.platform.CoreProcess;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
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
 * The HTTP API connector on the assembled core, started as Electron starts it (ARCHITECTURE.md §10 C9): an HTTPS API
 * on this computer with its own CA, reached only after the person allowed its private address and trusted its
 * certificate (nothing sent before), {@code http_get} with the token echoed back masked, {@code http_request} POST
 * sent as exactly the approved bytes after Approve, and an OpenAPI file read by {@code /api/templates/openapi/parse}
 * into a template whose tools run on the connection — an edit between the request and Approve refuses the run.
 * With {@code -Dgateway.core.binary} the same runs against the native executable.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HttpCoreProcessIT {

  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private static final String TOKEN = "tok-cnry-" + Long.toHexString(System.nanoTime()) + "-9f3a";
  private static final HeldCertificate CA = new HeldCertificate.Builder().certificateAuthority(0)
      .commonName("Acme test CA").build();
  private static final HeldCertificate LEAF = new HeldCertificate.Builder().commonName("127.0.0.1")
      .addSubjectAlternativeName("127.0.0.1").signedBy(CA).build();

  /** What reached the API, in order. */
  record Seen(String method, String target, Map<String, List<String>> headers, byte[] body) {
    String header(String name) {
      for (Map.Entry<String, List<String>> e : headers.entrySet()) {
        if (e.getKey().equalsIgnoreCase(name)) {
          return e.getValue().getFirst();
        }
      }
      return null;
    }
  }

  private static final List<Seen> SEEN = new CopyOnWriteArrayList<>();
  private static HttpsServer api;
  private static CoreProcess core;
  private static String base;
  private static String token;
  private static String templateId;
  private static JsonNode definition;

  @BeforeAll
  static void start() throws Exception {
    api = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    api.setHttpsConfigurator(new HttpsConfigurator(new HandshakeCertificates.Builder()
        .heldCertificate(LEAF, CA.certificate()).build().sslContext()));
    api.createContext("/", HttpCoreProcessIT::answer);
    api.start();
    base = "https://127.0.0.1:" + api.getAddress().getPort() + "/v1";
    core = CoreProcess.start(CoreProcess.newHome("http-e2e"), CoreProcess.freePort(), List.of()).bootstrap();
    core.untilReady(Duration.ofSeconds(90));
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

  private static void answer(HttpExchange exchange) throws IOException {
    byte[] body = exchange.getRequestBody().readAllBytes();
    String target = exchange.getRequestURI().getRawPath() + (exchange.getRequestURI().getRawQuery() == null ? ""
        : "?" + exchange.getRequestURI().getRawQuery());
    SEEN.add(new Seen(exchange.getRequestMethod(), target, Map.copyOf(exchange.getRequestHeaders()), body));
    String path = exchange.getRequestURI().getPath();
    int status;
    String out;
    if (path.equals("/v1/invoices")) {
      // An API that repeats the credentials it got: the agent must never read them.
      status = 200;
      out = "{\"items\":[{\"id\":\"in_1\",\"total\":129}],\"seenAuthorization\":\""
          + exchange.getRequestHeaders().getFirst("Authorization") + "\"}";
    } else if (path.equals("/v1/refunds") && exchange.getRequestMethod().equals("POST")) {
      status = 201;
      out = "{\"id\":\"re_1\",\"status\":\"pending\"}";
    } else if (path.equals("/v1/refunds")) {
      status = 200;
      out = "{\"data\":[{\"id\":\"re_0\"}]}";
    } else {
      status = 404;
      out = "{\"error\":\"not found\"}";
    }
    byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  // ── Setup: allow the address, trust the certificate ─────────────────────────────────────────────────────────

  @Test
  @Order(1)
  void theConnectionReachesTheApiOnlyAfterItsAddressAndCertificateAreAllowed() throws Exception {
    json(admin("POST", "/api/connections", """
        {"name":"billing-api","type":"http","env":"dev","config":{"baseUrls":["%s"],"auth":{"type":"bearer"}},
         "secrets":{"/auth/token":{"source":"local","value":"%s"}}}""".formatted(base, TOKEN)), 201);

    JsonNode privateAddress = json(admin("POST", "/api/connections/billing-api/test", null), 200);
    assertThat(privateAddress.path("ok").asBoolean()).isFalse();
    assertThat(privateAddress.at("/error/code").asString()).as(privateAddress.toString())
        .isEqualTo("http.private-address");
    assertThat(privateAddress.at("/suggestedPolicyChange/cidr").asString()).isEqualTo("127.0.0.1/32");
    json(admin("POST", "/api/connections/billing-api/policy-suggestion",
        "{\"kind\":\"allow-private\",\"cidr\":\"127.0.0.1/32\"}"), 200);

    JsonNode unknownCertificate = json(admin("POST", "/api/connections/billing-api/test", null), 200);
    assertThat(unknownCertificate.at("/needsConfirmation/kind").asString()).as(unknownCertificate.toString())
        .isEqualTo("tls-cert");
    assertThat(SEEN).as("nothing sent before the certificate is trusted").isEmpty();

    JsonNode confirmed = json(admin("POST", "/api/connections/billing-api/test/confirm", "{\"confirmationId\":\""
        + unknownCertificate.path("confirmationId").asString() + "\"}"), 200);
    assertThat(confirmed.at("/retest/ok").asBoolean()).as(confirmed.toString()).isTrue();
    assertThat(SEEN).hasSize(2);
    assertThat(SEEN.get(0).header("Authorization")).as("first without credentials").isNull();
    assertThat(SEEN.get(1).header("Authorization")).isEqualTo("Bearer " + TOKEN);
    assertThat(confirmed.toString()).doesNotContain(TOKEN);

    // Requests go only under the environment's allowlist of base URLs.
    JsonNode dev = json(admin("GET", "/api/policies/dev", null), 200);
    json(admin("PUT", "/api/policies/dev", "{\"version\":" + dev.path("version").asLong() + ",\"sections\":{\"http\":"
        + "{\"allowedBaseUrls\":[\"" + base + "\"]}}}"), 200);

    json(admin("POST", "/api/endpoints", "{\"slug\":\"billing\",\"startFrom\":\"empty\"}"), 201);
    json(admin("POST", "/api/endpoints/billing/connections/billing-api", "{}"), 200);
    JsonNode settings = json(admin("GET", "/api/endpoints/billing/settings", null), 200);
    json(admin("PUT", "/api/endpoints/billing/settings", "{\"approvalWait\":{\"firstWaitSeconds\":0},\"version\":"
        + settings.path("version").asLong() + "}"), 200);
    token = json(admin("POST", "/api/endpoints/billing/tokens", "{\"name\":\"e2e\",\"ttlDays\":1}"), 201)
        .path("token").asString();
    mcp("""
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},
         "clientInfo":{"name":"http-e2e","version":"1"}}}""");
  }

  // ── http_get and http_request ───────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(2)
  void aReadAnswersAndTheEchoedTokenIsMasked() throws Exception {
    SEEN.clear();
    JsonNode r = call(2, "http_get", "{\"connection\":\"billing-api\",\"path\":\"/invoices\"}");
    assertThat(r.at("/result/isError").asBoolean(true)).as(r.toString()).isFalse();
    assertThat(r.at("/result/structuredContent/status").asInt()).isEqualTo(200);
    assertThat(r.at("/result/structuredContent/json/items/0/id").asString()).isEqualTo("in_1");
    assertThat(r.toString()).doesNotContain(TOKEN).contains("‹masked›");
    assertThat(SEEN).singleElement().satisfies(s -> {
      assertThat(s.target()).isEqualTo("/v1/invoices");
      assertThat(s.header("Authorization")).isEqualTo("Bearer " + TOKEN);
    });

    JsonNode outside = call(3, "http_get", "{\"connection\":\"billing-api\",\"path\":\"https://127.0.0.1:"
        + api.getAddress().getPort() + "/admin\"}");
    assertThat(outside.toString()).contains("isn't under");
    assertThat(SEEN).hasSize(1);
  }

  @Test
  @Order(3)
  void aPostRunsAfterApproveAsExactlyTheApprovedBytes() throws Exception {
    SEEN.clear();
    JsonNode request = call(4, "http_request", "{\"connection\":\"billing-api\",\"method\":\"POST\",\"path\":"
        + "\"/refunds\",\"headers\":{\"Idempotency-Key\":\"refund-48213\"},\"json\":{\"order_id\":48213,"
        + "\"amount\":1999,\"currency\":\"EUR\"}}");
    JsonNode handle = request.at("/result/structuredContent");
    assertThat(handle.path("status").asString()).as(request.toString()).isEqualTo("pending");
    assertThat(SEEN).as("nothing before Approve").isEmpty();

    String id = handle.path("handle").asString();
    JsonNode detail = json(admin("GET", "/api/approvals/" + id, null), 200);
    String text = detail.at("/action/text").asString();
    assertThat(text).startsWith("POST " + base + "/refunds\nContent-Type: application/json\nIdempotency-Key: "
        + "refund-48213\nAuthorization: ‹masked›\n\n{\n  \"order_id\": 48213,");
    json(admin("POST", "/api/approvals/" + id + "/decision", "{\"decision\":\"approve\",\"digest\":\""
        + detail.path("digest").asString() + "\",\"via\":\"app\"}"), 200);

    JsonNode status = call(5, "approval_status", "{\"handle\":\"" + id + "\",\"wait_seconds\":20}");
    assertThat(status.at("/result/structuredContent/status").asString()).as(status.toString()).isEqualTo("completed");
    assertThat(status.at("/result/structuredContent/result/status").asInt()).isEqualTo(201);
    assertThat(SEEN).singleElement().satisfies(s -> {
      assertThat(s.method()).isEqualTo("POST");
      assertThat(new String(s.body(), StandardCharsets.UTF_8)).isEqualTo(text.substring(text.indexOf("\n\n") + 2));
      assertThat(s.header("Idempotency-Key")).isEqualTo("refund-48213");
      assertThat(s.header("Authorization")).isEqualTo("Bearer " + TOKEN);
    });
  }

  // ── OpenAPI templates ───────────────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(4)
  void anOpenApiFileBecomesToolsThatRunOnTheConnection() throws Exception {
    String yaml = """
        openapi: 3.0.3
        info: {title: Billing API, version: "2.0"}
        servers:
          - url: %s
        paths:
          /refunds:
            get:
              operationId: listRefunds
              parameters:
                - {name: limit, in: query, schema: {type: integer, minimum: 1, maximum: 100}}
              responses: {'200': {description: ok}}
            post:
              operationId: createRefund
              requestBody:
                required: true
                content:
                  application/json:
                    schema:
                      type: object
                      required: [order_id, amount]
                      properties: {order_id: {type: integer}, amount: {type: integer}}
              responses: {'201': {description: created}}
        """.formatted(base);
    ObjectNode parse = CoreProcess.JSON.createObjectNode().put("connection", "billing-api").put("text", yaml);
    JsonNode parsed = json(admin("POST", "/api/templates/openapi/parse", parse.toString()), 200);
    assertThat(parsed.path("ok").asBoolean()).as(parsed.toString()).isTrue();
    assertThat(parsed.at("/source/sha256").asString()).hasSize(64);
    definition = parsed.path("definition");
    assertThat(definition.path("operations").size()).isEqualTo(2);

    ObjectNode create = CoreProcess.JSON.createObjectNode().put("kind", "openapi").put("connection", "billing-api")
        .put("name", "billing-api v2");
    create.set("definition", definition);
    JsonNode saved = json(admin("POST", "/api/templates", create.toString()), 201);
    assertThat(saved.at("/compile/ok").asBoolean()).as(saved.toString()).isTrue();
    templateId = saved.at("/template/id").asString();

    List<String> names = new ArrayList<>();
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (!names.contains("billing_list_refunds") && System.nanoTime() < deadline) {
      names.clear();
      mcp("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/list\",\"params\":{}}").at("/result/tools")
          .forEach(t -> names.add(t.path("name").asString()));
      if (!names.contains("billing_list_refunds")) {
        Thread.sleep(200);
      }
    }
    assertThat(names).contains("http_get", "http_request", "billing_list_refunds", "billing_create_refund");

    SEEN.clear();
    JsonNode list = call(7, "billing_list_refunds", "{\"limit\":3}");
    assertThat(list.at("/result/structuredContent/status").asInt()).as(list.toString()).isEqualTo(200);
    assertThat(SEEN).singleElement().satisfies(s -> assertThat(s.target()).isEqualTo("/v1/refunds?limit=3"));
  }

  @Test
  @Order(5)
  void aTemplateEditedAfterTheRequestRefusesTheApprovedRun() throws Exception {
    SEEN.clear();
    JsonNode request = call(8, "billing_create_refund", "{\"body\":{\"order_id\":48213,\"amount\":500}}");
    String id = request.at("/result/structuredContent/handle").asString();
    assertThat(request.at("/result/structuredContent/status").asString()).as(request.toString()).isEqualTo("pending");
    JsonNode detail = json(admin("GET", "/api/approvals/" + id, null), 200);
    assertThat(detail.toString()).contains("createRefund · billing-api v2 (template v1)");

    ObjectNode edited = (ObjectNode) definition.deepCopy();
    ((ObjectNode) edited.path("operations").get(1)).put("description", "Refund an order (edited).");
    ObjectNode update = CoreProcess.JSON.createObjectNode().put("baseVersion", 1).put("note", "description");
    update.set("definition", edited);
    json(admin("PUT", "/api/templates/" + templateId, update.toString()), 200);

    JsonNode decided = CoreProcess.JSON.readTree(admin("POST", "/api/approvals/" + id + "/decision",
        "{\"decision\":\"approve\",\"digest\":\"" + detail.path("digest").asString() + "\",\"via\":\"app\"}").body());
    JsonNode status = call(9, "approval_status", "{\"handle\":\"" + id + "\",\"wait_seconds\":20}");
    assertThat(decided.toString() + status).contains("Template changed after approval. Not executed.");
    assertThat(SEEN).as("the edited template's request never left").isEmpty();
  }

  @Test
  @Order(6)
  void theLogHasNoSecret() {
    assertThat(core.coreLog()).doesNotContain(TOKEN).doesNotContain(token);
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  private record Answer(int status, String body) {}

  private static URI uri(String target) {
    return URI.create("http://127.0.0.1:" + core.port + target);
  }

  /** A request of the window: signed as Electron's main process signs it. */
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
    HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(uri("/mcp/billing"))
        .timeout(Duration.ofSeconds(40)).header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream").header("MCP-Protocol-Version", "2025-11-25")
        .header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.ofString(message)).build(),
        HttpResponse.BodyHandlers.ofString());
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
