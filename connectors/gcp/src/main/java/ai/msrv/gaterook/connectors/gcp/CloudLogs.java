package ai.msrv.gaterook.connectors.gcp;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.logs.LogsConnector;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** The existing log tools, scoped to the same resources and identity as a GCP connection. */
final class CloudLogs implements AutoCloseable {

  private final LogsConnector logs;

  CloudLogs(CloudCredentials cloud, Clock clock) {
    logs = new LogsConnector(cloud, clock);
  }

  static boolean isTool(String name) {
    return "logs_search".equals(name) || "logs_tail".equals(name);
  }

  List<ToolDefinition> tools() {
    return logs.manifest().tools().stream().filter(t -> isTool(t.name())).toList();
  }

  Classification classify(ToolCall call, ConnectionConfig cfg, Settings settings, GcpPolicy policy,
      PolicyView view) {
    try {
      checkPolicy(policy);
    } catch (Refusal refused) {
      return Classification.deny(view.rule(refused.rule), refused.getMessage(), refused.hint);
    }
    ObjectNode section = JsonNodeFactory.instance.objectNode().put("defaultLimit", policy.maxItems());
    return logs.classify(call, configuration(cfg, settings), new PolicyView(view.env(), view.rulePrefix(), section,
        limits(view.limits(), policy)));
  }

  ActionPreview describeAction(ToolCall call, ConnectionConfig cfg, Settings settings) {
    return logs.describeAction(call, configuration(cfg, settings));
  }

  ToolResult execute(ToolCall call, ConnectionConfig cfg, Settings settings, GcpPolicy policy,
      SecretResolver secrets, ExecContext context) throws ConnectorException {
    try {
      checkPolicy(policy);
    } catch (Refusal refused) {
      throw ConnectorException.denied(refused.forAgent());
    }
    return logs.execute(call, configuration(cfg, settings), secrets, new LimitedContext(context,
        limits(context.limits(), policy)));
  }

  void changed(String connectionId) {
    logs.onConnectionChanged(connectionId);
  }

  @Override
  public void close() {
    logs.close();
  }

  /** Cloud Asset resource masks cannot safely select the monitored resources or payloads of arbitrary log entries. */
  private static void checkPolicy(GcpPolicy policy) throws Refusal {
    if (policy.servicesRestricted() || !policy.regions().isEmpty() || !policy.resources().isEmpty()) {
      throw new Refusal("log_scope", "The Cloud policy limits services, locations or resources. GateRook cannot "
          + "apply those resource restrictions safely to Cloud Logging entries.",
          "Use a dedicated GCP Cloud Logging connection with an explicit scope filter for the permitted logs.");
    }
  }

  private static ConnectionConfig configuration(ConnectionConfig cfg, Settings settings) {
    ObjectNode config = JsonNodeFactory.instance.objectNode().put("backend", "gcp-logging");
    settings.scopes().forEach(config.putArray("resourceNames")::add);
    config.set("credentials", cfg.config().path("credentials").deepCopy());
    return new ConnectionConfig(cfg.id(), cfg.name(), LogsConnector.TYPE, cfg.env(), cfg.version(), config,
        cfg.secretFields(), cfg.metadata());
  }

  private static Limits limits(Limits original, GcpPolicy policy) {
    Duration range = Duration.ofDays(policy.maxRangeDays());
    if (original.maxRange().compareTo(range) < 0) range = original.maxRange();
    return new Limits(original.maxRows(), Math.min(original.maxLines(), policy.maxItems()),
        original.maxResponseBytes(), original.maxRequestBytes(), original.maxOutputBytes(), original.maxStderrBytes(),
        original.maxCellChars(), original.statementTimeout(), original.commandTimeout(), original.toolTimeout(), range);
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
