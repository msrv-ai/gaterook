package com.gaterook.connectors.mcpserver;

import com.gaterook.connectors.support.PrettyJson;
import com.gaterook.connector.api.ActionPreview;
import com.gaterook.connector.api.Classification;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Connector;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ConnectorManifest;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connector.api.HealthResult;
import com.gaterook.connector.api.PolicyView;
import com.gaterook.connector.api.SecretResolver;
import com.gaterook.connector.api.TestResult;
import com.gaterook.connector.api.ToolCall;
import com.gaterook.connector.api.ToolDefinition;
import com.gaterook.connector.api.ToolResult;
import com.gaterook.connectors.support.mcp.McpServerRuntime;
import com.gaterook.connectors.support.mcp.VerifiedProfiles;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The connector of one {@code mcp-server} connection: the tools of the person's reviewed snapshot, published under the
 * server's prefix, each call forwarded as {@code tools/call} to the server's process (or HTTP session) with the
 * upstream name (report 11 §3.1). The manifest's version is the server's own {@code serverInfo.version}, retained
 * as the provenance of its tool definitions.
 *
 * <p>Before every call the definition the running server lists for the tool must hash as the snapshot does, and show
 * the same app: a server that changed a tool after it was reviewed (a rug pull) is refused until the core took the new
 * list in, and an approval made for one definition never runs against another. That the core took the list in does not
 * make the new definition the person's: the endpoints then take a tool that ran without a person (Read only or Bypass)
 * back to Approval, or to Off on a read-only endpoint, until its mode is set again, and a tool in Approval or Off stays
 * there (ARCHITECTURE.md §7.5).
 */
final class ServerConnector implements Connector {

  private final McpServers runtime;
  private final ConnectionConfig cfg;
  private final McpServerRuntime.Snapshot snapshot;
  private final ServerConfig server;
  private final ConnectorManifest manifest;
  /** GateRook's verified profile of the vendor's server these settings name, or {@code null}. */
  private final VerifiedProfiles.Profile profile;

  ServerConnector(McpServers runtime, ConnectionConfig cfg, McpServerRuntime.Snapshot snapshot,
      ConnectorManifest type) {
    this.runtime = runtime;
    this.cfg = cfg;
    this.snapshot = snapshot;
    this.server = ServerConfig.of(cfg);
    this.profile = VerifiedProfiles.of(cfg.config()).orElse(null);
    List<ToolDefinition> tools = new ArrayList<>();
    for (JsonNode entry : snapshot.tools()) {
      tools.add(Snapshots.definition(entry, cfg.name(), rule(entry)));
    }
    String version = snapshot.serverVersion() == null || snapshot.serverVersion().isBlank() ? "unknown"
        : snapshot.serverVersion();
    this.manifest = ConnectorManifest.builder(type.id(), version, ConnectorManifest.Kind.MCP_SERVER)
        .name(type.name())
        .icon(type.icon())
        .group(type.group())
        .description(type.description())
        .configSchema(type.configSchema())
        .tools(tools)
        .dynamicTools(true)
        .build();
  }

  @Override
  public ConnectorManifest manifest() {
    return manifest;
  }

  @Override
  public int maxConcurrency() {
    return server.maxInFlight();
  }

