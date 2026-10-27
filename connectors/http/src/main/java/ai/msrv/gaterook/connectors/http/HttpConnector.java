package ai.msrv.gaterook.connectors.http;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.PreparedAction;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.TemplateKind;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.support.BuiltIn;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import ai.msrv.gaterook.connectors.support.TemplateImporter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The HTTP API connector (report 10): {@code http_get} (GET and HEAD, Read only; Off on prod by default) and
 * {@code http_request} (any method; POST, PUT, PATCH and DELETE always wait for a person, who approves the exact
 * request), plus the {@code openapi} template kind — one tool per operation of an OpenAPI 3 file.
 *
 * <p>Every request goes through the core's egress guard ({@code ctx.egress()}: the environment's address policy,
 * metadata and unapproved private addresses refused at connect time, no proxy, no automatic redirects or retries)
 * and must lie under a base URL of the connection <em>and</em> of the environment's allowlist. The approver sees the
 * request line, the headers, the credentials as {@code ‹masked›} and the body byte for byte; after Approve the stored
 * canonical request is sent, not a new build from the arguments.
 */
public final class HttpConnector implements Connector {

  public static final String TYPE = "http";

  private final ConnectorManifest manifest;
  private final OpenApiTemplateKind openApi;
  /** Calls running per connection version: {@code advanced.maxConcurrentCalls} (prod 2 by default). */
  private final Map<String, Semaphore> slots = new ConcurrentHashMap<>();

  public HttpConnector() {
    SchemaResources schemas = SchemaResources.of(HttpConnector.class, "http");
    this.manifest = ConnectorManifest.builder(TYPE, BuiltIn.VERSION, ConnectorManifest.Kind.BUILT_IN)
        .name("HTTP API")
        .icon("globe")
        .group("HTTP and MCP")
        .description("One base URL with an allowlist. GET reads; every other method waits for approval.")
        .configSchema(schemas.load("config"))
        .policySchema(schemas.load("policy"))
        .tools(HttpTools.definitions(schemas))
        .build();
    this.openApi = new OpenApiTemplateKind(schemas.load("openapi.definition"));
  }

  @Override
  public ConnectorManifest manifest() {
    return manifest;
  }

  @Override
  public List<TemplateKind> templateKinds() {
    return List.of(openApi);
  }

  /** The OpenAPI import of {@code POST /api/templates/openapi/parse}. */
  public TemplateImporter openApiImporter() {
    return openApi;
  }

  /** The bulkhead of the core is per connector; each connection's own limit is {@link #slots}. */
  @Override
  public int maxConcurrency() {
    return 16;
  }

