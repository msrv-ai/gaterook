package com.gaterook.connectors.azure;

import com.gaterook.connector.api.ActionPreview;
import com.gaterook.connector.api.Classification;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connector.api.Limits;
import com.gaterook.connector.api.PolicyView;
import com.gaterook.connector.api.SecretResolver;
import com.gaterook.connector.api.ToolCall;
import com.gaterook.connector.api.ToolDefinition;
import com.gaterook.connector.api.ToolResult;
import com.gaterook.connectors.cloudauth.CloudCredentials;
import com.gaterook.connectors.logs.LogsConnector;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The shared log tools over the Log Analytics workspaces and Application Insights components of an Azure
 * connection, with its identity: the Logs connector's Azure Monitor backend (KQL, cross-resource functions refused).
 */
final class AzureLogs implements AutoCloseable {

  private final LogsConnector logs;

  AzureLogs(CloudCredentials cloud, Clock clock) {
    logs = new LogsConnector(cloud, clock);
  }

  static boolean isTool(String name) {
    return "logs_search".equals(name) || "logs_tail".equals(name);
  }

  List<ToolDefinition> tools() {
    return logs.manifest().tools().stream().filter(t -> isTool(t.name())).toList();
  }

  List<ToolDefinition> previewTools(ConnectionConfig cfg) {
    ConnectionConfig preview = new ConnectionConfig(cfg.id(), cfg.name(), cfg.type(), cfg.env(), cfg.version(),
        JsonNodeFactory.instance.objectNode().put("backend", "azure-monitor"), cfg.secretFields(), cfg.metadata());
    return logs.previewTools(preview);
  }

  Classification classify(ToolCall call, ConnectionConfig cfg, Settings settings, AzurePolicy policy,
      PolicyView view) {
    if (!settings.hasLogs()) {
      return Classification.deny(view.rule("no_log_sources"), cfg.name() + " names no Log Analytics workspace or "
          + "Application Insights component.", "Ask the person to add one to the connection.");
    }
    if (policy.restricted()) {
      return Classification.deny(view.rule("log_scope"), "The Cloud policy limits services, locations or resources. "
          + "GateRook can't apply those restrictions safely to log entries.", "Use a dedicated Azure Monitor Logs "
          + "connection whose workspaces hold only the permitted logs.");
    }
    ObjectNode section = JsonNodeFactory.instance.objectNode().put("defaultLimit", policy.maxItems());
    return logs.classify(call, configuration(cfg, settings), new PolicyView(view.env(), view.rulePrefix(), section,
        limits(view.limits(), policy)));
  }

  ActionPreview describeAction(ToolCall call, ConnectionConfig cfg, Settings settings) {
    return logs.describeAction(call, configuration(cfg, settings));
  }

  ToolResult execute(ToolCall call, ConnectionConfig cfg, Settings settings, AzurePolicy policy,
      SecretResolver secrets, ExecContext context) throws ConnectorException {
    if (!settings.hasLogs() || policy.restricted()) {
      throw ConnectorException.denied("The log tools can't read through " + cfg.name() + " under this Cloud policy.");
    }
    return logs.execute(call, configuration(cfg, settings), secrets, new LimitedContext(context,
        limits(context.limits(), policy)));
  }

  Optional<JsonNode> describe(ConnectionConfig cfg, Settings settings, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    return logs.describe(configuration(cfg, settings), secrets, ctx, null);
  }

  void changed(String connectionId) {
    logs.onConnectionChanged(connectionId);
  }

  @Override
  public void close() {
    logs.close();
  }

  /** The Logs connector's view of the connection: the same cloud, tenant, credentials and log sources. */
  static ConnectionConfig configuration(ConnectionConfig cfg, Settings s) {
    ObjectNode config = JsonNodeFactory.instance.objectNode().put("backend", "azure-monitor");
    JsonNode c = cfg.config();
    for (String field : List.of("cloud", "tenantId", "trustedCli")) {
      if (c.hasNonNull(field)) {
        config.set(field, c.get(field).deepCopy());
      }
    }
    config.set("credentials", c.path("credentials").deepCopy());
    s.workspaces().forEach(config.putArray("workspaces")::add);
    s.applicationInsights().forEach(config.putArray("applicationInsights")::add);
    return new ConnectionConfig(cfg.id(), cfg.name(), LogsConnector.TYPE, cfg.env(), cfg.version(), config,
        cfg.secretFields(), cfg.metadata());
  }

  private static Limits limits(Limits original, AzurePolicy policy) {
    return new Limits(original.maxRows(), Math.min(original.maxLines(), policy.maxItems()),
        original.maxResponseBytes(), original.maxRequestBytes(), original.maxOutputBytes(), original.maxStderrBytes(),
        original.maxCellChars(), original.statementTimeout(), original.commandTimeout(), original.toolTimeout(),
        original.maxRange());
  }

  /** Retains core authorization, cancellation, credential tainting and handles while narrowing the log limits. */
  private record LimitedContext(ExecContext delegate, Limits limits) implements ExecContext {
    @Override public String callId() { return delegate.callId(); }
    @Override public String traceId() { return delegate.traceId(); }
    @Override public String traceparent() { return delegate.traceparent(); }
    @Override public Env env() { return delegate.env(); }
    @Override public Duration timeout() { return delegate.timeout(); }
    @Override public Instant deadline() { return delegate.deadline(); }
    @Override public boolean cancelled() { return delegate.cancelled(); }
    @Override public void onCancel(Runnable action) { delegate.onCancel(action); }
    @Override public boolean approved() { return delegate.approved(); }
    @Override public Optional<String> approvalId() { return delegate.approvalId(); }
    @Override public Optional<JsonNode> preparedState() { return delegate.preparedState(); }
    @Override public Optional<JsonNode> approvedPayload() { return delegate.approvedPayload(); }
    @Override public System.Logger log() { return delegate.log(); }
    @Override public Clock clock() { return delegate.clock(); }
    @Override public HandleCodec handles() { return delegate.handles(); }
    @Override public boolean acquire(String key, int permits) { return delegate.acquire(key, permits); }
    @Override public Path workDir() { return delegate.workDir(); }
    @Override public Egress egress() { return delegate.egress(); }
    @Override public void taint(CharSequence value) { delegate.taint(value); }
    @Override public JsonNode masked(JsonNode tree) { return delegate.masked(tree); }
    @Override public Optional<String> textMaskMarker() { return delegate.textMaskMarker(); }
  }
}
