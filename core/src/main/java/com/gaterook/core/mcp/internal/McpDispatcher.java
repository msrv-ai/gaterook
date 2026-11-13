package com.gaterook.core.mcp.internal;

import com.gaterook.core.audit.AuditWriter;
import com.gaterook.core.endpoints.CoreTools;
import com.gaterook.core.endpoints.EndpointSnapshot;
import com.gaterook.core.endpoints.ToolCatalog;
import com.gaterook.core.endpoints.ToolSearch;
import com.gaterook.core.mcp.McpGateway;
import com.gaterook.core.platform.CallTracing;
import com.gaterook.core.platform.Caller;
import com.gaterook.core.platform.CanonicalJson;
import com.gaterook.core.platform.CoreBuild;
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
 * {@link PromptsHandler}: the gateway's own and those external MCP servers share. A call may run as a task
 * ({@link CallTasks}): the {@code tasks/*} methods of 2025-11-25, or those of the 2026-07-28 Tasks extension.
 */
@Component
public class McpDispatcher {

  private static final Logger log = LoggerFactory.getLogger(McpDispatcher.class);

  public static final int PAGE = 100;

  private static final Set<String> LEGACY_METHODS = Set.of("initialize", "ping", "tools/list", "tools/call",
      "resources/list", "resources/templates/list", "resources/read", "prompts/list", "prompts/get",
      "completion/complete", "logging/setLevel", "resources/subscribe", "resources/unsubscribe", "tasks/get",
      "tasks/result", "tasks/list", "tasks/cancel");
  /** RFC 5424 severities as MCP names them, least severe first. */
  static final List<String> LOG_LEVELS = List.of("debug", "info", "notice", "warning", "error", "critical",
      "alert", "emergency");
  private static final Set<String> MODERN_METHODS = Set.of("server/discover", "tools/list", "tools/call",
      "resources/list", "resources/templates/list", "resources/read", "prompts/list", "prompts/get",
      "completion/complete", "tasks/get", "tasks/update", "tasks/cancel");

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
  private final ResourceSubscriptions subscriptions;
  private final CallTasks tasks;
  private final JsonMapper json;

  public McpDispatcher(ToolCatalog catalogs, ToolCallHandler tools, ResourcesHandler resources,
      PromptsHandler prompts, AuditWriter audit, CallTracing tracing, ClientMemory clients, InFlight inFlight,
      ResourceSubscriptions subscriptions, CallTasks tasks, JsonMapper json) {
    this.catalogs = catalogs;
    this.tools = tools;
    this.resources = resources;
    this.prompts = prompts;
    this.audit = audit;
    this.tracing = tracing;
    this.clients = clients;
    this.inFlight = inFlight;
    this.subscriptions = subscriptions;
    this.tasks = tasks;
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
        yield runsTool(r);
      }
      case "tasks/get", "tasks/result", "tasks/list", "tasks/cancel", "tasks/update" -> task(r);
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
      case "resources/subscribe" -> {
        String uri = env.params().path("uri").asString("");
        if (!resources.subscribable(r.snapshot(), uri)) {
          throw new McpException(JsonRpc.RESOURCE_NOT_FOUND, "No updates of " + uri + " here: only resources a "
              + "server shares have them.", JsonNodeFactory.instance.objectNode().put("uri", uri));
        }
        if (!subscriptions.add(ResourceSubscriptions.legacy(r.caller().principalId()), r.snapshot().id(), uri)) {
          throw McpException.invalidParams("A client hears updates of at most " + ResourceSubscriptions.MAX_PER_OWNER
              + " resources.");
        }
        yield JsonNodeFactory.instance.objectNode();
      }
      case "resources/unsubscribe" -> {
        subscriptions.remove(ResourceSubscriptions.legacy(r.caller().principalId()),
            env.params().path("uri").asString(""));
        yield JsonNodeFactory.instance.objectNode();
      }
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
   * {@code call_tool} and {@code call_read_tool} (§5.7): the call of the tool they name with its arguments, exactly as
   * if the client had called it, before anything else looks at it. So the named tool's validation, modes, approvals,
   * rate limits, masking, audit and tasks apply, Activity shows that tool, and the retry of an approval round names it
   * as the first round did. {@code call_read_tool} refuses a tool that can change data. A name the endpoint doesn't
   * publish for the model, or one of the gateway's own, gets an error the model can act on.
   */
  private ObjectNode runsTool(McpRequest r) {
    Envelope env = r.env();
    String wrapper = env.params().path("name").asString("");
    if (!CoreTools.RUNS_TOOLS.contains(wrapper) || r.catalog().tool(wrapper).isEmpty()) {
      return call(r);
    }
    JsonNode argsNode = env.params().get("arguments");
    ObjectNode args = argsNode instanceof ObjectNode o ? o : JsonNodeFactory.instance.objectNode();
    if (argsNode != null && !argsNode.isObject() && !argsNode.isNull()) {
      throw McpException.invalidParams("params.arguments must be an object");
    }
    for (String key : args.propertyNames()) {
      if (!key.equals("name") && !key.equals("arguments")) {
        return refuse(r, wrapper + " takes name and arguments, the tool's own arguments inside arguments: "
            + "{\"name\": \"<tool>\", \"arguments\": {…}}.");
      }
    }
    JsonNode target = args.get("name");
    if (target == null || !target.isString() || target.asString().isBlank()) {
      return refuse(r, "Name the tool to run: {\"name\": \"<tool>\", \"arguments\": {…}}. find_tools finds it.");
    }
    String name = target.asString();
    JsonNode inner = args.get("arguments");
    if (inner != null && !inner.isObject() && !inner.isNull()) {
      return refuse(r, "arguments must be an object: the arguments of " + name + ".");
    }
    Optional<ToolCatalog.PublishedTool> found = r.catalog().tool(name).filter(ToolSearch::searchable);
    if (found.isEmpty()) {
      return refuse(r, r.catalog().tool(name).map(ToolCatalog.PublishedTool::core).orElse(false)
          ? name + " is listed: call it directly."
          : "There is no tool " + name + " on " + r.snapshot().slug() + ". find_tools finds its tools by what "
              + "they do.");
    }
    if (CoreTools.CALL_READ_TOOL.equals(wrapper) && !ToolSearch.readOnly(found.get())) {
      return refuse(r, name + " can change data, so call_read_tool doesn't run it: run it with call_tool.");
    }
    ObjectNode params = env.params().deepCopy();
    params.put("name", name);
    if (inner == null || inner.isNull()) {
      params.remove("arguments");
    } else {
      params.set("arguments", inner);
    }
    r.span().tag("gaterook.tool.via", wrapper);
    Envelope named = new Envelope(env.era(), env.version(), env.id(), env.method(), params, env.clientCapabilities(),
        env.clientInfo(), env.traceparent());
    return call(new McpRequest(named, r.caller(), r.snapshot(), r.catalog(), r.span(), r.stream(), r.logLevel(),
        r.client(), r.task()));
  }

  /** A wrapper's refusal: a tool error the model reads, or for a 2025-11-25 task, which expects a task, an error. */
  private static ObjectNode refuse(McpRequest r, String text) {
    Envelope env = r.env();
    if (env.era() == Era.LEGACY && Era.LEGACY_VERSION.equals(env.version()) && env.params().path("task").isObject()) {
      throw McpException.invalidParams(text);
    }
    return ResultWriter.toolError(env, text, null);
  }

  /**
   * {@code tools/call}, or the same call as a task: one with {@code params.task} of 2025-11-25 answers with the task at
   * once; one of 2026-07-28 from a client that takes tasks answers as usual when it ends within a moment, else with
   * the task. The gateway's own tools answer at once and never run as tasks; neither does the retry of an approval
   * round. When the client has too many unfinished tasks, a 2025-11-25 call is refused and a 2026-07-28 one runs as an
   * ordinary call.
   */
  private ObjectNode call(McpRequest r) {
    Envelope env = r.env();
    JsonNode asked = env.params().get("task");
    boolean legacy = env.era() == Era.LEGACY && Era.LEGACY_VERSION.equals(env.version()) && asked != null
        && asked.isObject();
    boolean modern = env.takesTasks() && !env.params().has("requestState");
    if (!legacy && !modern) {
      return tools.call(r);
    }
    String name = env.params().path("name").asString("");
    if (r.catalog().tool(name).map(ToolCatalog.PublishedTool::core).orElse(true)) {
      if (legacy && r.catalog().tool(name).isPresent()) {
        throw new McpException(JsonRpc.METHOD_NOT_FOUND, name + " doesn't run as a task.");
      }
      return tools.call(r);
    }
    Optional<CallTasks.Task> started = tasks.start(r, legacy ? CallTasks.ttl(asked.get("ttl"))
        : CallTasks.DEFAULT_TTL, tools::call);
    if (started.isEmpty()) {
      if (legacy) {
        throw new McpException(JsonRpc.OVER_BUDGET, "A client runs at most " + CallTasks.PER_CLIENT + " tasks at "
            + "once. Wait for one to end.");
      }
      return tools.call(r);
    }
    CallTasks.Task task = started.get();
    if (legacy) {
      ObjectNode created = JsonNodeFactory.instance.objectNode();
      created.set("task", tasks.legacy(task));
      return created;
    }
    return tasks.quick(task).orElseGet(() -> ResultWriter.task(tasks.modern(task, false)));
  }

  /**
   * The methods of a task: {@code tasks/get}, {@code tasks/result}, {@code tasks/list} and {@code tasks/cancel} of
   * 2025-11-25; {@code tasks/get}, {@code tasks/update} and {@code tasks/cancel} of the 2026-07-28 Tasks extension,
   * where {@code tasks/get} carries the result. A task of another client is as unknown as one that never was.
   */
  private ObjectNode task(McpRequest r) {
    Envelope env = r.env();
    String method = env.method();
    boolean modern = env.era() == Era.MODERN;
    if (!modern && (!Era.LEGACY_VERSION.equals(env.version()) || method.equals("tasks/update"))) {
      throw new McpException(JsonRpc.METHOD_NOT_FOUND, "Method not found: " + method);
    }
    if (method.equals("tasks/list")) {
      return tasks.list(r);
    }
    JsonNode id = env.params().get("taskId");
    if (id == null || !id.isString() || id.asString().isEmpty()) {
      throw McpException.invalidParams("params.taskId is required");
    }
    String taskId = id.asString();
    CallTasks.Task t = tasks.find(r, taskId).orElseThrow(() -> McpException.invalidParams("Unknown task "
        + (taskId.length() > 80 ? taskId.substring(0, 80) + "…" : taskId) + "."));
    return switch (method) {
      case "tasks/get" -> modern ? ResultWriter.complete(env, tasks.modern(t, true), -1) : tasks.legacy(t);
      case "tasks/result" -> tasks.result(t);
      case "tasks/cancel" -> {
        tasks.cancel(t);
        yield modern ? ResultWriter.complete(env, JsonNodeFactory.instance.objectNode(), -1) : tasks.legacy(t);
      }
      default -> throw McpException.invalidParams("Task " + taskId + " doesn't wait for input.");
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
    out.set("capabilities", capabilities(r.env()));
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
    out.set("capabilities", capabilities(r.env()));
    out.put("instructions", instructions(r));
    return ResultWriter.complete(r.env(), out, 60_000);
  }

  /**
   * What the gateway offers. Changes of the lists and updates of subscribed resources come on the GET stream (2025
   * revisions) or a {@code subscriptions/listen} stream (2026-07-28). Calls run as tasks with 2025-11-25 and with the
   * Tasks extension of 2026-07-28.
   */
  private static ObjectNode capabilities(Envelope env) {
    ObjectNode c = JsonNodeFactory.instance.objectNode();
    c.putObject("tools").put("listChanged", true);
    c.putObject("resources").put("subscribe", true).put("listChanged", true);
    c.putObject("prompts").put("listChanged", true);
    c.putObject("completions");
    c.putObject("logging");
    if (env.era() == Era.LEGACY && Era.LEGACY_VERSION.equals(env.version())) {
      ObjectNode t = c.putObject("tasks");
      t.putObject("list");
      t.putObject("cancel");
      t.putObject("requests").putObject("tools").putObject("call");
    }
    ObjectNode extensions = JsonNodeFactory.instance.objectNode();
    if (env.era() == Era.MODERN) {
      extensions.putObject(CallTasks.EXTENSION);
    }
    if (env.supportsUi()) {
      extensions.putObject(McpAppResource.EXTENSION);
    }
    if (!extensions.isEmpty()) {
      c.set("extensions", extensions);
    }
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

  /**
   * {@code tools/list}: pages of 100, the cursor names its offset and the list it belongs to. Only the tools the
   * catalog lists: in search, the gateway's own and those of pinned connections (§5.7).
   */
  private ObjectNode toolsList(McpRequest r) {
    boolean ui = r.env().supportsUi();
    boolean tasks = r.env().era() == Era.LEGACY && Era.LEGACY_VERSION.equals(r.env().version());
    List<ToolCatalog.PublishedTool> all = r.catalog().tools().stream().filter(t -> r.catalog().listed(t.name()))
        .filter(t -> ui || (!CoreTools.APPROVAL_CARD_ACTION.equals(t.name())
            && !CoreTools.OPEN_CONNECTION_IMPORT.equals(t.name()) && !t.appOnly())).toList();
    String listHash = r.catalog().listHash() + (ui ? ":ui" : ":text");
    int offset = cursor(r.env(), listHash);
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    ArrayNode list = out.putArray("tools");
    for (int i = offset; i < Math.min(all.size(), offset + PAGE); i++) {
      list.add(tool(all.get(i), ui, tasks));
    }
    if (offset + PAGE < all.size()) {
      out.put("nextCursor", encodeCursor(offset + PAGE, listHash));
    }
    return out;
  }

  /**
   * @param tasks whether the client's revision runs calls as tasks (2025-11-25): the gateway's own tools don't, apart
   *              from the two that run another tool, whose call is that tool's
   */
  private static ObjectNode tool(ToolCatalog.PublishedTool t, boolean ui, boolean tasks) {
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
    if (tasks && (!t.core() || CoreTools.RUNS_TOOLS.contains(t.name()))) {
      n.putObject("execution").put("taskSupport", "optional");
    }
    ObjectNode app = ui ? clientUi(t) : null;
    if (app != null) {
      n.putObject("_meta").set("ui", app);
    }
    return n;
  }

  /**
   * The tool's {@code _meta.ui} for an AI app that renders MCP Apps (ARCHITECTURE.md §5.3, §5.4). MCP Apps lets an
   * app call the tools of the server that shows it, and to an AI app the endpoint is that server: so every tool says
   * who may call it. The gateway's cards are shown by {@code approval_card} and {@code submit_connection_import}, and
   * call only {@code approval_card_action} and {@code open_connection_import}, which only an app may call; the
   * gateway's other tools are for the model alone. An MCP server's tool says what {@link
   * ToolCatalog.PublishedTool#clientUi} decides: its app, by the URI the endpoint publishes it under, and model-only
   * unless an app of its own connection is shown.
   */
  static ObjectNode clientUi(ToolCatalog.PublishedTool t) {
    if (!t.core()) {
      return t.clientUi();
    }
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    if (CoreTools.APPROVAL_CARD_ACTION.equals(t.name()) || CoreTools.OPEN_CONNECTION_IMPORT.equals(t.name())) {
      out.putArray("visibility").add("app");
      return out;
    }
    if (CoreTools.APPROVAL_CARD.equals(t.name())) {
      out.put("resourceUri", McpAppResource.URI);
    } else if (CoreTools.SUBMIT_CONNECTION_IMPORT.equals(t.name())) {
      out.put("resourceUri", ConnectionImportCards.URI);
    }
    out.putArray("visibility").add("model");
    return out;
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