  @Override
  public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
    Optional<JsonNode> entry = Snapshots.find(snapshot.tools(), call.tool());
    if (entry.isEmpty()) {
      return Classification.deny(policy.rule("unknown-tool"), call.tool() + " isn't among the reviewed tools of "
          + cfg.name() + ".", "Open Connectors › MCP servers to see what it lists now.");
    }
    JsonNode e = entry.get();
    if (read(e)) {
      return Classification.read();
    }
    ObjectNode facts = Texts.JSON.createObjectNode();
    facts.put("server", cfg.name());
    facts.put("tool", Texts.str(e.path("original"), call.tool()));
    VerifiedProfiles.ToolRule rule = rule(e);
    if (rule != null) {
      facts.put("profile", profile.id());
      return Classification.mutation(rule.reason() + " (GateRook's verified profile of " + profile.name() + ")",
          facts);
    }
    return Classification.mutation(cfg.name() + " doesn't declare " + Texts.str(e.path("original"), call.tool())
        + " read-only, so it may change data.", facts);
  }

  /** What the verified profile says of a snapshot entry's tool, or {@code null}. */
  private VerifiedProfiles.ToolRule rule(JsonNode entry) {
    return profile == null ? null : profile.rule(entry.path("original").asString("")).orElse(null);
  }

  /** A read runs without a person: declared read-only, and not a tool the verified profile names. */
  private boolean read(JsonNode entry) {
    return rule(entry) == null && Snapshots.declaresReadOnly(entry.path("annotations").isObject()
        ? (ObjectNode) entry.path("annotations") : null);
  }

  @Override
  public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
    JsonNode entry = Snapshots.find(snapshot.tools(), call.tool()).orElseThrow(() -> new IllegalArgumentException(
        call.tool() + " isn't a reviewed tool of " + cfg.name()));
    String original = entry.path("original").asString();
    ObjectNode request = Texts.JSON.createObjectNode();
    request.put("method", "tools/call");
    ObjectNode params = request.putObject("params");
    params.put("name", original);
    params.set("arguments", call.arguments() == null ? Texts.JSON.createObjectNode() : call.arguments().deepCopy());
    String text = PrettyJson.writer(Texts.JSON).writeValueAsString(request);
    ObjectNode payload = Texts.JSON.createObjectNode();
    payload.put("tool", call.tool());
    payload.put("original", original);
    payload.set("arguments", params.get("arguments").deepCopy());
    payload.put("definitionHash", entry.path("definitionHash").asString());
    // The app the tool shows is pinned beside the hash, which doesn't cover it (null: none).
    JsonNode app = Snapshots.reviewedApp(entry);
    if (app == null) {
      payload.putNull("ui");
    } else {
      payload.set("ui", app.deepCopy());
    }
    payload.put("serverVersion", manifest.version());
    String where = server.http() ? server.url() : String.join(" ", server.command());
    List<ActionPreview.Field> fields = List.of(new ActionPreview.Field("Server", cfg.name() + " " + manifest.version()),
        new ActionPreview.Field("Tool", original), new ActionPreview.Field(server.http() ? "URL" : "Command",
            Texts.clean(where, 300, false)));
    List<String> warnings = new ArrayList<>();
    VerifiedProfiles.ToolRule rule = rule(entry);
    if (rule != null) {
      warnings.add(rule.reason() + " GateRook's verified profile of " + profile.name() + " keeps it out of Read only.");
    } else if (entry.path("annotations").isObject()) {
      warnings.add("Its annotations are declared by " + cfg.name() + ", not verified by GateRook.");
    }
    warnings.add("GateRook can't see what the server does with these arguments: it may change data or send them "
        + "elsewhere.");
    String summary = Texts.clean(call.tool() + " on " + cfg.name(), 120, false);
    return ActionPreview.of(ActionPreview.Kind.JSON, "json", text, cfg.name() + " · " + Texts.clean(where, 120, false),
        summary).withFields(fields).withWarnings(warnings).withPayload(payload);
  }

  @Override
  public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    try {
      McpServerRuntime.Handshake h = runtime.start(cfg, snapshot, secrets, ctx, false);
      return Tests.success(cfg.name(), h, snapshot);
    } catch (ConnectorException e) {
      return Tests.failure(e, cfg);
    }
  }

  /** The periodic health check never starts a sleeping server: it pings a running one and reports the state else. */
  @Override
  public HealthResult health(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    Session s = runtime.running(cfg.id());
    if (s != null) {
      long t0 = System.nanoTime();
      try {
        s.client.ping(Session.min(Duration.ofSeconds(5), ctx.remaining()));
        return HealthResult.healthy((System.nanoTime() - t0) / 1_000_000);
      } catch (ConnectorException e) {
        return HealthResult.unreachable(e.getMessage());
      }
    }
    McpServerRuntime.ProcessView view = runtime.process(cfg.id()).orElse(null);
    if (view == null || view.state() == McpServerRuntime.State.STOPPED) {
      return new HealthResult(HealthResult.State.HEALTHY, 0, "Sleeping: starts on the next call");
    }
    return switch (view.state()) {
      case FAILED -> HealthResult.unreachable(view.detail() == null ? "Failed" : view.detail());
      case BACKOFF, CRASHED -> new HealthResult(HealthResult.State.DEGRADED, 0, view.detail());
      default -> new HealthResult(HealthResult.State.HEALTHY, 0, view.detail());
    };
  }

  @Override
  public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    JsonNode entry = Snapshots.find(snapshot.tools(), call.tool()).orElseThrow(() -> ConnectorException.badInput(
        call.tool() + " isn't among the reviewed tools of " + cfg.name() + "."));
    boolean read = read(entry);
    if (!read && !ctx.approved()) {
      throw ConnectorException.denied(call.tool() + " may change data: it runs only after a person approved it.");
    }
    String original = entry.path("original").asString();
    String hash = entry.path("definitionHash").asString();
    JsonNode arguments = call.arguments() == null ? Texts.JSON.createObjectNode() : call.arguments();
    Optional<JsonNode> approved = ctx.approvedPayload();
    if (approved.isPresent()) {
      JsonNode p = approved.get();
      // A payload prepared before the app was pinned has no "ui" at all: it could not name one.
      boolean sameApp = !p.has("ui") || Objects.equals(Snapshots.reviewedApp(p), Snapshots.reviewedApp(entry));
      if (!hash.equals(Texts.str(p.path("definitionHash"), null)) || !call.tool().equals(Texts.str(p.path("tool"),
          null)) || !sameApp) {
        throw ConnectorException.denied(cfg.name() + " changed " + original + " after the call was approved, so the "
            + "approved call doesn't run. Ask again.");
      }
      arguments = p.path("arguments").isObject() ? p.path("arguments") : Texts.JSON.createObjectNode();
    }
    Session s = runtime.session(cfg, snapshot, secrets, ctx, false, Supervisor.Why.CALL);
    taint(s, ctx);
    ObjectNode live = s.tool(original);
    if (live == null) {
      throw ConnectorException.unavailable(cfg.name() + " no longer lists " + original + ". Its tools are listed "
          + "again in Connectors › MCP servers.", null);
    }
    if (!hash.equals(Snapshots.definitionHash(live)) || !Snapshots.sameApp(entry, live)) {
      throw ConnectorException.denied(cfg.name() + " changed " + original + " since it was reviewed, so the call "
          + "doesn't run. The new definition is in Connectors › MCP servers; where the tool ran without approval, "
          + "its endpoints ask for approval again, or turn it off where they are read only.");
    }
    ObjectNode params = Texts.JSON.createObjectNode();
    params.put("name", original);
    params.set("arguments", arguments.isObject() ? arguments.deepCopy() : Texts.JSON.createObjectNode());
    Duration budget = Session.min(server.callTimeout(), ctx.remaining());
    // Over 2026-07-28 HTTP the arguments a tool marks x-mcp-header go into Mcp-Param-* headers too.
    Map<String, String> headers = s.client.era() == UpstreamClient.Era.MODERN && s.http != null
        ? UpstreamClient.paramHeaders(live.get("inputSchema"), params.get("arguments")) : Map.of();
    UpstreamClient.Ask ask = new UpstreamClient.Ask(budget, watch(ctx), ctx, headers);
    s.inFlight.incrementAndGet();
    try {
      ObjectNode result = asTask(entry, s) ? s.client.callAsTask(params, ask)
          : s.client.request("tools/call", params, ask);
      return Results.of(result, server.shareResources() ? cfg.name() : null);
    } catch (ConnectorException e) {
      throw callFailed(e, s, read, original, budget);
    } finally {
      s.inFlight.decrementAndGet();
      s.lastUsed = runtime.now();
      // What the call may have started is known before the root can crash and orphan it.
      s.observe();
    }
  }

  /**
   * What the call's client hears of it, when it asked: the server's progress, and its log messages at the client's
   * level (2026-07-28 asks the server for that level with the request). The core masks both before they leave.
   */
  static UpstreamClient.Watch watch(ExecContext ctx) {
    String level = ctx.clientLogLevel();
    if (!ctx.wantsProgress() && level == null) {
      return UpstreamClient.Watch.NONE;
    }
    return new UpstreamClient.Watch(ctx.wantsProgress() ? p -> ctx.progress(p.path("progress").asDouble(0),
        p.path("total").isNumber() ? p.get("total").asDouble() : null, Texts.str(p.get("message"), null)) : null,
        level == null ? null : l -> ctx.clientLog(Texts.str(l.get("level"), "info"), Texts.str(l.get("logger"),
            null), l.get("data")), level);
  }

  /**
   * Whether a call runs as a task: the tool requires one, and the server runs tool calls as tasks (2025-11-25). A
   * tool that only may is called directly, and so is one whose server doesn't say it can.
   */
  private static boolean asTask(JsonNode entry, Session s) {
    UpstreamClient.Hello h = s.init;
    return "required".equals(Texts.str(entry.get("taskSupport"), null)) && h != null
        && h.era() == UpstreamClient.Era.LEGACY
        && h.capabilities().path("tasks").path("requests").path("tools").path("call").isObject();
  }

  /** What a failed call tells the agent: a server's own error is data; a lost process may have done it. */
  private ConnectorException callFailed(ConnectorException e, Session s, boolean read, String original,
      Duration budget) {
    McpServerRuntime.SignInRequired signIn = McpServerRuntime.SignInRequired.in(e);
    if (signIn != null) {
      // The server refused the token before it read the call, or no token went at all.
      return ConnectorException.unavailable(signIn.getMessage() + " " + original + " didn't run.", signIn);
    }
    if (Channel.SessionGone.in(e)) {
      runtime.broken(s, "The server ended its session");
      return ConnectorException.unavailable(cfg.name() + " ended its session before it took the call; it didn't "
          + "run. Try again.", e);
    }
    for (Throwable t = e.getCause(); t != null; t = t.getCause()) {
      if (t instanceof Rpc.Error) {
        return ConnectorException.resourceError(cfg.name() + " refused " + original + ": " + e.getMessage(), e);
      }
      if (t.getCause() == t) {
        break;
      }
    }
    if (!s.alive() || (s.http != null && e.kind() == ConnectorException.Kind.RESOURCE_UNAVAILABLE)) {
      if (s.http != null) {
        runtime.broken(s, "The connection broke during a call");
      }
      String what = cfg.name() + (s.http != null ? " lost the connection" : " stopped") + " while it ran " + original;
      return read ? ConnectorException.unavailable(what + ".", e)
          : ConnectorException.outcomeUnknown(what + ": it may have done it. Check before you try again.", e);
    }
    if (e.kind() == ConnectorException.Kind.TIMEOUT && !read) {
      return ConnectorException.outcomeUnknown(cfg.name() + " didn't answer " + original + " in "
          + Math.max(1, budget.toSeconds()) + " s: it may still do it. Check before you try again.", e);
    }
    return e;
  }

  // ── What the server shares besides tools (ARCHITECTURE.md §5.4) ──────────────────────────────────────────────

  @Override
  public boolean sharesResources(ConnectionConfig cfg) {
    return server.shareResources();
  }

  @Override
  public boolean sharesPrompts(ConnectionConfig cfg) {
    return server.sharePrompts();
  }

  @Override
  public JsonNode listResources(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, boolean templates)
      throws ConnectorException {
    if (!server.shareResources()) {
      return null;
    }
    return shared(secrets, ctx, s -> {
      if (!s.init.has("resources")) {
        return Federation.resources(cfg.name(), List.of(), templates);
      }
      Session.Listed cached = templates ? s.templates : s.resources;
      if (cached != null && cached.fresh(runtime.now())) {
        return cached.list().deepCopy();
      }
      List<ObjectNode> listed = s.client.list(templates ? "resources/templates/list" : "resources/list",
          templates ? "resourceTemplates" : "resources", Session.min(Duration.ofSeconds(30), ctx.remaining()),
          Federation.MAX_ITEMS);
      ObjectNode list = Federation.resources(cfg.name(), listed, templates);
      Session.Listed fresh = new Session.Listed(list, Map.of(), runtime.now().plus(Session.LIST_TTL));
      if (templates) {
        s.templates = fresh;
      } else {
        s.resources = fresh;
      }
      return list.deepCopy();
    });
  }

  /**
   * A shared resource of the server, or the app of a reviewed tool ({@code ui://}): an app is read whether or not the
   * server shares its resources, since the tool that names it was reviewed, but only an app some tool names.
   */
  @Override
  public ToolResult readResource(String uri, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    String upstream = Federation.upstream(cfg.name(), uri).orElseThrow(() -> ConnectorException.badInput(
        "No resource " + uri));
    boolean app = uri.startsWith(com.gaterook.connector.api.ResourceDescriptor.appPrefix(cfg.name()));
    if (app ? snapshot.tools().stream().noneMatch(t -> upstream.equals(Texts.str(t.path("ui").get("resourceUri"),
        null))) : !server.shareResources()) {
      throw ConnectorException.denied(app ? "No reviewed tool of " + cfg.name() + " shows " + uri + "."
          : cfg.name() + " doesn't share its resources. A person can let it in Connectors › MCP servers.");
    }
    JsonNode read = shared(secrets, ctx, s -> s.client.request("resources/read",
        Texts.JSON.createObjectNode().put("uri", upstream), new UpstreamClient.Ask(Session.min(server.callTimeout(),
            ctx.remaining()), UpstreamClient.Watch.NONE, ctx, Map.of())));
    return Federation.contents(cfg.name(), read);
  }

  @Override
  public JsonNode listPrompts(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    if (!server.sharePrompts()) {
      return null;
    }
    return shared(secrets, ctx, s -> prompts(s, ctx).list().deepCopy());
  }

  @Override
  public JsonNode getPrompt(String name, JsonNode arguments, ConnectionConfig cfg, SecretResolver secrets,
      ExecContext ctx) throws ConnectorException {
    if (!server.sharePrompts()) {
      throw ConnectorException.denied(cfg.name() + " doesn't share its prompts.");
    }
    return shared(secrets, ctx, s -> {
      String original = prompts(s, ctx).originals().get(name);
      if (original == null) {
        throw ConnectorException.badInput(cfg.name() + " has no prompt " + name + ".");
      }
      ObjectNode params = Texts.JSON.createObjectNode().put("name", original);
      params.set("arguments", arguments != null && arguments.isObject() ? arguments.deepCopy()
          : Texts.JSON.createObjectNode());
      JsonNode got = s.client.request("prompts/get", params, new UpstreamClient.Ask(Session.min(server.callTimeout(),
          ctx.remaining()), UpstreamClient.Watch.NONE, ctx, Map.of()));
      return Federation.prompt(cfg.name(), got);
    });
  }

  @Override
  public JsonNode complete(JsonNode params, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    String type = Texts.str(params.path("ref").get("type"), "");
    if (type.equals("ref/prompt") ? !server.sharePrompts() : !server.shareResources()) {
      return null;
    }
    return shared(secrets, ctx, s -> {
      if (!s.init.has("completions")) {
        return null;
      }
      if (!(params.deepCopy() instanceof ObjectNode p) || !(p.get("ref") instanceof ObjectNode ref)) {
        return null;
      }
      if (type.equals("ref/prompt")) {
        String original = prompts(s, ctx).originals().get(Texts.str(ref.get("name"), ""));
        if (original == null) {
          return null;
        }
        ref.put("name", original);
      } else {
        String upstream = Federation.upstream(cfg.name(), Texts.str(ref.get("uri"), "")).orElse(null);
        if (upstream == null) {
          return null;
        }
        ref.put("uri", upstream);
      }
      return s.client.request("completion/complete", p, new UpstreamClient.Ask(Session.min(Duration.ofSeconds(10),
          ctx.remaining()), UpstreamClient.Watch.NONE, ctx, Map.of()));
    });
  }

  /** Only what the server shares: an app isn't a resource clients subscribe to. */
  @Override
  public void subscriptions(ConnectionConfig cfg, java.util.Set<String> uris) {
    runtime.subscriptions(cfg.id(), cfg.name(), server.shareResources() ? uris : java.util.Set.of());
  }

  /** The prompts of the server under the connection's prefix, listed again when the cached list is stale. */
  private Session.Listed prompts(Session s, ExecContext ctx) throws ConnectorException {
    Session.Listed cached = s.prompts;
    if (cached != null && cached.fresh(runtime.now())) {
      return cached;
    }
    List<ObjectNode> listed = s.init.has("prompts") ? s.client.list("prompts/list", "prompts",
        Session.min(Duration.ofSeconds(30), ctx.remaining()), Federation.MAX_ITEMS) : List.of();
    Federation.Prompts mapped = Federation.prompts(snapshot.toolPrefix(), listed);
    Session.Listed fresh = new Session.Listed(mapped.list(), mapped.originals(), runtime.now().plus(Session.LIST_TTL));
    s.prompts = fresh;
    return fresh;
  }

  /** What one exchange with the running server does, on the session a call would use. */
  private interface Exchange<T> {
    T run(Session s) throws ConnectorException;
  }

  /** {@code work} on the session (started if it sleeps), counted as in flight so it isn't stopped for idleness. */
  private <T> T shared(SecretResolver secrets, ExecContext ctx, Exchange<T> work) throws ConnectorException {
    Session s = runtime.session(cfg, snapshot, secrets, ctx, false, Supervisor.Why.CALL);
    taint(s, ctx);
    s.inFlight.incrementAndGet();
    try {
      return work.run(s);
    } catch (ConnectorException e) {
      if (Channel.SessionGone.in(e) || (s.http != null && e.kind() == ConnectorException.Kind.RESOURCE_UNAVAILABLE
          && !s.alive())) {
        runtime.broken(s, "The connection broke");
      }
      throw e;
    } finally {
      s.inFlight.decrementAndGet();
      s.lastUsed = runtime.now();
    }
  }

  /**
   * The values that reached the process when it started (maybe in an earlier call), the tokens of its sign-in and its
   * cloud credentials: this call's output is masked for them too.
   */
  private static void taint(Session s, ExecContext ctx) {
    for (String v : s.secretValues) {
      ctx.taint(v);
    }
    if (s.http != null && s.http.authorization() != null) {
      for (String v : s.http.authorization().secrets()) {
        ctx.taint(v);
      }
    }
    if (s.http != null && s.http.credentials() != null) {
      for (String v : s.http.credentials().secrets()) {
        ctx.taint(v);
      }
    }
  }

  @Override
  public Optional<JsonNode> describe(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, String object) {
    ObjectNode out = Texts.JSON.createObjectNode();
    out.put("server", cfg.name());
    out.put("version", manifest.version());
    if (snapshot.protocolVersion() != null) {
      out.put("protocol", snapshot.protocolVersion());
    }
    Session running = runtime.running(cfg.id());
    out.put("transport", running == null ? server.transport() : running.transport());
    out.put("tools", snapshot.tools().size());
    out.put("annotationsDeclared", Snapshots.annotated(snapshot.tools()));
    out.put("sharesResources", server.shareResources());
    out.put("sharesPrompts", server.sharePrompts());
    out.put("note", "Tool annotations are declared by the server, not verified by GateRook.");
    return Optional.of(out);
  }

  @Override
  public void onConnectionRemoved(String connectionId) {
    runtime.forget(connectionId);
  }
}
