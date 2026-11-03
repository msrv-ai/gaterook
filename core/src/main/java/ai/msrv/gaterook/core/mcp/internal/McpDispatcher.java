package ai.msrv.gaterook.core.mcp.internal;

import ai.msrv.gaterook.core.audit.AuditWriter;
import ai.msrv.gaterook.core.endpoints.CoreTools;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshot;
import ai.msrv.gaterook.core.endpoints.ToolCatalog;
import ai.msrv.gaterook.core.mcp.McpGateway;
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
import java.util.Optional;
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
 * written for its era. Unknown methods are {@code -32601} (modern: HTTP 404). Prompts and completions come from
 * {@link PromptsHandler}: the gateway's own and those external MCP servers share.
 */
@Component
public class McpDispatcher {

  private static final Logger log = LoggerFactory.getLogger(McpDispatcher.class);

  public static final int PAGE = 100;

  private static final Set<String> LEGACY_METHODS = Set.of("initialize", "ping", "tools/list", "tools/call",
      "resources/list", "resources/templates/list", "resources/read", "prompts/list", "prompts/get",
      "completion/complete", "logging/setLevel");
  /** RFC 5424 severities as MCP names them, least severe first. */
  static final List<String> LOG_LEVELS = List.of("debug", "info", "notice", "warning", "error", "critical",
      "alert", "emergency");
  private static final Set<String> MODERN_METHODS = Set.of("server/discover", "tools/list", "tools/call",
      "resources/list", "resources/templates/list", "resources/read", "prompts/list", "prompts/get",
      "completion/complete");

  /** What to answer: HTTP status and body ({@code null}: 202 without a body). */
  public record Reply(int status, ObjectNode body) {

    public static Reply accepted() {
      return new Reply(202, null);
    }
  }

  private final ToolCatalog catalogs;
  private final ToolCallHandler tools;
  private final ResourcesHandler resources;
  private final PromptsHandler prompts;
  private final AuditWriter audit;
  private final CallTracing tracing;
  private final ClientMemory clients;
  private final InFlight inFlight;
  private final JsonMapper json;

  public McpDispatcher(ToolCatalog catalogs, ToolCallHandler tools, ResourcesHandler resources,
      PromptsHandler prompts, AuditWriter audit, CallTracing tracing, ClientMemory clients, InFlight inFlight,
      JsonMapper json) {
    this.catalogs = catalogs;
    this.tools = tools;
    this.resources = resources;
    this.prompts = prompts;
    this.audit = audit;
    this.tracing = tracing;
    this.clients = clients;
    this.inFlight = inFlight;
    this.json = json;
  }

  /**
   * @param bridgeClient the request's {@code X-Gateway-Bridge-Client} ({@link BridgeClient}), or {@code null}; only a
   *                     bridge caller's is read, and only on a legacy request after {@code initialize}
   */
  public Reply dispatch(Envelope request, Caller caller, EndpointSnapshot snapshot, String bridgeClient) {
    return dispatch(request, caller, snapshot, bridgeClient, McpGateway.Stream.NONE);
  }

