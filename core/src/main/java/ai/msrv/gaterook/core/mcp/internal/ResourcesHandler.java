package ai.msrv.gaterook.core.mcp.internal;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ResourceDescriptor;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.core.audit.AuditUnavailableException;
import ai.msrv.gaterook.core.audit.AuditWriter;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshot;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshotService;
import ai.msrv.gaterook.core.endpoints.ToolCatalog;
import ai.msrv.gaterook.core.invocation.ConnectorInvoker;
import ai.msrv.gaterook.core.invocation.ToolGuard;
import ai.msrv.gaterook.core.platform.CanonicalJson;
import ai.msrv.gaterook.core.platform.Ids;
import ai.msrv.gaterook.core.platform.MaskPolicy;
import ai.msrv.gaterook.core.policy.Decision;
import ai.msrv.gaterook.core.policy.PairView;
import ai.msrv.gaterook.core.policy.PolicyEngine;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Resources (report 01 §3.6, ARCHITECTURE.md §5.4), from three places, every one under the URI namespace of the
 * connection it belongs to ({@link ResourceDescriptor#prefix}):
 *
 * <ul>
 *   <li>what connectors offer as {@code mcpgw://connections/<name>/…} — the schema of a database, the runbooks of a
 *       host — listed only while one of the connection's pairs runs at once (Read only): reading a resource is a read
 *       without a person, so a connection whose tools all wait for approval offers none. A descriptor whose URI holds
 *       {@code {…}} is a template;</li>
 *   <li>what an external MCP server the person let share its resources lists itself, and its resource templates:
 *       asked for when listed, read as the server reads them, through the connection's READ pair ({@link Sharing});</li>
 *   <li>the MCP Apps of a server's tools ({@code ui://gaterook/connections/<name>/…}), for clients that render them:
 *       readable while a tool that names the app is on. An app is code, not data: its HTML is masked for the call's
 *       secrets and known credential formats, never for entropy or personal data, which would break it.</li>
 * </ul>
 *
 * <p>Resources pass the same fail-closed gates as {@code tools/call} (§4 "Integrity", §5 step 7): an endpoint whose
 * rows didn't verify offers nothing (its pair modes can't be trusted), and every resource is decided by
 * {@link PolicyEngine#decide} as a READ of its pair, so a policy that didn't verify ({@code core.integrity}) or a prod
 * connection outside a prod endpoint lists nothing and reads nothing. {@code resources/read} then goes the way of a
 * READ tool call: audit {@code received} (a refusal is recorded too), the invoker (secrets, guard, masking, limits),
 * audit {@code completed}, then the answer.
 */
@Component
public class ResourcesHandler {

  /** The pseudo-tool of resource reads in the audit. */
  static final String TOOL = "resources/read";

  /** An app's HTML is code: the call's secrets and credential formats are masked in it, nothing else. */
  static final MaskPolicy APP_MASK = new MaskPolicy(false, Set.of(), List.of());

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{[a-zA-Z0-9_]+\\}");

  private final ConnectorRegistry registry;
  private final PolicyEngine engine;
  private final ToolGuard guard;
  private final AuditWriter audit;
  private final ConnectorInvoker invoker;
  private final Calls calls;
  private final EndpointSnapshotService snapshots;

  public ResourcesHandler(ConnectorRegistry registry, PolicyEngine engine, ToolGuard guard, AuditWriter audit,
      ConnectorInvoker invoker, Calls calls, EndpointSnapshotService snapshots) {
    this.registry = registry;
    this.engine = engine;
    this.guard = guard;
    this.audit = audit;
    this.invoker = invoker;
    this.calls = calls;
    this.snapshots = snapshots;
  }

  /** One offered resource and the pair it reads through. */
  private record Offer(ResourceDescriptor descriptor, EndpointSnapshot.Pair pair, Pattern template) {}

  /**
   * An app of a tool on the endpoint: its URI, the pair of the tool that names it, and the READ pair its HTML is read
   * through (whatever the tool's own mode: showing the app is not calling the tool).
   */
  private record App(String uri, EndpointSnapshot.Pair pair, PairView view) {}

  public ObjectNode list(McpRequest r, boolean templates) {
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    ArrayNode items = out.putArray(templates ? "resourceTemplates" : "resources");
    boolean ui = r.env().supportsUi();
    if (!templates && !r.snapshot().blocked() && ui) {
      items.add(McpAppResource.descriptor());
      items.add(ConnectionImportCards.descriptor());
    }
    Set<String> listed = new LinkedHashSet<>();
    for (Offer o : offers(r.snapshot())) {
      if ((o.template() != null) != templates || decide(o.pair().view(), o.pair().config()).outcome()
          != Decision.Outcome.ALLOW) {
        continue;
      }
      ObjectNode n = items.addObject();
      n.put(templates ? "uriTemplate" : "uri", o.descriptor().uri());
      n.put("name", o.descriptor().name() == null ? o.descriptor().uri() : o.descriptor().name());
      if (o.descriptor().mimeType() != null) {
        n.put("mimeType", o.descriptor().mimeType());
      }
      if (o.descriptor().description() != null) {
        n.put("description", o.descriptor().description());
      }
      listed.add(o.descriptor().uri());
    }
    if (!templates && ui) {
      for (App a : apps(r.snapshot())) {
        if (listed.add(a.uri()) && decide(a.view(), a.pair().config()).outcome() == Decision.Outcome.ALLOW) {
          items.addObject().put("uri", a.uri()).put("name", a.pair().tool().title() + " · app")
              .put("description", "The MCP App of " + a.pair().tool().name() + " on " + a.pair().connectionName()
                  + ".").put("mimeType", ResourceDescriptor.APP_MIME_TYPE);
        }
      }
    }
    String field = templates ? "resourceTemplates" : "resources";
    String key = templates ? "uriTemplate" : "uri";
    List<Sharing.Shared> sharing = Sharing.of(r.snapshot(), registry, Sharing.What.RESOURCES).stream()
        .filter(s -> decide(s.view(), s.config()).outcome() == Decision.Outcome.ALLOW).toList();
    for (JsonNode answer : Sharing.each(sharing, s -> listOf(r, s, templates))) {
      Sharing.Shared s = sharing.stream().filter(x -> x.name().equals(answer.path("connection").asString()))
          .findFirst().orElse(null);
      if (s == null) {
        continue;
      }
      for (JsonNode item : answer.path("list").path(field)) {
        String uri = item.path(key).asString("");
        // A connection lists only what is under its own namespace.
        if (item.isObject() && (uri.startsWith(ResourceDescriptor.prefix(s.name()))
            || uri.startsWith(ResourceDescriptor.appPrefix(s.name()))) && listed.add(uri)) {
          items.add(item.deepCopy());
        }
      }
    }
    return out;
  }

  /** The list of one sharing connection, masked, with its name: {@code {"connection", "list"}}; {@code null}: none. */
  private JsonNode listOf(McpRequest r, Sharing.Shared s, boolean templates) {
    ConnectorInvoker.Outcome o = invoker.query(request(r, s.config(), s.view(), Ids.next("tc_"), TOOL, null,
        engine.maskPolicy(s.view(), s.config())), (c, cfg, secrets, ctx) -> {
          JsonNode list = c.listResources(cfg, secrets, ctx, templates);
          return list == null ? null : ToolResult.json(list, null);
        });
    if (!o.ok() || o.result().structuredContent() == null) {
      return null;
    }
    ObjectNode answer = JsonNodeFactory.instance.objectNode();
    answer.put("connection", s.name());
    answer.set("list", o.result().structuredContent());
    return answer;
  }

  public ObjectNode read(McpRequest r) {
    String uri = r.env().params().path("uri").asString(null);
    if (uri == null || uri.isEmpty()) {
      throw McpException.invalidParams("params.uri is required");
    }
    if (McpAppResource.URI.equals(uri) && !r.snapshot().blocked()) return McpAppResource.read();
    if (ConnectionImportCards.URI.equals(uri) && !r.snapshot().blocked()) return ConnectionImportCards.read();
    Optional<App> app = apps(r.snapshot()).stream().filter(a -> a.uri().equals(uri)).findFirst();
    if (app.isPresent()) {
      return readThrough(r, uri, app.get().pair().config(), app.get().view(), APP_MASK,
          ResourceDescriptor.APP_MIME_TYPE);
    }
    Optional<Sharing.Shared> shared = Sharing.forUri(r.snapshot(), registry, uri);
    if (shared.isPresent() && !uri.startsWith(ResourceDescriptor.appPrefix(shared.get().name()))) {
      Sharing.Shared s = shared.get();
      return readThrough(r, uri, s.config(), s.view(), engine.maskPolicy(s.view(), s.config()), "text/plain");
    }
    Optional<Offer> offer = offers(r.snapshot()).stream().filter(o -> o.template() == null
        ? o.descriptor().uri().equals(uri) : o.template().matcher(uri).matches()).findFirst();
    if (offer.isEmpty()) {
      throw notFound(r, uri);
    }
    EndpointSnapshot.Pair pair = offer.get().pair();
    String mime = offer.get().descriptor().mimeType() == null ? "text/plain" : offer.get().descriptor().mimeType();
    return readThrough(r, uri, pair.config(), pair.view(), engine.maskPolicy(pair.view(), pair.config()), mime);
  }

  private static McpException notFound(McpRequest r, String uri) {
    return new McpException(r.env().era() == Era.MODERN ? JsonRpc.INVALID_PARAMS : JsonRpc.RESOURCE_NOT_FOUND,
        "Resource not found: " + uri, JsonNodeFactory.instance.objectNode().put("uri", uri));
  }

  /**
   * One read of a connection's resource, the way of a READ tool call: the user's pause, the token's rate, the
   * policy's decision, audit {@code received}, the invoker, audit {@code completed}, the answer.
   *
   * @param mime what a text without a type of its own is ({@code text/plain}, the descriptor's)
   */
  private ObjectNode readThrough(McpRequest r, String uri, ConnectionConfig cfg, PairView view, MaskPolicy mask,
      String mime) {
    String disabled = r.snapshot().disabledReason(cfg.name());
    if (disabled != null) throw new McpException(JsonRpc.INVALID_PARAMS, disabled);
    PolicyEngine.Rates rates = engine.rates(view, cfg);
    ToolGuard.Admission admission = guard.admit(r.caller().principalId(), TOOL, rates.perToken(), rates.perTool());
    if (!admission.allowed()) {
      throw new McpException(JsonRpc.INVALID_PARAMS, "Too many reads on this token. Retry after "
          + Math.max(1, admission.retryAfterMs() / 1000) + " s.");
    }
    Decision decision = decide(view, cfg);
    String callId = Ids.next("tc_");
    ObjectNode args = JsonNodeFactory.instance.objectNode().put("uri", uri);
    ToolDefinition read = ToolDefinition.builder("resources_read").build();
    AuditWriter.Call call = calls.call(r, callId, TOOL, "resource:" + uri, cfg.name(), cfg.env(), read, args, null);
    if (decision.outcome() != Decision.Outcome.ALLOW) {
      // Only a policy that didn't verify or the prod line refuses a READ of a Read only pair: say which rule.
      received(call, AuditWriter.Received.of(AuditWriter.Verdict.DENIED, Classification.Kind.READ,
          decision.ruleId()));
      r.span().error("denied");
      throw new McpException(JsonRpc.INVALID_PARAMS, "Denied by policy " + decision.ruleId() + ": "
          + decision.reason(), JsonNodeFactory.instance.objectNode().put("uri", uri)
          .put("rule", decision.ruleId()));
    }
    received(call, AuditWriter.Received.of(AuditWriter.Verdict.ALLOWED, Classification.Kind.READ, null));
    long started = System.nanoTime();
    ConnectorInvoker.Outcome o = invoker.readResource(new ConnectorInvoker.Request(callId, cfg,
        new ToolCall(callId, TOOL, cfg.name(), args, null), decision.limits(), mask, r.span().traceId(),
        r.span().traceparent(), () -> snapshots.requireAvailable(r.snapshot().id(), cfg.name())), uri);
    long durationMs = (System.nanoTime() - started) / 1_000_000;
    try {
      audit.completed(call, new AuditWriter.Completion(o.ok() ? AuditWriter.Verdict.ALLOWED
          : AuditWriter.Verdict.ERROR, null, null, null, null, durationMs, Math.max(0, durationMs - o.resourceMs()),
          o.resourceMs(), null, o.resultBytes(), o.resultSha256(), o.preview(), o.maskedCount(), o.truncated(),
          o.errorKind() == null ? null : o.errorKind().name(), o.errorMessage(), null, null, null));
    } catch (AuditUnavailableException e) {
      throw new McpException(JsonRpc.INTERNAL_ERROR, "The audit couldn't record the read, so it is not shown.");
    }
    if (!o.ok()) {
      throw new McpException(JsonRpc.INTERNAL_ERROR, o.errorMessage());
    }
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    ArrayNode contents = out.putArray("contents");
    for (ToolResult.Content c : o.result().content()) {
      if ("resource".equals(c.type())) {
        ObjectNode item = contents.addObject().put("uri", c.uri() == null ? uri : c.uri());
        if (c.mimeType() != null) {
          item.put("mimeType", c.mimeType());
        }
        if (c.data() != null) {
          item.put("blob", c.data());
        } else {
          item.put("text", c.text() == null ? "" : c.text());
        }
        if (c.meta() != null && c.meta().isObject()) {
          item.set("_meta", c.meta());
        }
      } else if (c.text() != null) {
        contents.addObject().put("uri", uri).put("mimeType", mime).put("text", c.text());
      }
    }
    if (contents.isEmpty() && o.result().structuredContent() != null) {
      contents.addObject().put("uri", uri).put("mimeType", "application/json")
          .put("text", CanonicalJson.text(o.result().structuredContent()));
    }
    return out;
  }

  /** A request of the invoker for a list or a read of {@code cfg} through {@code view}. */
  private ConnectorInvoker.Request request(McpRequest r, ConnectionConfig cfg, PairView view, String callId,
      String tool, ObjectNode args, MaskPolicy mask) {
    Decision decision = decide(view, cfg);
    return new ConnectorInvoker.Request(callId, cfg, new ToolCall(callId, tool, cfg.name(),
        args == null ? JsonNodeFactory.instance.objectNode() : args, null), decision.limits(), mask,
        r.span().traceId(), r.span().traceparent(), () -> snapshots.requireAvailable(r.snapshot().id(), cfg.name()));
  }

  /**
   * Whether a client of {@code s} may hear updates of {@code uri}: a resource of a connection that shares its own, the
   * policy letting the endpoint read it. An app, a built-in resource or anyone else's URI has none.
   */
  public boolean subscribable(EndpointSnapshot s, String uri) {
    Optional<Sharing.Shared> shared = Sharing.forUri(s, registry, uri);
    return shared.isPresent() && uri.startsWith(ResourceDescriptor.prefix(shared.get().name()))
        && decide(shared.get().view(), shared.get().config()).outcome() == Decision.Outcome.ALLOW;
  }

  /**
   * The resources an endpoint offers, as a hash, without asking any server: what its connections declare and its apps,
   * as {@link #list} lists them, and which connections share their own (with their versions). A list a client keeps is
   * stale when this changes; what a sharing server lists changes when it says so ({@code ConnectionNotified}).
   */
  public String offerHash(EndpointSnapshot s) {
    StringBuilder out = new StringBuilder(Boolean.toString(s.blocked()));
    for (Offer o : offers(s)) {
      if (decide(o.pair().view(), o.pair().config()).outcome() == Decision.Outcome.ALLOW) {
        ResourceDescriptor d = o.descriptor();
        out.append("\n").append(d.uri()).append('|').append(d.name()).append('|').append(d.mimeType()).append('|')
            .append(d.description());
      }
    }
    for (App a : apps(s)) {
      if (decide(a.view(), a.pair().config()).outcome() == Decision.Outcome.ALLOW) {
        out.append("\napp ").append(a.uri()).append('|').append(a.pair().tool().title());
      }
    }
    for (Sharing.Shared x : Sharing.of(s, registry, Sharing.What.RESOURCES)) {
      if (decide(x.view(), x.config()).outcome() == Decision.Outcome.ALLOW) {
        out.append("\nshared ").append(x.name()).append(':').append(x.config().version());
      }
    }
    return ai.msrv.gaterook.core.platform.CanonicalJson.sha256Hex(JsonNodeFactory.instance.stringNode(
        out.toString()));
  }

  /** A resource read is a READ of its pair: the same policy gates as a tool call of it. */
  private Decision decide(PairView view, ConnectionConfig cfg) {
    return engine.decide(view, cfg, Classification.read());
  }

  private void received(AuditWriter.Call call, AuditWriter.Received received) {
    try {
      audit.received(call, received);
    } catch (AuditUnavailableException e) {
      throw new McpException(JsonRpc.INTERNAL_ERROR, "The audit is unavailable, so nothing ran. Try again later.");
    }
  }

  /**
   * The apps the endpoint's tools name ({@code _meta.ui.resourceUri} of an MCP server's tool, under its connection's
   * app namespace), each with the pair of a tool that is on. An endpoint whose rows didn't verify has none.
   */
  private static List<App> apps(EndpointSnapshot s) {
    List<App> out = new ArrayList<>();
    if (s.blocked()) {
      return out;
    }
    for (EndpointSnapshot.Pair p : s.onPairs()) {
      // The same apps whose connections' tools an app may call (ToolCatalog.appConnections).
      String uri = ToolCatalog.appUri(p);
      Optional<EndpointSnapshot.Wired> wired = s.connection(p.connectionName());
      if (uri != null && wired.isPresent() && wired.get().usable()
          && out.stream().noneMatch(a -> a.uri().equals(uri))) {
        out.add(new App(uri, p, Sharing.appView(s, wired.get(), p.tool().name())));
      }
    }
    return out;
  }

  /**
   * The resources the endpoint's connections declare, each with the Read only pair it reads through. An endpoint
   * whose rows didn't verify offers none: its wiring and pair modes are what someone wrote around the gateway.
   */
  private List<Offer> offers(EndpointSnapshot s) {
    List<Offer> out = new ArrayList<>();
    if (s.blocked()) {
      return out;
    }
    for (EndpointSnapshot.Wired w : s.connections()) {
      if (!w.usable()) {
        continue;
      }
      Optional<EndpointSnapshot.Pair> readOnly = s.pairs().stream()
          .filter(p -> p.config().id().equals(w.config().id()) && (p.mode() == Mode.READ_ONLY
              || (p.mode() == Mode.BYPASS && (p.bypassUntil() == null || p.storedMode() == Mode.READ_ONLY))))
          .findFirst();
      if (readOnly.isEmpty()) {
        continue;
      }
      Optional<Connector> connector = registry.connectorFor(w.config());
      if (connector.isEmpty()) {
        continue;
      }
      List<ResourceDescriptor> descriptors;
      try {
        descriptors = connector.get().resources(w.config());
      } catch (RuntimeException e) {
        continue;
      }
      for (ResourceDescriptor d : descriptors) {
        if (d.uri() == null || !d.uri().startsWith(ResourceDescriptor.prefix(w.config().name()))) {
          // A connector names only its own connection's resources.
          continue;
        }
        Pattern template = d.uri().contains("{") ? Pattern.compile(templateRegex(d.uri())) : null;
        out.add(new Offer(d, readOnly.get(), template));
      }
    }
    return out;
  }

  private static String templateRegex(String uriTemplate) {
    StringBuilder regex = new StringBuilder();
    var m = PLACEHOLDER.matcher(uriTemplate);
    int last = 0;
    while (m.find()) {
      regex.append(Pattern.quote(uriTemplate.substring(last, m.start()))).append("[^/{}?#]+");
      last = m.end();
    }
    regex.append(Pattern.quote(uriTemplate.substring(last)));
    return regex.toString();
  }
}
