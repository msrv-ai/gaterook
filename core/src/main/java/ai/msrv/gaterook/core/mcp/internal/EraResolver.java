package ai.msrv.gaterook.core.mcp.internal;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Chooses the era of one request (ARCHITECTURE.md §5 step 3, report 01 §3.1), in this order:
 * <ol>
 *   <li>{@code params._meta["io.modelcontextprotocol/protocolVersion"]} is a string → modern: the version must be
 *       {@code 2026-07-28} ({@code -32022} otherwise); {@code MCP-Protocol-Version}, {@code Mcp-Method} and, for
 *       {@code tools/call}, {@code prompts/get} and {@code resources/read}, {@code Mcp-Name} must equal the body
 *       ({@code =?base64?…?=} decoded first; {@code -32020}); {@code clientCapabilities} must be there ({@code -32602});</li>
 *   <li>{@code initialize} → legacy; 2025-11-25, 2025-06-18 or 2025-03-26 as asked, else 2025-11-25;</li>
 *   <li>a {@code 2026-07-28} header without the {@code _meta} version → {@code -32602}, so a modern client doesn't fall
 *       back silently;</li>
 *   <li>else legacy by the header, and without it 2025-03-26 (the spec allows assuming it).</li>
 * </ol>
 * Every rejection is HTTP 400. Basic JSON-RPC shape ({@code jsonrpc}, {@code method}, {@code id}) is checked first.
 */
public final class EraResolver {

  public static final String META_VERSION = "io.modelcontextprotocol/protocolVersion";
  public static final String META_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities";
  public static final String META_CLIENT = "io.modelcontextprotocol/clientInfo";

  public static final List<String> MODERN = List.of(Era.MODERN_VERSION);
  public static final List<String> LEGACY = List.of("2025-11-25", "2025-06-18", "2025-03-26");

  /** The methods whose name or URI goes into {@code Mcp-Name}. */
  private static final Set<String> NAMED = Set.of("tools/call", "prompts/get", "resources/read");
  private static final Pattern BASE64_SENTINEL = Pattern.compile("^=\\?base64\\?(.*)\\?=$");

  /** A request refused before any method runs: always HTTP 400. */
  public static final class Rejected extends McpException {
    private static final long serialVersionUID = 1L;
    private final transient JsonNode id;

    Rejected(JsonNode id, int code, String message, JsonNode data) {
      super(code, message, data);
      this.id = id;
    }

    public JsonNode id() {
      return id;
    }
  }

  private EraResolver() {}

  /**
   * @param header the request's headers by name, case-insensitive
   * @throws Rejected with the JSON-RPC error to answer (HTTP 400)
   */
  public static Envelope resolve(ObjectNode req, Function<String, String> header) {
    JsonNode id = req.get("id");
    JsonNode replyId = id == null || !(id.isString() || id.isIntegralNumber()) ? null : id;
    if (!"2.0".equals(req.path("jsonrpc").asString(null))) {
      throw new Rejected(replyId, JsonRpc.INVALID_REQUEST, "Not a JSON-RPC 2.0 request", null);
    }
    if (!req.has("method") && (req.has("result") || req.has("error"))) {
      throw new Rejected(replyId, JsonRpc.INVALID_REQUEST, "MCP Gateway sends no requests, so it takes no responses",
          null);
    }
    String method = req.path("method").isString() ? req.get("method").asString() : null;
    if (method == null || method.isEmpty()) {
      throw new Rejected(replyId, JsonRpc.INVALID_REQUEST, "A request needs a method", null);
    }
    if (id != null && !(id.isString() || id.isIntegralNumber())) {
      throw new Rejected(null, JsonRpc.INVALID_REQUEST, "The id must be a string or an integer", null);
    }
    JsonNode paramsNode = req.get("params");
    if (paramsNode != null && !paramsNode.isObject() && !paramsNode.isNull()) {
      throw new Rejected(replyId, JsonRpc.INVALID_REQUEST, "params must be an object", null);
    }
    ObjectNode params = paramsNode != null && paramsNode.isObject() ? (ObjectNode) paramsNode
        : JsonNodeFactory.instance.objectNode();
    JsonNode meta = params.path("_meta");
    String traceparent = meta.path("traceparent").isString() ? meta.get("traceparent").asString() : null;
    String headerVersion = header.apply("MCP-Protocol-Version");

    JsonNode metaVersion = meta.get(META_VERSION);
    if (metaVersion != null && metaVersion.isString()) {
      String v = metaVersion.asString();
      if (!MODERN.contains(v)) {
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.putArray("supported").add(Era.MODERN_VERSION).add(Era.LEGACY_VERSION);
        data.put("requested", v);
        throw new Rejected(id, JsonRpc.UNSUPPORTED_VERSION, "Unsupported protocol version " + v, data);
      }
      if (!v.equals(headerVersion)) {
        throw new Rejected(id, JsonRpc.HEADER_MISMATCH, "MCP-Protocol-Version must be " + v, null);
      }
      if (!method.equals(header.apply("Mcp-Method"))) {
        throw new Rejected(id, JsonRpc.HEADER_MISMATCH, "Mcp-Method must be " + method, null);
      }
      if (NAMED.contains(method)) {
        String name = method.equals("resources/read") ? params.path("uri").asString(null)
            : params.path("name").asString(null);
        String sent = decodeName(header.apply("Mcp-Name"));
        if (name == null || !name.equals(sent)) {
          throw new Rejected(id, JsonRpc.HEADER_MISMATCH, "Mcp-Name must match the "
              + (method.equals("resources/read") ? "uri" : "name") + " of the request", null);
        }
      }
      JsonNode capabilities = meta.get(META_CAPABILITIES);
      if (capabilities == null || !capabilities.isObject()) {
        throw new Rejected(id, JsonRpc.INVALID_PARAMS, "Missing _meta." + META_CAPABILITIES, null);
      }
      return new Envelope(Era.MODERN, v, id, method, params, capabilities, meta.get(META_CLIENT), traceparent);
    }
    // initialize comes before the header check (report 01 §3.1: the order matters): a client that sends the modern
    // header on its initialize still negotiates a legacy version instead of being turned away.
    if ("initialize".equals(method)) {
      String asked = params.path("protocolVersion").asString(null);
      String version = asked != null && LEGACY.contains(asked) ? asked : Era.LEGACY_VERSION;
      JsonNode caps = params.path("capabilities");
      return new Envelope(Era.LEGACY, version, id, method, params, caps.isObject() ? caps
          : JsonNodeFactory.instance.objectNode(), params.get("clientInfo"), traceparent);
    }
    if (headerVersion != null && MODERN.contains(headerVersion)) {
      throw new Rejected(id, JsonRpc.INVALID_PARAMS, "Missing _meta." + META_VERSION, null);
    }
    String version = headerVersion == null ? "2025-03-26" : headerVersion;
    if (!LEGACY.contains(version)) {
      throw new Rejected(id, JsonRpc.INVALID_REQUEST, "Unsupported MCP-Protocol-Version " + headerVersion, null);
    }
    return new Envelope(Era.LEGACY, version, id, method, params, JsonNodeFactory.instance.objectNode(), null,
        traceparent);
  }

  /** {@code Mcp-Name} as sent, with the base64 sentinel for non-ASCII names decoded. */
  static String decodeName(String header) {
    if (header == null) {
      return null;
    }
    Matcher m = BASE64_SENTINEL.matcher(header);
    if (!m.matches()) {
      return header;
    }
    try {
      return new String(Base64.getDecoder().decode(m.group(1)), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
