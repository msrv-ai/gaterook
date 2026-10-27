package ai.msrv.gaterook.core.mcp.internal;

import ai.msrv.gaterook.core.audit.AuditWriter;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshot;
import ai.msrv.gaterook.core.endpoints.ToolCatalog;
import ai.msrv.gaterook.core.platform.CallTracing;
import ai.msrv.gaterook.core.platform.Caller;
import ai.msrv.gaterook.core.platform.CanonicalJson;
import ai.msrv.gaterook.core.platform.CoreBuild;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * One request of an authenticated caller on one endpoint (ARCHITECTURE.md §5 steps 3–12): the era and its methods,
 * the endpoint's revisions, a SERVER span per request ({@code mcp.method.name}, {@code mcp.protocol.version},
 * {@code jsonrpc.request.id}, {@code gen_ai.tool.name}) under the client's {@code traceparent}, and every answer
 * written for its era. Unknown methods are {@code -32601} (modern: HTTP 404); {@code subscriptions/listen},
 * {@code completion/complete} and {@code logging/setLevel} are not offered.
 */
@Component
public class McpDispatcher {

  private static final Logger log = LoggerFactory.getLogger(McpDispatcher.class);

  public static final int PAGE = 100;

  private static final Set<String> LEGACY_METHODS = Set.of("initialize", "ping", "tools/list", "tools/call",
      "resources/list", "resources/templates/list", "resources/read", "prompts/list", "prompts/get");
  private static final Set<String> MODERN_METHODS = Set.of("server/discover", "tools/list", "tools/call",
      "resources/list", "resources/templates/list", "resources/read", "prompts/list", "prompts/get");

  /** What to answer: HTTP status and body ({@code null}: 202 without a body). */
  public record Reply(int status, ObjectNode body) {

    public static Reply accepted() {
      return new Reply(202, null);
    }
  }

  private final ToolCatalog catalogs;
  private final ToolCallHandler tools;
  private final ResourcesHandler resources;
  private final AuditWriter audit;
  private final CallTracing tracing;
  private final ClientMemory clients;
  private final JsonMapper json;

  public McpDispatcher(ToolCatalog catalogs, ToolCallHandler tools, ResourcesHandler resources, AuditWriter audit,
      CallTracing tracing, ClientMemory clients, JsonMapper json) {
    this.catalogs = catalogs;
    this.tools = tools;
    this.resources = resources;
    this.audit = audit;
    this.tracing = tracing;
    this.clients = clients;
    this.json = json;
  }

  public Reply dispatch(Envelope request, Caller caller, EndpointSnapshot snapshot) {
    Envelope env = request;
    if (env.era() == Era.LEGACY) {
      if ("initialize".equals(env.method())) {
        clients.remember(caller.principalId(), env.clientInfo());
      } else if (env.clientInfo() == null) {
        env = new Envelope(env.era(), env.version(), env.id(), env.method(), env.params(), env.clientCapabilities(),
            clients.recall(caller.principalId()), env.traceparent());
      }
    }
    if (env.notification()) {
      return Reply.accepted();
    }
    Set<String> methods = env.era() == Era.MODERN ? MODERN_METHODS : LEGACY_METHODS;
    if (!methods.contains(env.method())) {
      return error(env, JsonRpc.METHOD_NOT_FOUND, "Method not found: " + env.method(), null);
    }
    List<String> revisions = revisions(snapshot);
    if (!revisions.contains(env.era().revision())) {
      ObjectNode data = JsonNodeFactory.instance.objectNode();
      revisions.forEach(data.putArray("supported")::add);
      data.put("requested", env.version());
      int code = env.era() == Era.MODERN ? JsonRpc.UNSUPPORTED_VERSION : JsonRpc.INVALID_PARAMS;
      return new Reply(400, JsonRpc.error(env.id(), code, snapshot.slug() + " doesn't speak " + env.version()
          + ". It speaks " + String.join(" and ", revisions) + ".", data));
    }
    Map<String, String> tags = new LinkedHashMap<>();
    tags.put("mcp.method.name", env.method());
    tags.put("mcp.protocol.version", env.version());
    tags.put("jsonrpc.request.id", env.id().isString() ? env.id().asString() : env.id().toString());
    String target = env.params().path("name").asString(env.params().path("uri").asString(""));
    try (CallTracing.Started span = tracing.server((env.method() + " " + target).strip(), env.traceparent(), tags)) {
      McpRequest r = new McpRequest(env, caller, snapshot, catalogs.forEndpoint(snapshot), span);
      try {
        ObjectNode result = handle(r);
        return new Reply(200, JsonRpc.result(env.id(), result));
      } catch (McpException e) {
        span.error(Integer.toString(e.code()));
        return error(env, e.code(), e.getMessage(), e.data());
      } catch (RuntimeException e) {
        log.error("{} on {} failed", env.method(), snapshot.slug(), e);
        span.error("internal");
        return error(env, JsonRpc.INTERNAL_ERROR, "Internal error in MCP Gateway. See its log.", null);
      }
    }
  }

