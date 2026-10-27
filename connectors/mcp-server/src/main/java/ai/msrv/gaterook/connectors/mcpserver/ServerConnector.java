package ai.msrv.gaterook.connectors.mcpserver;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.support.mcp.McpServerRuntime;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The connector of one {@code mcp-server} connection: the tools of the person's reviewed snapshot, published under the
 * server's prefix, each call forwarded as {@code tools/call} to the server's process (or HTTP session) with the
 * upstream name (report 11 §3.1). The manifest's version is the server's own {@code serverInfo.version}: a Read only
 * confirmation binds to it, so a new server version asks again.
 *
 * <p>Before every call the definition the running server lists for the tool must hash as the snapshot does: a server
 * that changed a tool after it was reviewed (a rug pull) is refused until the core took the new list in, and an
 * approval made for one definition never runs against another.
 */
final class ServerConnector implements Connector {

  private final McpServers runtime;
  private final ConnectionConfig cfg;
  private final McpServerRuntime.Snapshot snapshot;
  private final ServerConfig server;
  private final ConnectorManifest manifest;

  ServerConnector(McpServers runtime, ConnectionConfig cfg, McpServerRuntime.Snapshot snapshot,
      ConnectorManifest type) {
    this.runtime = runtime;
    this.cfg = cfg;
    this.snapshot = snapshot;
    this.server = ServerConfig.of(cfg);
    List<ToolDefinition> tools = new ArrayList<>();
    for (JsonNode entry : snapshot.tools()) {
      tools.add(Snapshots.definition(entry, cfg.name()));
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
    if (Snapshots.declaresReadOnly(e.path("annotations").isObject() ? (ObjectNode) e.path("annotations") : null)) {
      return Classification.read();
    }
    ObjectNode facts = Texts.JSON.createObjectNode();
    facts.put("server", cfg.name());
    facts.put("tool", Texts.str(e.path("original"), call.tool()));
    return Classification.mutation(cfg.name() + " doesn't declare " + Texts.str(e.path("original"), call.tool())
        + " read-only, so it may change data.", facts);
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
    String text = Texts.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(request);
    ObjectNode payload = Texts.JSON.createObjectNode();
    payload.put("tool", call.tool());
    payload.put("original", original);
    payload.set("arguments", params.get("arguments").deepCopy());
    payload.put("definitionHash", entry.path("definitionHash").asString());
    payload.put("serverVersion", manifest.version());
    String where = server.http() ? server.url() : String.join(" ", server.command());
    List<ActionPreview.Field> fields = List.of(new ActionPreview.Field("Server", cfg.name() + " " + manifest.version()),
        new ActionPreview.Field("Tool", original), new ActionPreview.Field(server.http() ? "URL" : "Command",
            Texts.clean(where, 300, false)));
    List<String> warnings = new ArrayList<>();
    if (entry.path("annotations").isObject()) {
      warnings.add("Its annotations are declared by " + cfg.name() + ", not verified by MCP Gateway.");
    }
    warnings.add("MCP Gateway can't see what the server does with these arguments: it may change data or send them "
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
        s.await(s.client.ping(), Session.min(Duration.ofSeconds(5), ctx.remaining()), "ping");
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
    boolean read = Snapshots.declaresReadOnly(entry.path("annotations").isObject()
        ? (ObjectNode) entry.path("annotations") : null);
    if (!read && !ctx.approved()) {
      throw ConnectorException.denied(call.tool() + " may change data: it runs only after a person approved it.");
    }
    String original = entry.path("original").asString();
    String hash = entry.path("definitionHash").asString();
    JsonNode arguments = call.arguments() == null ? Texts.JSON.createObjectNode() : call.arguments();
    Optional<JsonNode> approved = ctx.approvedPayload();
    if (approved.isPresent()) {
      JsonNode p = approved.get();
      if (!hash.equals(Texts.str(p.path("definitionHash"), null)) || !call.tool().equals(Texts.str(p.path("tool"),
          null))) {
        throw ConnectorException.denied(cfg.name() + " changed " + original + " after the call was approved, so the "
            + "approved call doesn't run. Ask again.");
      }
      arguments = p.path("arguments").isObject() ? p.path("arguments") : Texts.JSON.createObjectNode();
    }
    Session s = runtime.session(cfg, snapshot, secrets, ctx, false, Supervisor.Why.CALL);
    // The values reached the process when it started (maybe in an earlier call): this call's output masks them too.
    for (String v : s.secretValues) {
      ctx.taint(v);
    }
    McpSchema.Tool live = null;
    for (McpSchema.Tool t : s.tools) {
      if (original.equals(t.name())) {
        live = t;
      }
    }
    if (live == null) {
      throw ConnectorException.unavailable(cfg.name() + " no longer lists " + original + ". Its tools are listed "
          + "again in Connectors › MCP servers.", null);
    }
    if (!hash.equals(Snapshots.definitionHash(live))) {
      throw ConnectorException.denied(cfg.name() + " changed " + original + " since it was reviewed, so the call "
          + "doesn't run. The new definition is in Connectors › MCP servers; its endpoints ask for approval again.");
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> args = Texts.JSON.treeToValue(arguments, Map.class);
    Duration budget = Session.min(server.callTimeout(), ctx.remaining());
    s.inFlight.incrementAndGet();
    try {
      McpSchema.CallToolResult result = s.await(s.client.callTool(McpSchema.CallToolRequest.builder(original)
          .arguments(args).build()), budget, original);
      return Results.of(result);
    } catch (ConnectorException e) {
      throw callFailed(e, s, read, original, budget);
    } finally {
      s.inFlight.decrementAndGet();
      s.lastUsed = runtime.now();
      // What the call may have started is known before the root can crash and orphan it.
      s.observe();
    }
  }

  /** What a failed call tells the agent: a server's own error is data; a lost process may have done it. */
  private ConnectorException callFailed(ConnectorException e, Session s, boolean read, String original,
      Duration budget) {
    Throwable cause = e.getCause();
    for (Throwable t = cause; t != null; t = t.getCause()) {
      if (t instanceof McpTransportSessionNotFoundException) {
        runtime.broken(s, "The server ended its session");
        return ConnectorException.unavailable(cfg.name() + " ended its session before it took the call; it didn't "
            + "run. Try again.", e);
      }
      if (t instanceof McpError) {
        return ConnectorException.resourceError(cfg.name() + " refused " + original + ": " + e.getMessage(), e);
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

  @Override
  public Optional<JsonNode> describe(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, String object) {
    ObjectNode out = Texts.JSON.createObjectNode();
    out.put("server", cfg.name());
    out.put("version", manifest.version());
    if (snapshot.protocolVersion() != null) {
      out.put("protocol", snapshot.protocolVersion());
    }
    out.put("transport", server.transport());
    out.put("tools", snapshot.tools().size());
    out.put("annotationsDeclared", Snapshots.annotated(snapshot.tools()));
    out.put("note", "Tool annotations are declared by the server, not verified by MCP Gateway.");
    return Optional.of(out);
  }

  @Override
  public void onConnectionRemoved(String connectionId) {
    runtime.forget(connectionId);
  }
}
