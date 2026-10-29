package ai.msrv.gaterook.connectors.logs;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.ConnectorManifest.RequiredAccess;
import ai.msrv.gaterook.connector.api.EndpointView;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.support.BuiltIn;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Logs connector (report 07): {@code logs_search} and {@code logs_tail} over Loki, GCP Cloud Logging, AWS
 * CloudWatch Logs and OpenSearch.
 *
 * <p>The log tools only read within the connection's scope. OpenSearch defaults to full API access with index
 * discovery: known reads use {@code opensearch_query}; all other REST calls default to Approval through
 * {@code opensearch_request}. The full API uses server permissions rather than the log query's index/filter scope.
 */
public final class LogsConnector implements Connector {

  public static final String TYPE = "logs";

  /** How long a decision of {@code classify} waits for {@code execute} of the same call. */
  private static final Duration DECISION_TTL = Duration.ofMinutes(5);
  private static final int MAX_DECISIONS = 4_096;

  private final ConnectorManifest manifest;
  private final CloudCredentials cloud;
  private final Clock clock;
  private final Clients clients = new Clients();
  /**
   * The Logs policy section only reaches {@code classify} ({@link PolicyView}); {@code execute} sees the call's
   * {@code Limits}. The section's default line count and longest tail wait travel from one to the other here, by call
   * id; without a decision (a test, an expired entry) the environment's defaults apply.
   */
  private final Map<String, Decided> decided = new ConcurrentHashMap<>();

  private record Decided(LogsRequest.PolicyLimits limits, long atNanos) {}

  public LogsConnector(CloudCredentials cloud) {
    this(cloud, Clock.systemUTC());
  }

  public LogsConnector(CloudCredentials cloud, Clock clock) {
    this.cloud = Objects.requireNonNull(cloud, "cloud");
    this.clock = Objects.requireNonNull(clock, "clock");
    SchemaResources schemas = SchemaResources.of(LogsConnector.class, "logs");
    this.manifest = ConnectorManifest.builder(TYPE, BuiltIn.VERSION, ConnectorManifest.Kind.BUILT_IN)
        .name("Logs")
        .icon("scroll-text")
        .group("Logs")
        .description("Search Loki, GCP Cloud Logging, AWS CloudWatch and OpenSearch logs. OpenSearch includes full "
            + "REST API access with administration in Approval mode by default.")
        .configSchema(schemas.load("config"))
        .policySchema(schemas.load("policy"))
        .tools(LogsTools.definitions(schemas))
        // Files this connector may read outside its own config ("Needs access to" on the catalog card).
        .access(new RequiredAccess(List.of(), List.of(), List.of(
            "~/.config/gcloud/application_default_credentials.json",
            "~/.aws/config",
            "~/.aws/credentials")))
        .build();
  }

  @Override
  public ConnectorManifest manifest() {
    return manifest;
  }

  @Override
  public List<ToolDefinition> tools(EndpointView endpoint) {
    return LogsTools.forEndpoint(manifest.tools(), endpoint);
  }

  @Override
  public List<ToolDefinition> previewTools(ConnectionConfig cfg) {
    return LogsTools.forBackend(manifest.tools(), cfg.config().path("backend").asString(""), true).stream()
        .filter(tool -> supportsTool(tool.name(), cfg)).toList();
  }

  @Override
  public boolean supportsTool(String tool, ConnectionConfig cfg) {
    if (!OpenSearchRequest.isTool(tool)) {
      return true;
    }
    try {
      return Settings.parse(cfg) instanceof Settings.OpenSearch s
          && tool.startsWith(s.backend() + "_") && (tool.equals(s.tool("indices")) || s.fullApi());
    } catch (Settings.InvalidSettingsException e) {
      return false;
    }
  }

