package ai.msrv.gaterook.core.playground;

import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.core.audit.AuditQueries;
import ai.msrv.gaterook.core.endpoints.CoreTools;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshot;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshotService;
import ai.msrv.gaterook.core.endpoints.ToolCatalog;
import ai.msrv.gaterook.core.mcp.McpGateway;
import ai.msrv.gaterook.core.platform.Caller;
import ai.msrv.gaterook.core.platform.CoreBuild;
import ai.msrv.gaterook.core.platform.Ingress;
import ai.msrv.gaterook.core.platform.ProblemException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Playground (ARCHITECTURE.md §2.2 {@code playground}, §6 playground): the person calls an endpoint's tools the way
 * an agent would, in the protocol revision they pick, through the same MCP pipeline — policy, approvals for anything
 * that changes data, masking, limits and audit. The principal is {@code playground}, a principal that is never
 * revoked and whose calls Activity shows with the client {@code playground}. What the page shows is the JSON-RPC on
 * the wire, byte for byte as a client of that revision would see it.
 */
@Service
public class PlaygroundService {

  /** The principal of every Playground call. */
  public static final Caller CALLER = new Caller(Caller.Kind.PLAYGROUND, "playground", "playground", "playground",
      Ingress.PLAYGROUND, "127.0.0.1");

  /** The revisions the Playground speaks: one per era of the core. */
  public static final String MODERN = "2026-07-28";
  public static final String LEGACY = "2025-11-25";

  private static final String META_VERSION = "io.modelcontextprotocol/protocolVersion";
  private static final String META_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities";
  private static final String META_CLIENT = "io.modelcontextprotocol/clientInfo";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /**
   * One tool of the endpoint, Off ones too (they block Run with their reason).
   *
   * @param mode          the strictest mode a call of the tool meets: {@code APPROVAL}, {@code READ_ONLY} or
   *                      {@code OFF} ({@code approval_status}, which has none: {@code ALWAYS})
   * @param connections   the connections whose pair is on; the {@code connection} argument picks one
   * @param needsApproval a call waits for a person ("This call needs approval" before it runs)
   */
  public record Tool(String name, String title, String description, String mode, boolean runnable, String reason,
      boolean needsApproval, List<String> connections, ToolDefinition.Annotations annotations, String declaredBy,
      JsonNode inputSchema) {}

  /** {@code GET /api/playground/tools}: every tool and what {@code tools/list} of the era sends. */
  public record Tools(String endpoint, String era, List<Tool> tools, ObjectNode wirePreview) {}

  /** What a call came to, as the Structured, Text and JSON-RPC tabs show it. */
  public record CallResult(String callId, int httpStatus, ObjectNode request, ObjectNode response,
      JsonNode structured, String text, boolean isError, Meta meta, Approval approval) {}

  /** {@code masked}: how many values the gateway replaced with ‹masked›. */
  public record Meta(long durationMs, long bytes, int masked, boolean truncated) {}

  /** The request a mutation became, to follow in Approvals. */
  public record Approval(String handle, String status) {}

  private final EndpointSnapshotService snapshots;
  private final ToolCatalog catalog;
  private final McpGateway gateway;
  private final AuditQueries audit;
  private final AtomicLong ids = new AtomicLong();

  public PlaygroundService(EndpointSnapshotService snapshots, ToolCatalog catalog, McpGateway gateway,
      AuditQueries audit) {
    this.snapshots = snapshots;
    this.catalog = catalog;
    this.gateway = gateway;
    this.audit = audit;
  }

