package com.gaterook.connectors.logs;

import com.gaterook.connector.api.ActionPreview;
import com.gaterook.connector.api.Classification;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Connector;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ConnectorManifest;
import com.gaterook.connector.api.ConnectorManifest.RequiredAccess;
import com.gaterook.connector.api.EndpointView;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connector.api.HealthResult;
import com.gaterook.connector.api.PolicyView;
import com.gaterook.connector.api.SecretResolver;
import com.gaterook.connector.api.Suggestions;
import com.gaterook.connector.api.TestResult;
import com.gaterook.connector.api.ToolCall;
import com.gaterook.connector.api.ToolDefinition;
import com.gaterook.connector.api.ToolResult;
import com.gaterook.connectors.cloudauth.CloudCredentials;
import com.gaterook.connectors.cloudauth.CloudLocal;
import com.gaterook.connectors.support.BuiltIn;
import com.gaterook.connectors.support.SchemaResources;
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
  private final CloudLocal local;
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
    this(cloud, clock, new CloudLocal(Objects.requireNonNull(cloud, "cloud").endpoints()));
  }

  /** For tests: what the cloud CLIs of another home folder and environment know ({@link CloudLocal}). */
  LogsConnector(CloudCredentials cloud, Clock clock, CloudLocal local) {
    this.cloud = Objects.requireNonNull(cloud, "cloud");
    this.local = Objects.requireNonNull(local, "local");
    this.clock = Objects.requireNonNull(clock, "clock");
    SchemaResources schemas = SchemaResources.of(LogsConnector.class, "logs");
    this.manifest = ConnectorManifest.builder(TYPE, BuiltIn.VERSION, ConnectorManifest.Kind.BUILT_IN)
        .name("Logs")
        .icon("scroll-text")
        .group("Logs")
        .description("Search Loki, GCP Cloud Logging, AWS CloudWatch, OpenSearch and Azure Monitor logs. OpenSearch "
            + "includes full REST API access with administration in Approval mode by default.")
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
    // Availability is checked before store-backed text fields (such as a Basic username) are resolved. Parsing
    // runtime settings here would hide valid tools and let endpoint reconciliation remove their saved modes.
    JsonNode config = cfg.config();
    if (config == null || !config.isObject()) return false;
    String backend = config.path("backend").asString("").strip();
    String access = config.path("apiAccess").asString("full").strip();
    if (access.isEmpty()) access = "full";
    if (!List.of("opensearch", "elasticsearch").contains(backend)
        || !List.of("logs", "full").contains(access)
        || (backend.equals("elasticsearch") && (!cfg.type().equals(backend) || !access.equals("full")))) {
      return false;
    }
    return tool.startsWith(backend + "_") && (tool.equals(backend + "_indices") || access.equals("full"));
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

  /**
   * Setup helpers for every backend ({@link LogsSuggestions}): local files and a server on this computer while the
   * form is typed, and once it signs in what the account may read (log groups, indices, log views, workspaces).
   */
  @Override
  public Suggestions suggest(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, Suggestions.Scope scope) {
    return LogsSuggestions.suggest(cfg, secrets, ctx, scope, cloud, local);
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