  @Override
  public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
    HttpSettings s;
    try {
      s = HttpSettings.of(cfg);
    } catch (ConnectorException e) {
      return Classification.deny(policy.rule("invalid-config"), e.getMessage(), "A person fixes the connection "
          + cfg.name() + " in Connections.");
    }
    PlannedRequest r;
    boolean read;
    try {
      r = RequestPlanner.plan(call, cfg, s);
      RequestPlanner.checkConnection(r, s, cfg);
      read = declaredRead(call, r);
      RequestPlanner.checkPolicy(r, s, cfg, policy, read && !call.tool().equals(HttpTools.REQUEST));
    } catch (Refusal refusal) {
      return refusal.deny(policy);
    }
    ObjectNode facts = JsonNodeFactory.instance.objectNode();
    facts.put("method", r.method());
    facts.put("host", r.url().host());
    facts.put("path", r.relativePath());
    boolean mutatingGet = RequestPlanner.mutatingGet(r, s);
    if (mutatingGet && call.template() == null && call.tool().equals(HttpTools.GET)) {
      return Classification.deny(policy.rule("mutating-get"), r.method() + " " + r.url().encodedPath()
          + " changes state on " + cfg.name() + ".", "Use http_request; it asks first.");
    }
    if (mutatingGet) {
      return Classification.mutation(r.method() + " " + r.url().encodedPath() + " changes state on " + cfg.name(),
          facts);
    }
    if (!read) {
      return Classification.mutation(r.method() + " changes data on " + cfg.name(), facts);
    }
    return new Classification(Classification.Kind.READ, null, null, null, facts);
  }

  /**
   * Whether the call is a read by what it says: GET or HEAD of {@code http_get} and {@code http_request}, the nature
   * of a template operation (a person may mark a GET as changing state, or a POST that searches as a read).
   */
  private static boolean declaredRead(ToolCall call, PlannedRequest r) {
    if (call.template() != null) {
      return "read".equals(call.template().binding().path("nature").asString(""));
    }
    return r.readMethod();
  }

  @Override
  public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
    try {
      HttpSettings s = HttpSettings.of(cfg);
      PlannedRequest r = RequestPlanner.plan(call, cfg, s);
      RequestPlanner.checkConnection(r, s, cfg);
      return ActionText.of(r, s, cfg, null);
    } catch (ConnectorException e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    } catch (Refusal e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }
  }

  /**
   * The exact request, no I/O: the credentials it needs must be set (a request no one can send is not worth a
   * person's approval), and the tool timeout is part of what the approver sees.
   */
  @Override
  public PreparedAction prepare(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    HttpSettings s = HttpSettings.of(cfg);
    PlannedRequest r;
    try {
      r = RequestPlanner.plan(call, cfg, s);
      RequestPlanner.checkConnection(r, s, cfg);
    } catch (Refusal e) {
      return PreparedAction.reject(e.getMessage());
    }
    if (s.auth().field() != null && !secrets.isSet(s.auth().field())) {
      return PreparedAction.reject(cfg.name() + " has no " + switch (s.auth().type()) {
        case BEARER -> "token";
        case BASIC -> "password";
        default -> "API key";
      } + " set, so the request can't be sent. A person sets it under Credentials. Nothing was asked.");
    }
    for (HttpSettings.SecretHeader h : s.secretHeaders()) {
      if (h.appliesTo(r.method()) && !secrets.isSet(h.field())) {
        return PreparedAction.reject(cfg.name() + " has no value for " + h.name() + ", so the request can't be sent. "
            + "A person sets it under More secret headers. Nothing was asked.");
      }
    }
    return PreparedAction.approval(ActionText.of(r, s, cfg, ctx.limits().toolTimeout()), null);
  }

  @Override
  public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    HttpSettings s = HttpSettings.of(cfg);
    PlannedRequest r;
    boolean follow;
    try {
      if (ctx.approved()) {
        JsonNode payload = ctx.approvedPayload().orElseThrow(() -> ConnectorException.denied(
            "The approval holds no request. Not executed."));
        r = PlannedRequest.fromPayload(payload, s, operation(call));
        RequestPlanner.checkConnection(r, s, cfg);
        follow = false;
      } else {
        r = RequestPlanner.plan(call, cfg, s);
        RequestPlanner.checkConnection(r, s, cfg);
        // Defence in depth: a request that changes data runs only from an approval, whatever the caller did.
        if (!declaredRead(call, r) || RequestPlanner.mutatingGet(r, s)) {
          throw ConnectorException.denied(r.method() + " " + r.url().encodedPath() + " changes data on " + cfg.name()
              + ": it runs only after a person approves it.");
        }
        follow = !call.tool().equals(HttpTools.REQUEST);
      }
    } catch (Refusal e) {
      throw e.toException();
    }
    if (r.body() != null && r.body().length > ctx.limits().maxRequestBytes()) {
      throw ConnectorException.denied(Sizes.tooLarge(r.body().length, ctx.limits().maxRequestBytes(),
          cfg.env().id()) + ".");
    }
    Semaphore slot = slots.computeIfAbsent(cfg.id() + ":" + s.maxConcurrentCalls(),
        k -> new Semaphore(s.maxConcurrentCalls()));
    try {
      if (!slot.tryAcquire(Math.min(2_000, Math.max(1, ctx.remaining().toMillis())), TimeUnit.MILLISECONDS)) {
        throw new ConnectorException(ConnectorException.Kind.RESOURCE_ERROR, cfg.name() + " already has "
            + s.maxConcurrentCalls() + " calls running. Try again in a moment.", null);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ConnectorException.timeout("The call to " + cfg.name() + " was cancelled.");
    }
    try {
      ResponseReader.Answer answer = HttpExecutor.send(r, s, secrets, ctx, follow);
      PlannedRequest last = answer.url() == null ? r : new PlannedRequest(r.method(), answer.url(), r.base(),
          r.headers(), r.body(), r.contentType(), r.operation());
      return ResponseReader.read(r.method(), ActionText.shownUrl(last, s), answer, ctx.limits().maxResponseBytes());
    } finally {
      slot.release();
    }
  }

  /** The operation of a template call, for the approver's "Operation" line and the payload. */
  static PlannedRequest.Operation operation(ToolCall call) {
    if (call.template() == null) {
      return null;
    }
    JsonNode b = call.template().binding();
    String template = b.path("template").asString("template");
    String op = b.path("operationId").asString(call.template().opKey());
    String label = op + " · " + template + " (template v" + call.template().version() + ")";
    return new PlannedRequest.Operation(call.template().templateId(), call.template().version(),
        call.template().opKey(), label, template + "@v" + call.template().version());
  }

  @Override
  public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    return TestProbe.test(cfg, secrets, ctx);
  }

  @Override
  public HealthResult health(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    return TestProbe.health(cfg, secrets, ctx);
  }

  /** {@code describe_connection}: where requests may go and what needs approval; no I/O, no secret. */
  @Override
  public Optional<JsonNode> describe(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, String object)
      throws ConnectorException {
    HttpSettings s = HttpSettings.of(cfg);
    ObjectNode d = JsonNodeFactory.instance.objectNode();
    ArrayNode bases = d.putArray("baseUrls");
    s.baseUrls().forEach(b -> bases.add(b.toString()));
    ArrayNode paths = d.putArray("allowedPaths");
    s.allowedPaths().forEach(paths::add);
    ArrayNode mutating = d.putArray("mutatingGetPaths");
    s.mutatingGetPaths().forEach(mutating::add);
    d.put("auth", s.auth().type().id);
    d.put("followRedirects", s.followSameOrigin() ? "same-origin" : "off");
    d.put("note", "Paths are relative to the first base URL. GET and HEAD read; POST, PUT, PATCH and DELETE wait for "
        + "a person. The gateway adds the credentials itself.");
    return Optional.of(d);
  }

  @Override
  public void onConnectionChanged(String connectionId) {
    slots.keySet().removeIf(k -> k.startsWith(connectionId + ":"));
  }

  @Override
  public void onConnectionRemoved(String connectionId) {
    slots.keySet().removeIf(k -> k.startsWith(connectionId + ":"));
  }
}