  private ObjectNode handle(McpRequest r) {
    Envelope env = r.env();
    return switch (env.method()) {
      case "initialize" -> initialize(r);
      case "server/discover" -> discover(r);
      case "ping" -> JsonNodeFactory.instance.objectNode();
      case "tools/list" -> ResultWriter.complete(env, toolsList(r), 30_000);
      case "tools/call" -> {
        requireAudit();
        ObjectNode result = tools.call(r);
        yield result;
      }
      case "resources/list" -> ResultWriter.complete(env, page(resources.list(r, false), "resources", env), 30_000);
      case "resources/templates/list" -> ResultWriter.complete(env, page(resources.list(r, true),
          "resourceTemplates", env), 30_000);
      case "resources/read" -> {
        requireAudit();
        yield ResultWriter.complete(env, resources.read(r), 0);
      }
      case "prompts/list" -> {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.set("prompts", Prompts.list());
        yield ResultWriter.complete(env, out, 300_000);
      }
      case "prompts/get" -> {
        String name = env.params().path("name").asString("");
        ObjectNode out = Prompts.get(name, env.params().path("arguments"))
            .orElseThrow(() -> McpException.invalidParams("Unknown prompt: " + name));
        yield ResultWriter.complete(env, out, -1);
      }
      default -> throw new McpException(JsonRpc.METHOD_NOT_FOUND, "Method not found: " + env.method());
    };
  }

  /** While a completed row waits in the audit backlog, nothing new runs (§5 step 11). */
  private void requireAudit() {
    if (!audit.available()) {
      throw new McpException(JsonRpc.INTERNAL_ERROR, "MCP Gateway is catching up with its audit: nothing runs until "
          + "it has recorded what already ran. Try again in a moment.");
    }
  }

  private ObjectNode initialize(McpRequest r) {
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    out.put("protocolVersion", r.env().version());
    out.set("capabilities", capabilities(true));
    ObjectNode info = out.putObject("serverInfo");
    info.put("name", ResultWriter.SERVER_NAME);
    info.put("title", "MCP Gateway · " + r.snapshot().slug());
    info.put("version", CoreBuild.VERSION);
    out.put("instructions", Instructions.of(r.snapshot(), r.catalog()));
    return out;
  }

  private ObjectNode discover(McpRequest r) {
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    ArrayNode versions = out.putArray("supportedVersions");
    for (String v : revisions(r.snapshot())) {
      versions.add(v);
    }
    out.set("capabilities", capabilities(false));
    out.put("instructions", Instructions.of(r.snapshot(), r.catalog()));
    return ResultWriter.complete(r.env(), out, 60_000);
  }

  private static ObjectNode capabilities(boolean legacy) {
    ObjectNode c = JsonNodeFactory.instance.objectNode();
    ObjectNode tools = c.putObject("tools");
    if (legacy) {
      // No subscriptions/listen and no GET stream: a changed list shows after its ttl or a reconnect.
      tools.put("listChanged", false);
    }
    c.putObject("resources");
    c.putObject("prompts");
    return c;
  }

  /** {@code tools/list}: pages of 100, the cursor names its offset and the list it belongs to. */
  private ObjectNode toolsList(McpRequest r) {
    List<ToolCatalog.PublishedTool> all = r.catalog().tools();
    int offset = cursor(r.env(), r.catalog().listHash());
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    ArrayNode list = out.putArray("tools");
    for (int i = offset; i < Math.min(all.size(), offset + PAGE); i++) {
      list.add(tool(all.get(i)));
    }
    if (offset + PAGE < all.size()) {
      out.put("nextCursor", encodeCursor(offset + PAGE, r.catalog().listHash()));
    }
    return out;
  }

  private static ObjectNode tool(ToolCatalog.PublishedTool t) {
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    n.put("name", t.name());
    n.put("title", t.title());
    n.put("description", t.description());
    n.set("inputSchema", t.inputSchema());
    if (t.outputSchema() != null) {
      n.set("outputSchema", t.outputSchema());
    }
    ObjectNode a = n.putObject("annotations");
    a.put("readOnlyHint", Boolean.TRUE.equals(t.annotations().readOnlyHint()));
    a.put("destructiveHint", Boolean.TRUE.equals(t.annotations().destructiveHint()));
    a.put("idempotentHint", Boolean.TRUE.equals(t.annotations().idempotentHint()));
    a.put("openWorldHint", Boolean.TRUE.equals(t.annotations().openWorldHint()));
    return n;
  }

  /** Resources come in one page: an endpoint names a handful. */
  private static ObjectNode page(ObjectNode list, String field, Envelope env) {
    if (env.params().has("cursor") && !env.params().path("cursor").isNull()) {
      ArrayNode items = (ArrayNode) list.get(field);
      items.removeAll();
    }
    return list;
  }

  private int cursor(Envelope env, String listHash) {
    JsonNode c = env.params().get("cursor");
    if (c == null || c.isNull()) {
      return 0;
    }
    try {
      JsonNode n = json.readTree(new String(Base64.getUrlDecoder().decode(c.asString("")), StandardCharsets.UTF_8));
      if (!listHash.equals(n.path("h").asString(null)) || n.path("o").asInt(-1) < 0) {
        throw McpException.invalidParams("The tool list changed: list again from the start");
      }
      return n.path("o").asInt();
    } catch (IllegalArgumentException | JacksonException e) {
      throw McpException.invalidParams("Invalid cursor");
    }
  }

  private static String encodeCursor(int offset, String listHash) {
    ObjectNode n = JsonNodeFactory.instance.objectNode().put("o", offset).put("h", listHash);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(CanonicalJson.bytes(n));
  }

  /** The revisions the endpoint speaks ({@code settings.protocol.revisions}). */
  static List<String> revisions(EndpointSnapshot s) {
    List<String> out = new ArrayList<>();
    for (JsonNode v : s.settings().path("protocol").path("revisions")) {
      out.add(v.asString());
    }
    return out.isEmpty() ? List.of(Era.MODERN_VERSION, Era.LEGACY_VERSION) : out;
  }

  private static Reply error(Envelope env, int code, String message, JsonNode data) {
    return new Reply(McpException.status(env.era(), code), JsonRpc.error(env.id(), code, message, data));
  }
}