  /** @throws ProblemException 404 for an unknown endpoint, 422 for an unknown revision */
  public Tools tools(String slug, String era) {
    String version = era(era);
    EndpointSnapshot s = snapshot(slug);
    ToolCatalog.Catalog published = catalog.forEndpoint(s);
    Map<String, List<EndpointSnapshot.Pair>> byName = new TreeMap<>();
    s.pairs().forEach(p -> byName.computeIfAbsent(p.tool().name(), k -> new ArrayList<>()).add(p));
    List<Tool> out = new ArrayList<>();
    byName.forEach((name, pairs) -> out.add(tool(s, name, pairs, published.tool(name))));
    for (String core : CoreTools.WITH_MODE) {
      Mode mode = s.coreTool(core).map(EndpointSnapshot.CorePair::mode).orElse(Mode.OFF);
      ToolDefinition d = CoreTools.withMode(core);
      String reason = blockedReason(s).orElse(mode == Mode.OFF ? core + " is Off on " + slug + "." : null);
      out.add(new Tool(core, d.title(), d.description(), modeId(mode), reason == null, reason, false, List.of(),
          d.annotations(), null, published.tool(core).map(ToolCatalog.PublishedTool::inputSchema)
              .map(JsonNode.class::cast).orElse(d.inputSchema())));
    }
    ToolDefinition status = CoreTools.approvalStatus();
    out.add(new Tool(status.name(), status.title(), status.description(), "ALWAYS", blockedReason(s).isEmpty(),
        blockedReason(s).orElse(null), false, List.of(), status.annotations(), null, status.inputSchema()));
    ObjectNode list = JsonNodeFactory.instance.objectNode();
    list.put("jsonrpc", "2.0");
    list.put("id", ids.incrementAndGet());
    list.put("method", "tools/list");
    ObjectNode params = list.putObject("params");
    meta(params, version);
    McpGateway.Response r = gateway.handle(slug, CALLER, list, headers(version, "tools/list", null));
    return new Tools(slug, version, List.copyOf(out), r.body());
  }