  /** @param stream where the request's notifications go before its answer */
  public Reply dispatch(Envelope request, Caller caller, EndpointSnapshot snapshot, String bridgeClient,
      McpGateway.Stream stream) {
    // Which client of the principal: a bridge's declaration tells the clients that share its grant apart.
    String client = caller.kind() == Caller.Kind.BRIDGE && bridgeClient != null
        ? CanonicalJson.sha256Hex(JsonNodeFactory.instance.stringNode(bridgeClient)).substring(0, 16) : null;
    Envelope env = request;
    if (env.era() == Era.LEGACY) {
      if ("initialize".equals(env.method())) {
        clients.remember(caller.principalId(), env.clientInfo(), env.clientCapabilities());
      } else {
        // A bridge repeats its own client's declaration: neither a core restart nor another client on the same
        // grant decides what this connection is shown. Other legacy clients fall back to the principal's last one.
        Optional<BridgeClient> declared = caller.kind() == Caller.Kind.BRIDGE
            ? BridgeClient.parse(bridgeClient, json) : Optional.empty();
        JsonNode capabilities = declared.isPresent() ? declared.get().capabilities()
            : clients.capabilities(caller.principalId());
        JsonNode info = env.clientInfo() != null ? env.clientInfo()
            : declared.isPresent() ? declared.get().clientInfo() : clients.recall(caller.principalId());
        env = new Envelope(env.era(), env.version(), env.id(), env.method(), env.params(), capabilities, info,
            env.traceparent());
      }
    }
    if (env.notification()) {
      if ("notifications/cancelled".equals(env.method())) {
        // The client gave up its call: it stops as its deadline would; an approval a person decides isn't touched.
        inFlight.cancel(InFlight.Key.of(caller.principalId(), client, env.params().get("requestId")));
      }
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
      McpRequest r = new McpRequest(env, caller, snapshot, catalogs.forEndpoint(snapshot), span,
          stream == null ? McpGateway.Stream.NONE : stream, logLevel(env, caller, client), client);
      try {
        ObjectNode result = handle(r);
        return new Reply(200, JsonRpc.result(env.id(), result));
      } catch (McpException e) {
        span.error(Integer.toString(e.code()));
        return error(env, e.code(), e.getMessage(), e.data());
      } catch (RuntimeException e) {
        log.error("{} on {} failed", env.method(), snapshot.slug(), e);
        span.error("internal");
        return error(env, JsonRpc.INTERNAL_ERROR, "Internal error in GateRook. See its log.", null);
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
      // The gateway's own prompts are fresh for five minutes; a server that shares its own can change them sooner.
      case "prompts/list" -> ResultWriter.complete(env, prompts.list(r), prompts.shared(r) ? 30_000 : 300_000);
      case "prompts/get" -> {
        requireAudit();
        yield ResultWriter.complete(env, prompts.get(r), -1);
      }
      case "completion/complete" -> ResultWriter.complete(env, prompts.complete(r), -1);
      case "logging/setLevel" -> {
        String level = env.params().path("level").asString("");
        if (!LOG_LEVELS.contains(level)) {
          throw McpException.invalidParams("params.level must be one of " + String.join(", ", LOG_LEVELS));
        }
        clients.logLevel(r.caller().principalId(), r.client(), level);
        yield JsonNodeFactory.instance.objectNode();
      }
      default -> throw new McpException(JsonRpc.METHOD_NOT_FOUND, "Method not found: " + env.method());
    };
  }

  /**
   * The least severe level of the log messages the client wants with this request: 2026-07-28 says it in each
   * request's {@code _meta}; the 2025 revisions with {@code logging/setLevel}, once. {@code null}: none.
   */
  private String logLevel(Envelope env, Caller caller, String client) {
    if (env.era() == Era.MODERN) {
      String level = env.meta().path("io.modelcontextprotocol/logLevel").asString(null);
      return level != null && LOG_LEVELS.contains(level) ? level : null;
    }
    return clients.logLevel(caller.principalId(), client);
  }

  /** While a completed row waits in the audit backlog, nothing new runs (§5 step 11). */
  private void requireAudit() {
    if (!audit.available()) {
      throw new McpException(JsonRpc.INTERNAL_ERROR, "GateRook is catching up with its audit: nothing runs until "
          + "it has recorded what already ran. Try again in a moment.");
    }
  }

  private ObjectNode initialize(McpRequest r) {
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    out.put("protocolVersion", r.env().version());
    out.set("capabilities", capabilities(true, r.env().supportsUi()));
    ObjectNode info = out.putObject("serverInfo");
    info.put("name", ResultWriter.SERVER_NAME);
    info.put("title", "GateRook · " + r.snapshot().slug());
    info.put("version", CoreBuild.VERSION);
    out.put("instructions", instructions(r));
    return out;
  }

  private ObjectNode discover(McpRequest r) {
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    ArrayNode versions = out.putArray("supportedVersions");
    for (String v : revisions(r.snapshot())) {
      versions.add(v);
    }
    out.set("capabilities", capabilities(false, r.env().supportsUi()));
    out.put("instructions", instructions(r));
    return ResultWriter.complete(r.env(), out, 60_000);
  }

  private static ObjectNode capabilities(boolean legacy, boolean ui) {
    ObjectNode c = JsonNodeFactory.instance.objectNode();
    ObjectNode tools = c.putObject("tools");
    if (legacy) {
      tools.put("listChanged", true);
    }
    c.putObject("resources");
    c.putObject("prompts");
    c.putObject("completions");
    c.putObject("logging");
    if (ui) c.putObject("extensions").putObject(McpAppResource.EXTENSION);
    return c;
  }

  private static String instructions(McpRequest r) {
    String base = Instructions.of(r.snapshot(), r.catalog());
    return !r.env().supportsUi() ? base : base + "\n\nOnly when a tool returns a pending approval, show "
        + "approval_card(handle) once so the user can review it. Do not show a card for completed requests or "
        + "calls that run with bypass. When temporary bypass is enabled, report its expiry as text. "
        + "Keep polling approval_status for pending or running requests; "
        + "never repeat the original operation. The card can decline or open GateRook. Only the person can approve "
        + "in GateRook; a card interaction is not proof of approval.";
  }

  /** {@code tools/list}: pages of 100, the cursor names its offset and the list it belongs to. */
  private ObjectNode toolsList(McpRequest r) {
    boolean ui = r.env().supportsUi();
    List<ToolCatalog.PublishedTool> all = r.catalog().tools().stream()
        .filter(t -> ui || (!CoreTools.APPROVAL_CARD_ACTION.equals(t.name()) && !t.appOnly())).toList();
    String listHash = r.catalog().listHash() + (ui ? ":ui" : ":text");
    int offset = cursor(r.env(), listHash);
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    ArrayNode list = out.putArray("tools");
    for (int i = offset; i < Math.min(all.size(), offset + PAGE); i++) {
      list.add(tool(all.get(i), ui));
    }
    if (offset + PAGE < all.size()) {
      out.put("nextCursor", encodeCursor(offset + PAGE, listHash));
    }
    return out;
  }

  private static ObjectNode tool(ToolCatalog.PublishedTool t, boolean ui) {
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    n.put("name", t.name());
    n.put("title", t.title());
    n.put("description", t.description());
    n.set("inputSchema", t.clientInputSchema());
    if (t.outputSchema() != null) {
      n.set("outputSchema", t.outputSchema());
    }
    ObjectNode a = n.putObject("annotations");
    a.put("readOnlyHint", Boolean.TRUE.equals(t.annotations().readOnlyHint()));
    a.put("destructiveHint", Boolean.TRUE.equals(t.annotations().destructiveHint()));
    a.put("idempotentHint", Boolean.TRUE.equals(t.annotations().idempotentHint()));
    a.put("openWorldHint", Boolean.TRUE.equals(t.annotations().openWorldHint()));
    if (ui && CoreTools.APPROVAL_CARD.equals(t.name())) {
      n.putObject("_meta").putObject("ui").put("resourceUri", McpAppResource.URI);
    } else if (ui && CoreTools.APPROVAL_CARD_ACTION.equals(t.name())) {
      n.putObject("_meta").putObject("ui").putArray("visibility").add("app");
    } else if (ui && t.meta() != null && t.meta().path("ui").isObject()) {
      // An MCP server's app, by the URI the endpoint publishes it under: read through the gateway like any resource.
      n.putObject("_meta").set("ui", t.meta().get("ui").deepCopy());
    }
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