  @Override
  public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
    Settings s;
    try {
      s = Settings.parse(cfg);
    } catch (Settings.InvalidSettingsException e) {
      return Classification.deny(policy.rule("config_invalid"), "The settings of " + cfg.name() + " can't be used: "
          + e.getMessage(), "Ask the person to fix the connection in GateRook.");
    }
    if (OpenSearchRequest.isTool(call.tool())) {
      try {
        Settings.OpenSearch os = openSearch(s);
        OpenSearchRequest request = OpenSearchRequest.plan(call, os, policy.limits());
        return call.tool().equals(os.tool("request"))
            ? Classification.mutation(s.title() + " REST request: " + request.method() + " "
                + request.path()) : Classification.read();
      } catch (ConnectorException e) {
        return Classification.deny(policy.rule(s.backend() + "_api"), e.getMessage(), null);
      }
    }
    LogsRequest.PolicyLimits limits = policyLimits(policy, cfg.env());
    try {
      LogsRequest.of(call, s, cfg.env(), policy.limits(), limits, clock.instant());
    } catch (LogsRequest.Refusal r) {
      return Classification.deny(policy.rule(r.rule), r.getMessage(), r.hint);
    }
    remember(call.callId(), limits);
    return Classification.read();
  }

  @Override
  public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
    return describeAction(call, cfg, call.tool());
  }

  ActionPreview describeAction(ToolCall call, ConnectionConfig cfg, String displayTool) {
    try {
      Settings s = Settings.parse(cfg);
      if (OpenSearchRequest.isTool(call.tool())) {
        Settings.OpenSearch os = openSearch(s);
        return OpenSearchRequest.plan(call, os, null).preview(os, cfg);
      }
      LogsRequest r = LogsRequest.of(call, s, cfg.env(), null, LogsRequest.PolicyLimits.defaults(cfg.env()),
          clock.instant().truncatedTo(ChronoUnit.SECONDS));
      return Actions.preview(r, s, cfg, displayTool);
    } catch (Settings.InvalidSettingsException | LogsRequest.Refusal | ConnectorException e) {
      // classify() denies such a call before any request exists; this preview only ever shows what was asked.
      return ActionPreview.of(ActionPreview.Kind.JSON, "json", call.arguments().toString(), cfg.name(),
          call.tool() + " on " + cfg.name());
    }
  }

  @Override
  public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    Settings s;
    try {
      s = Settings.parse(cfg);
    } catch (Settings.InvalidSettingsException e) {
      return TestResult.failure("logs.config", e.getMessage(), null);
    }
    return backend(s, cfg, secrets, ctx).test();
  }

  @Override
  public TestResult discoverProjects(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    if (!"gcp-logging".equals(cfg.config().path("backend").asString())) {
      return TestResult.failure("discovery-unavailable", "Project discovery requires Google Cloud Logging.", null);
    }
    return GcpProjects.discover(new Backend.Call(cfg, secrets, ctx, clients, cloud, ctx.limits().maxCellChars()));
  }

  @Override
  public HealthResult health(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    try {
      return backend(Settings.parse(cfg), cfg, secrets, ctx).health();
    } catch (Settings.InvalidSettingsException e) {
      return HealthResult.unreachable(e.getMessage());
    }
  }

  @Override
  public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    if (LogsRequest.Tool.of(call.tool()) == null && !OpenSearchRequest.isTool(call.tool())) {
      throw ConnectorException.badInput("The Logs connector has no tool " + call.tool() + ".");
    }
    Settings s;
    try {
      s = Settings.parse(cfg);
    } catch (Settings.InvalidSettingsException e) {
      throw ConnectorException.badInput("The settings of " + cfg.name() + " can't be used: " + e.getMessage());
    }
    if (OpenSearchRequest.isTool(call.tool())) {
      Settings.OpenSearch os = openSearch(s);
      OpenSearchRequest request;
      if (ctx.approved()) {
        JsonNode payload = ctx.approvedPayload().orElseThrow(() -> ConnectorException.denied(
            "The authorization contains no " + os.title() + " request. Nothing was executed."));
        request = OpenSearchRequest.approved(payload, call, os, ctx.limits());
      } else {
        request = OpenSearchRequest.plan(call, os, ctx.limits());
        if (call.tool().equals(os.tool("request"))) {
          throw ConnectorException.denied(os.tool("request") + " requires core authorization of its exact request "
              + "through Approval or explicit Bypass.");
        }
      }
      return ((OpenSearchBackend) backend(s, cfg, secrets, ctx)).execute(request);
    }
    Decided d = decided.remove(call.callId() == null ? "" : call.callId());
    LogsRequest.PolicyLimits limits = d != null ? d.limits() : LogsRequest.PolicyLimits.defaults(cfg.env());
    LogsRequest r;
    try {
      Optional<JsonNode> approved = ctx.approved() ? ctx.approvedPayload() : Optional.empty();
      r = approved.isPresent()
          ? LogsRequest.ofPayload(approved.get(), s, cfg.env(), ctx.limits(), limits, ctx.clock().instant())
          : LogsRequest.of(call, s, cfg.env(), ctx.limits(), limits, ctx.clock().instant());
    } catch (LogsRequest.Refusal e) {
      throw ConnectorException.badInput(e.getMessage() + (e.hint == null ? "" : " " + e.hint));
    }
    Backend backend = backend(s, cfg, secrets, ctx);
    return r.tool() == LogsRequest.Tool.SEARCH ? Search.run(backend, r, s, cfg, ctx) : Tail.run(backend, r, s, cfg,
        ctx);
  }

  @Override
  public Optional<JsonNode> describe(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, String object)
      throws ConnectorException {
    Settings s;
    try {
      s = Settings.parse(cfg);
    } catch (Settings.InvalidSettingsException e) {
      throw ConnectorException.badInput("The settings of " + cfg.name() + " can't be used: " + e.getMessage());
    }
    ObjectNode out = backend(s, cfg, secrets, ctx).describe(object);
    out.put("backend", s.backend());
    out.put("title", s.title());
    out.put("language", s instanceof Settings.OpenSearch os && os.fullApi() ? os.title() + " REST / JSON Query DSL"
        : s.language());
    out.put("example", s instanceof Settings.OpenSearch os && os.fullApi()
        ? "POST /products/_search with body {\"query\":{\"match_all\":{}}}" : s.example());
    if (!s.sources().isEmpty()) {
      ArrayNode sources = out.putArray("sources");
      s.sources().forEach(sources::add);
    }
    out.put("tailWaitsUpToSeconds", Math.min(LogsRequest.MAX_WAIT_SECONDS,
        Math.max(0, ctx.limits().toolTimeout().toSeconds() - 2)));
    return Optional.of(out);
  }

  @Override
  public void onConnectionChanged(String connectionId) {
    clients.evict(connectionId);
    cloud.evict(connectionId);
  }

  @Override
  public void onConnectionRemoved(String connectionId) {
    onConnectionChanged(connectionId);
  }

  @Override
  public void close() {
    clients.close();
  }

  private Backend backend(Settings s, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    return Backend.of(s, new Backend.Call(cfg, secrets, ctx, clients, cloud, Math.max(256, ctx.limits()
        .maxCellChars())));
  }

  private static Settings.OpenSearch openSearch(Settings settings) throws ConnectorException {
    if (settings instanceof Settings.OpenSearch os) {
      return os;
    }
    throw ConnectorException.badInput("This tool requires an OpenSearch or Elasticsearch connection.");
  }

  private static LogsRequest.PolicyLimits policyLimits(PolicyView policy, Env env) {
    LogsRequest.PolicyLimits d = LogsRequest.PolicyLimits.defaults(env);
    return new LogsRequest.PolicyLimits(Math.max(1, policy.integer("defaultLimit", d.defaultLimit())),
        Math.max(0, policy.integer("maxTailWaitSeconds", d.maxWaitSeconds())));
  }

  private void remember(String callId, LogsRequest.PolicyLimits limits) {
    if (callId == null) {
      return;
    }
    long now = System.nanoTime();
    if (decided.size() >= MAX_DECISIONS) {
      decided.values().removeIf(d -> now - d.atNanos() > DECISION_TTL.toNanos());
      if (decided.size() >= MAX_DECISIONS) {
        // Calls classified but never run (denied by the policy later): start over; their execute gets defaults.
        decided.clear();
      }
    }
    decided.put(callId, new Decided(limits, now));
  }
}
