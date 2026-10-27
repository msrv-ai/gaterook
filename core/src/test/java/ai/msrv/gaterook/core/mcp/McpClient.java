package ai.msrv.gaterook.core.mcp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Raw JSON-RPC over HTTP against one endpoint of a test core: what a client sends, byte for byte. */
final class McpClient {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final String MODERN = "2026-07-28";

  /** One answer. */
  record Answer(int status, Map<String, String> headers, JsonNode body) {

    JsonNode result() {
      return body.path("result");
    }

    JsonNode error() {
      return body.path("error");
    }

    String header(String name) {
      return headers.get(name.toLowerCase(java.util.Locale.ROOT));
    }
  }

  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final int port;
  private final String slug;
  private final String token;
  private final AtomicInteger ids = new AtomicInteger(1);

  McpClient(int port, String slug, String token) {
    this.port = port;
    this.slug = slug;
    this.token = token;
  }

  McpClient as(String otherToken) {
    return new McpClient(port, slug, otherToken);
  }

  McpClient on(String otherSlug, String otherToken) {
    return new McpClient(port, otherSlug, otherToken);
  }

  // ── Legacy ──────────────────────────────────────────────────────────────────────────────────────────────────

  Answer legacy(String method, ObjectNode params) {
    ObjectNode body = request(method, params);
    return post(body.toString(), Map.of("MCP-Protocol-Version", "2025-11-25"));
  }

  Answer initialize(String version) {
    ObjectNode p = JsonNodeFactory.instance.objectNode();
    p.put("protocolVersion", version);
    p.putObject("capabilities").putObject("elicitation");
    p.putObject("clientInfo").put("name", "codex-mcp-client").put("version", "0.164.0");
    return post(request("initialize", p).toString(), Map.of());
  }

  Answer legacyCall(String tool, ObjectNode arguments) {
    ObjectNode p = JsonNodeFactory.instance.objectNode();
    p.put("name", tool);
    p.set("arguments", arguments);
    return legacy("tools/call", p);
  }

  // ── Modern ──────────────────────────────────────────────────────────────────────────────────────────────────

  Answer modern(String method, ObjectNode params, boolean elicitationUrl) {
    ObjectNode meta = params.has("_meta") ? (ObjectNode) params.get("_meta") : params.putObject("_meta");
    meta.put("io.modelcontextprotocol/protocolVersion", MODERN);
    ObjectNode caps = meta.putObject("io.modelcontextprotocol/clientCapabilities");
    if (elicitationUrl) {
      caps.putObject("elicitation").putObject("url");
    }
    meta.putObject("io.modelcontextprotocol/clientInfo").put("name", "codex-mcp-client").put("version", "0.164.0");
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("MCP-Protocol-Version", MODERN);
    headers.put("Mcp-Method", method);
    String name = method.equals("resources/read") ? params.path("uri").asString(null)
        : params.path("name").asString(null);
    if (name != null && (method.equals("tools/call") || method.equals("prompts/get")
        || method.equals("resources/read"))) {
      headers.put("Mcp-Name", name);
    }
    return post(request(method, params).toString(), headers);
  }

  Answer modernCall(String tool, ObjectNode arguments, boolean elicitationUrl) {
    ObjectNode p = JsonNodeFactory.instance.objectNode();
    p.put("name", tool);
    p.set("arguments", arguments);
    return modern("tools/call", p, elicitationUrl);
  }

  // ── Plumbing ────────────────────────────────────────────────────────────────────────────────────────────────

  ObjectNode request(String method, ObjectNode params) {
    ObjectNode body = JsonNodeFactory.instance.objectNode();
    body.put("jsonrpc", "2.0");
    body.put("id", ids.getAndIncrement());
    body.put("method", method);
    body.set("params", params == null ? JsonNodeFactory.instance.objectNode() : params);
    return body;
  }

  Answer post(String body, Map<String, String> headers) {
    return send("POST", body, "application/json", headers);
  }

  Answer send(String method, String body, String contentType, Map<String, String> headers) {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp/" + slug))
        .timeout(Duration.ofSeconds(60));
    if (contentType != null) {
      b.header("Content-Type", contentType);
    }
    b.header("Accept", "application/json, text/event-stream");
    if (token != null) {
      b.header("Authorization", "Bearer " + token);
    }
    headers.forEach(b::header);
    b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    try {
      HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
      Map<String, String> h = new LinkedHashMap<>();
      r.headers().map().forEach((k, v) -> h.put(k.toLowerCase(java.util.Locale.ROOT), String.join(",", v)));
      JsonNode parsed = r.body() == null || r.body().isBlank() ? JsonNodeFactory.instance.missingNode()
          : r.headers().firstValue("content-type").orElse("").startsWith("application/json")
              ? JSON.readTree(r.body()) : JsonNodeFactory.instance.textNode(r.body());
      return new Answer(r.statusCode(), h, parsed);
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  static ObjectNode args(String... pairs) {
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    for (int i = 0; i < pairs.length; i += 2) {
      n.put(pairs[i], pairs[i + 1]);
    }
    return n;
  }
}
