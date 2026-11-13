package ai.msrv.gaterook.e2e.modules;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.e2e.platform.CoreProcess;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * What a test of the connector modules does with one running core: the admin API as the application calls it, and an
 * agent's session on one endpoint.
 */
final class CoreClient {

  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  record Answer(int status, String body) {}

  final CoreProcess core;
  private String slug;
  private String token;

  CoreClient(CoreProcess core) {
    this.core = core;
  }

  // ── The admin API ───────────────────────────────────────────────────────────────────────────────────────────

  Answer admin(String method, String target, String body) throws Exception {
    byte[] nonce = new byte[16];
    new SecureRandom().nextBytes(nonce);
    HttpRequest.Builder b = HttpRequest.newBuilder(uri(target)).timeout(Duration.ofSeconds(150))
        .header("X-Gateway-Auth", core.secret().sign(method, target, System.currentTimeMillis(),
            Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)))
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    if (body != null) {
      b.header("Content-Type", "application/json");
    }
    HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    return new Answer(r.statusCode(), r.body());
  }

  /** A package as the UI uploads it: multipart, the field {@code file}. */
  Answer upload(String target, String fileName, byte[] bytes) throws Exception {
    String boundary = "gw-e2e-" + Long.toHexString(System.nanoTime());
    java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
    body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName
        + "\"\r\nContent-Type: application/java-archive\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    body.write(bytes);
    body.write(("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    byte[] nonce = new byte[16];
    new SecureRandom().nextBytes(nonce);
    HttpRequest request = HttpRequest.newBuilder(uri(target)).timeout(Duration.ofSeconds(150))
        .header("X-Gateway-Auth", core.secret().sign("POST", target, System.currentTimeMillis(),
            Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)))
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
    HttpResponse<String> r = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    return new Answer(r.statusCode(), r.body());
  }

  JsonNode json(Answer answer, int status) {
    // An answer the core didn't mean to give says nothing of why: its log does.
    assertThat(answer.status()).as(() -> answer.status() >= 500 ? answer.body() + "\n" + core.coreLog()
        : answer.body()).isEqualTo(status);
    return CoreProcess.JSON.readTree(answer.body());
  }

  JsonNode get(String target) throws Exception {
    return json(admin("GET", target, null), 200);
  }

  /** A module as {@code GET /api/modules} shows it. */
  JsonNode module(String id) throws Exception {
    JsonNode modules = get("/api/modules");
    for (JsonNode m : modules.path("modules")) {
      if (m.path("id").asString().equals(id)) {
        return m;
      }
    }
    throw new AssertionError("No module " + id + " in " + modules);
  }

  /** Waits until the module is in {@code state}. */
  JsonNode awaitModule(String id, String state, Duration within) throws Exception {
    JsonNode module = null;
    long deadline = System.nanoTime() + within.toNanos();
    while (System.nanoTime() < deadline) {
      module = module(id);
      if (module.path("state").asString().equals(state)) {
        return module;
      }
      Thread.sleep(200);
    }
    throw new AssertionError(id + " didn't become " + state + ": " + module + "\n" + core.coreLog());
  }

  /** The card of a connector type in Add connection. */
  JsonNode card(String type) throws Exception {
    for (JsonNode group : get("/api/connector-types").path("groups")) {
      for (JsonNode card : group.path("cards")) {
        if (card.path("type").asString().equals(type) && card.path("variant").isNull()) {
          return card;
        }
      }
    }
    throw new AssertionError("No card of " + type);
  }

  /** One tool of a connection on an endpoint, as {@code GET /api/endpoints/{slug}/tools} shows it. */
  JsonNode tool(String endpoint, String connection, String name) throws Exception {
    for (JsonNode g : get("/api/endpoints/" + endpoint + "/tools")) {
      if (g.path("connection").asString().equals(connection)) {
        for (JsonNode t : g.path("tools")) {
          if (t.path("name").asString().equals(name)) {
            return t;
          }
        }
      }
    }
    throw new AssertionError("No " + name + " of " + connection + " on " + endpoint);
  }

  /** The modes of a connection's tools on an endpoint, by tool name. */
  Map<String, String> modes(String endpoint, String connection) throws Exception {
    Map<String, String> out = new LinkedHashMap<>();
    get("/api/endpoints/" + endpoint + "/tools").forEach(g -> {
      if (g.path("connection").asString().equals(connection)) {
        g.path("tools").forEach(t -> out.put(t.path("name").asString(), t.path("mode").asString()));
      }
    });
    return out;
  }

  /** Sets a tool's mode as a person does on the endpoint's page. */
  void mode(String endpoint, String connection, String name, String mode) throws Exception {
    JsonNode row = tool(endpoint, connection, name);
    json(admin("PUT", "/api/endpoints/" + endpoint + "/tools", CoreProcess.JSON.createObjectNode()
        .put("toolKey", row.path("toolKey").asString()).put("connection", connection).put("mode", mode)
        .put("version", row.path("version").asLong()).put("reason", "Module e2e").toString()), 200);
  }

  // ── An agent on an endpoint ─────────────────────────────────────────────────────────────────────────────────

  /**
   * An endpoint with the connection, approvals that don't hold the call, and an agent's session on it.
   *
   * @return the agent's token, which a later core over the same home takes too ({@link #session})
   */
  String endpoint(String endpoint, String connection) throws Exception {
    json(admin("POST", "/api/endpoints", "{\"slug\":\"" + endpoint + "\",\"startFrom\":\"empty\"}"), 201);
    json(admin("POST", "/api/endpoints/" + endpoint + "/connections/" + connection, "{}"), 200);
    JsonNode settings = get("/api/endpoints/" + endpoint + "/settings");
    json(admin("PUT", "/api/endpoints/" + endpoint + "/settings", "{\"approvalWait\":{\"firstWaitSeconds\":0},"
        + "\"version\":" + settings.path("version").asLong() + "}"), 200);
    String created = json(admin("POST", "/api/endpoints/" + endpoint + "/tokens",
        "{\"name\":\"e2e\",\"ttlDays\":1}"), 201).path("token").asString();
    session(endpoint, created);
    return created;
  }

  /** An agent's session on an endpoint that exists, with a token it was given before. */
  void session(String endpoint, String agentToken) throws Exception {
    slug = endpoint;
    token = agentToken;
    mcp("""
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},
         "clientInfo":{"name":"modules-e2e","version":"1"}}}""");
  }

  /** The names of the tools the agent is offered. */
  java.util.List<String> tools(int id) throws Exception {
    java.util.List<String> names = new java.util.ArrayList<>();
    mcp("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/list\",\"params\":{}}").at("/result/tools")
        .forEach(t -> names.add(t.path("name").asString()));
    return names;
  }

  /** {@code tools/call} as the agent. */
  JsonNode call(int id, String tool, String arguments) throws Exception {
    return mcp("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
        + "\",\"arguments\":" + arguments + "}}");
  }

  /** Approves a pending request as the person does in the app and waits for its result. */
  JsonNode approve(int id, String handle) throws Exception {
    JsonNode detail = get("/api/approvals/" + handle);
    json(admin("POST", "/api/approvals/" + handle + "/decision", "{\"decision\":\"approve\",\"digest\":\""
        + detail.path("digest").asString() + "\",\"via\":\"app\"}"), 200);
    return call(id, "approval_status", "{\"handle\":\"" + handle + "\",\"wait_seconds\":20}");
  }

  private JsonNode mcp(String message) throws Exception {
    HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(uri("/mcp/" + slug))
        .timeout(Duration.ofSeconds(90)).header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream").header("MCP-Protocol-Version", "2025-11-25")
        .header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.ofString(message)).build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    return CoreProcess.JSON.readTree(r.body());
  }

  private URI uri(String target) {
    return URI.create("http://127.0.0.1:" + core.port + target);
  }
}