  /**
   * {@code POST /api/playground/call}: one {@code tools/call} in the era, exactly as a client would send it.
   *
   * @throws ProblemException 404 for an unknown endpoint, 422 for an unknown revision or a missing tool name
   */
  public CallResult call(String slug, String tool, JsonNode arguments, String era) {
    String version = era(era);
    snapshot(slug);
    if (tool == null || tool.isBlank()) {
      throw ProblemException.unprocessable("Choose a tool", "Pick the tool to call.", "/tool");
    }
    if (arguments != null && !arguments.isNull() && !arguments.isObject()) {
      throw ProblemException.unprocessable("Invalid arguments", "Arguments are a JSON object.", "/arguments");
    }
    ObjectNode request = JsonNodeFactory.instance.objectNode();
    request.put("jsonrpc", "2.0");
    request.put("id", ids.incrementAndGet());
    request.put("method", "tools/call");
    ObjectNode params = request.putObject("params");
    params.put("name", tool);
    params.set("arguments", arguments == null || arguments.isNull() ? JsonNodeFactory.instance.objectNode()
        : arguments.deepCopy());
    meta(params, version);
    long started = System.nanoTime();
    McpGateway.Response r = gateway.handle(slug, CALLER, request, headers(version, "tools/call", tool));
    long durationMs = (System.nanoTime() - started) / 1_000_000;
    ObjectNode body = r.body() == null ? JsonNodeFactory.instance.objectNode() : r.body();
    JsonNode result = body.path("result");
    JsonNode meta = result.path("_meta");
    StringBuilder text = new StringBuilder();
    for (JsonNode c : result.path("content")) {
      if ("text".equals(c.path("type").asString(null))) {
        text.append(text.isEmpty() ? "" : "\n").append(c.path("text").asString(""));
      }
    }
    if (body.has("error")) {
      text.append(body.path("error").path("message").asString(""));
    }
    JsonNode structured = result.get("structuredContent");
    Approval approval = null;
    JsonNode approvalMeta = meta.path("ai.msrv.gaterook/approval");
    if (approvalMeta.isObject()) {
      approval = new Approval(approvalMeta.path("handle").asString(null), approvalMeta.path("status").asString(null));
    } else if (structured != null && structured.path("handle").isString()) {
      approval = new Approval(structured.get("handle").asString(), structured.path("status").asString(null));
    }
    long bytes = JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8).length;
    Meta m = new Meta(durationMs, bytes, meta.path("ai.msrv.gaterook/masked").path("fields").asInt(0),
        meta.path("ai.msrv.gaterook/truncated").asBoolean(false) || structured != null
            && structured.path("truncated").asBoolean(false));
    return new CallResult(meta.path("ai.msrv.gaterook/callId").asString(null), r.status(), request, body, structured,
        text.toString(), body.has("error") || result.path("isError").asBoolean(false), m, approval);
  }

  /**
   * What a client learns from {@code initialize} on the endpoint (legacy revision): its instructions, capabilities and
   * server info, as Endpoint › Settings › Check counts them.
   */
  public JsonNode initialize(String slug) {
    snapshot(slug);
    ObjectNode request = JsonNodeFactory.instance.objectNode();
    request.put("jsonrpc", "2.0");
    request.put("id", ids.incrementAndGet());
    request.put("method", "initialize");
    ObjectNode params = request.putObject("params");
    params.put("protocolVersion", LEGACY);
    params.putObject("capabilities");
    params.putObject("clientInfo").put("name", "gaterook-playground").put("version", CoreBuild.VERSION);
    McpGateway.Response r = gateway.handle(slug, CALLER, request, headers(LEGACY, "initialize", null));
    return r.body() == null ? JsonNodeFactory.instance.objectNode() : r.body().path("result");
  }

  /** {@code GET /api/playground/history}: the Playground's own calls on the endpoint, newest first. */
  public AuditQueries.Events history(String slug, String cursor, int limit) {
    snapshot(slug);
    return audit.events("client:playground endpoint:" + slug, null, null, cursor, limit);
  }

  private Tool tool(EndpointSnapshot s, String name, List<EndpointSnapshot.Pair> pairs,
      Optional<ToolCatalog.PublishedTool> published) {
    EndpointSnapshot.Pair first = pairs.get(0);
    boolean approval = pairs.stream().anyMatch(p -> p.mode() == Mode.APPROVAL);
    boolean on = pairs.stream().anyMatch(EndpointSnapshot.Pair::on);
    String mode = approval ? Mode.APPROVAL.name() : on ? Mode.READ_ONLY.name() : Mode.OFF.name();
    List<String> connections = pairs.stream().filter(EndpointSnapshot.Pair::on)
        .map(EndpointSnapshot.Pair::connectionName).sorted().distinct().toList();
    Optional<String> blocked = blockedReason(s);
    String reason = blocked.orElse(!on ? name + " is Off on " + s.slug() + ". Turn it on in the endpoint's Tools to "
        + "call it." : published.isEmpty() ? name + " isn't published on " + s.slug() + ": another tool has its name."
        : null);
    boolean needsApproval = approval || first.tool().mutating();
    JsonNode schema = published.<JsonNode>map(ToolCatalog.PublishedTool::inputSchema)
        .orElse(first.tool().inputSchema());
    return new Tool(name, first.tool().title(), first.tool().description(), mode, reason == null, reason,
        needsApproval, connections, first.tool().annotations(), first.declaredBy(), schema);
  }

  private static Optional<String> blockedReason(EndpointSnapshot s) {
    if (!s.enabled()) {
      return Optional.of(s.slug() + " is disabled. Enable it to call its tools.");
    }
    if (s.blocked()) {
      return Optional.of(s.slug() + ": " + s.blockedReason() + ". Review it first.");
    }
    return Optional.empty();
  }

  private EndpointSnapshot snapshot(String slug) {
    return snapshots.bySlug(slug == null ? "" : slug).orElseThrow(() -> ProblemException.notFound(
        "Endpoint not found", "There is no endpoint " + slug + "."));
  }

  private static String era(String era) {
    if (era == null || era.isBlank() || MODERN.equals(era)) {
      return MODERN;
    }
    if (LEGACY.equals(era)) {
      return LEGACY;
    }
    throw ProblemException.unprocessable("Unknown revision", "The Playground speaks " + MODERN + " and " + LEGACY
        + ".", "/era");
  }

  /** The modern revision carries its version, capabilities and client in {@code _meta}; the legacy one doesn't. */
  private static void meta(ObjectNode params, String version) {
    if (!MODERN.equals(version)) {
      return;
    }
    ObjectNode meta = params.putObject("_meta");
    meta.put(META_VERSION, MODERN);
    // No elicitation: an approval comes back as a handle the person follows in Approvals, never as MRTR.
    meta.putObject(META_CAPABILITIES);
    meta.putObject(META_CLIENT).put("name", "gaterook-playground").put("version", CoreBuild.VERSION);
  }

  private static Function<String, String> headers(String version, String method, String name) {
    Map<String, String> h = new LinkedHashMap<>();
    h.put("mcp-protocol-version", version);
    if (MODERN.equals(version)) {
      h.put("mcp-method", method);
      if (name != null) {
        h.put("mcp-name", name);
      }
    }
    return header -> h.get(header.toLowerCase(Locale.ROOT));
  }

  private static String modeId(Mode mode) {
    return mode.name();
  }
}
