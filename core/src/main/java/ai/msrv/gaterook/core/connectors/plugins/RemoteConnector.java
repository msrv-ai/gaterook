package ai.msrv.gaterook.core.connectors.plugins;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.support.egress.EgressClients;
import ai.msrv.gaterook.connectors.support.OutputSanitizer;
import ai.msrv.gaterook.core.connectors.EnvironmentPolicies;
import ai.msrv.gaterook.core.platform.CanonicalJson;
import ai.msrv.gaterook.plugin.protocol.Gpp;
import ai.msrv.gaterook.plugin.protocol.GppJson;
import ai.msrv.gaterook.plugin.protocol.JsonRpcPeer;
import ai.msrv.gaterook.plugin.protocol.Messages;
import ai.msrv.gaterook.plugin.protocol.RpcException;
import ai.msrv.gaterook.plugin.protocol.WireCodec;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * A plugin seen as an ordinary {@link Connector} (report 16 §4, §9): policy, approvals and audit treat it like a
 * built-in, while every call crosses GPP/1 to the plugin host. {@code classify} answers from the stored manifest's
 * nature without a round trip (only a {@code MIXED} tool asks the plugin, and an answer that isn't a classification
 * is a DENY); {@code describeAction} is a round trip <em>without secrets</em>, whose text is only the secondary block
 * of the exact action — the main block is the core's ({@link #exactAction}); {@code test} and {@code execute} carry a
 * {@code callToken} the host asks secrets with.
 *
 * <p>The manifest comes from the database, so the registry and {@code tools/list} never start a host. A timeout on an
 * approved change is {@code OUTCOME_UNKNOWN} — the core never resends it; a stuck host is replaced by the supervisor.
 */
final class RemoteConnector implements Connector {

  private static final Logger log = LoggerFactory.getLogger(RemoteConnector.class);
  private static final Duration CLASSIFY_TIMEOUT = Duration.ofSeconds(2);
  private static final Duration DESCRIBE_TIMEOUT = Duration.ofSeconds(5);

  private final String pluginId;
  private final ConnectorManifest manifest;
  private final PluginSupervisor supervisor;
  private final SecretCallbackRegistry secrets;
  private final EnvironmentPolicies policies;
  private final EgressClients egress;
  private final boolean signed;

  RemoteConnector(ConnectorManifest manifest, PluginSupervisor supervisor, SecretCallbackRegistry secrets,
      EnvironmentPolicies policies, EgressClients egress, boolean signed) {
    this.pluginId = manifest.id();
    this.manifest = manifest;
    this.supervisor = supervisor;
    this.secrets = secrets;
    this.policies = policies;
    this.egress = egress;
    this.signed = signed;
  }

  private String generation() {
    return supervisor.spec() == null ? pluginId + "@" + manifest.version() : supervisor.spec().generation();
  }

  @Override
  public ConnectorManifest manifest() {
    return manifest;
  }

  @Override
  public int maxConcurrency() {
    return PluginSupervisor.BULKHEAD;
  }

  @Override
  public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
    Optional<ToolDefinition> tool = tool(call.tool());
    if (tool.isEmpty()) {
      return Classification.deny(policy.rule("unknown-tool"), call.tool() + " isn't a tool of " + manifest.name()
          + " " + manifest.version() + ".", null);
    }
    ToolDefinition.Nature nature = tool.get().nature();
    if (nature == ToolDefinition.Nature.READ) {
      return Classification.read();
    }
    if (nature == ToolDefinition.Nature.MUTATION) {
      return Classification.mutation(call.tool() + " changes data in " + cfg.name() + ".");
    }
    // MIXED: the plugin decides per call (no secrets); a timeout is a safe DENY.
    try (PluginSupervisor.Permit permit = supervisor.permit(deadline(CLASSIFY_TIMEOUT))) {
      Messages.Classify params = new Messages.Classify(generation(), call, cfg, policy);
      JsonNode result = permit.host().request(Gpp.CONNECTOR_CLASSIFY, params.write()).get(CLASSIFY_TIMEOUT);
      permit.returned();
      return WireCodec.classification(result);
    } catch (TimeoutException e) {
      return Classification.deny(policy.rule("plugin-classify-timeout"), manifest.name() + " didn't classify "
          + call.tool() + " in time.", null);
    } catch (RpcException | ConnectorException e) {
      return Classification.deny(policy.rule("plugin-classify-failed"), manifest.name() + " couldn't classify "
          + call.tool() + ": " + e.getMessage(), null);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Classification.deny(policy.rule("plugin-classify-failed"), "The call was cancelled.", null);
    } catch (RuntimeException e) {
      // An answer that isn't a classification (an unknown kind, no kind): refused, never guessed.
      return Classification.deny(policy.rule("plugin-classify-failed"), manifest.name() + " didn't give a valid "
          + "classification of " + call.tool() + ".", null);
    }
  }

  @Override
  public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
    ActionPreview described = null;
    try (PluginSupervisor.Permit permit = supervisor.permit(deadline(DESCRIBE_TIMEOUT))) {
      Messages.Describe params = new Messages.Describe(generation(), call, cfg);
      JsonNode result = permit.host().request(Gpp.CONNECTOR_DESCRIBE_ACTION, params.write()).get(DESCRIBE_TIMEOUT);
      permit.returned();
      described = WireCodec.action(result);
    } catch (TimeoutException | RpcException | ConnectorException | InterruptedException | RuntimeException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      log.debug("describeAction of {} by {} failed: {}", call.tool(), pluginId, e.toString());
    }
    return exactAction(call, cfg, described);
  }

  /**
   * The exact action of a plugin's call (ARCHITECTURE.md §3 "RemoteConnector", §9 "Approve the exact action"): the
   * core builds the main block — the call's arguments as canonical JSON, what the stored call sends to the plugin
   * after approval; the tool and the connection are the request's own — because a plugin's description of its action
   * may say anything. What the plugin said comes second, as a field "Described by jira-service-desk 1.3.0 (unsigned),
   * not verified", its invisible characters made visible, with its warnings; its other fields, facets and payload
   * aren't the person's to approve.
   */
  ActionPreview exactAction(ToolCall call, ConnectionConfig cfg, ActionPreview described) {
    JsonNode arguments = call.arguments() == null ? GppJson.object() : call.arguments();
    List<ActionPreview.Field> fields = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    if (described != null) {
      StringBuilder said = new StringBuilder();
      for (String part : new String[] {described.summary(), described.text()}) {
        if (part != null && !part.isBlank()) {
          said.append(said.isEmpty() ? "" : "\n").append(part.strip());
        }
      }
      if (!said.isEmpty()) {
        fields.add(new ActionPreview.Field("Described by " + pluginId + " " + manifest.version()
            + (signed ? "" : " (unsigned)") + ", not verified", clip(OutputSanitizer.sanitize(said), 4_000)));
      }
      for (String w : described.warnings()) {
        if (w != null && !w.isBlank()) {
          warnings.add(clip(OutputSanitizer.sanitize(w), 500));
        }
      }
    }
    return new ActionPreview(ActionPreview.Kind.JSON, "json", CanonicalJson.text(arguments), cfg.name(),
        clip(call.tool() + " on " + cfg.name(), 120), List.copyOf(fields), List.of(), List.copyOf(warnings),
        java.util.Map.of(), null, null);
  }

  private static String clip(String text, int max) {
    return text.length() <= max ? text : text.substring(0, max - 1) + "…";
  }

  @Override
  public TestResult test(ConnectionConfig cfg, SecretResolver resolver, ExecContext ctx) {
    return test(cfg, resolver, ctx, Gpp.CONNECTOR_TEST);
  }

  @Override
  public TestResult discoverDatabases(ConnectionConfig cfg, SecretResolver resolver, ExecContext ctx) {
    return test(cfg, resolver, ctx, Gpp.CONNECTOR_DISCOVER_DATABASES);
  }

  private TestResult test(ConnectionConfig cfg, SecretResolver resolver, ExecContext ctx, String method) {
    try (PluginSupervisor.Permit permit = supervisor.permit(ctx.deadline())) {
      HostProcess host = permit.host();
      try (SecretCallbackRegistry.Permit token = secrets.open(host.instance(), generation(), cfg.id(),
          ctx.callId(), cfg.secretFields(), resolver, ctx, host.log())) {
        Messages.Test params = new Messages.Test(generation(), ctx.callId(), token.token(), cfg,
            remaining(ctx).toMillis(), ctx.limits(), egressGrant(cfg));
        JsonNode result = host.request(method, params.write()).get(remaining(ctx));
        permit.returned();
        if (token.taintOverLimit()) {
          return TestResult.failure("plugin-error", manifest.name() + " sent more values to mask than a call may: "
              + "its answer isn't shown.", null);
        }
        return WireCodec.test(result);
      }
    } catch (TimeoutException e) {
      return TestResult.failure("timeout", manifest.name() + " didn't answer the test in time.", null);
    } catch (RpcException e) {
      return TestResult.failure("plugin-error", manifest.name() + " couldn't run the test: " + e.getMessage(), null);
    } catch (ConnectorException e) {
      return TestResult.failure("unavailable", e.getMessage(), null);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return TestResult.failure("cancelled", "The test was cancelled.", null);
    }
  }

  @Override
  public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver resolver, ExecContext ctx)
      throws ConnectorException {
    ToolDefinition tool = tool(call.tool()).orElseThrow(() -> ConnectorException.badInput(call.tool()
        + " isn't a tool of " + manifest.name() + "."));
    if (tool.nature() != ToolDefinition.Nature.READ && !ctx.approved()) {
      throw ConnectorException.denied(call.tool() + " changes data: it runs only after a person approved it.");
    }
    try (PluginSupervisor.Permit permit = supervisor.permit(ctx.deadline())) {
      HostProcess host = permit.host();
      try (SecretCallbackRegistry.Permit token = secrets.open(host.instance(), generation(), cfg.id(),
          ctx.callId(), cfg.secretFields(), resolver, ctx, host.log())) {
        Messages.Execute params = new Messages.Execute(generation(), token.token(), call, cfg,
            remaining(ctx).toMillis(), ctx.approved(), ctx.approvalId().orElse(null), ctx.preparedState().orElse(null),
            ctx.approvedPayload().orElse(null), ctx.limits(), ctx.traceId(), ctx.traceparent(), egressGrant(cfg));
        JsonRpcPeer.Call<JsonNode> pending = host.request(Gpp.CONNECTOR_EXECUTE, params.write());
        try {
          JsonNode answer = pending.get(remaining(ctx));
          permit.returned();
          if (token.taintOverLimit()) {
            // A value the plugin meant to mask may not be: the answer goes nowhere.
            throw ConnectorException.resourceError(manifest.name() + " sent more values to mask than a call may: "
                + "its answer isn't returned.", null);
          }
          return WireCodec.result(answer);
        } catch (TimeoutException e) {
          host.cancel(pending, "deadline");
          if (tool.nature() != ToolDefinition.Nature.READ && ctx.approved()) {
            throw ConnectorException.outcomeUnknown(manifest.name() + " didn't answer " + call.tool() + " in "
                + remaining(ctx).toSeconds() + " s: it may have done it. Check before you try again.", e);
          }
          throw ConnectorException.timeout(manifest.name() + " didn't answer " + call.tool() + " in time.");
        }
      }
    } catch (RpcException e) {
      throw connectorError(e, call, tool, ctx);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ConnectorException.timeout("The call was cancelled.");
    }
  }

  @Override
  public void onConnectionChanged(String connectionId) {
    invalidate(connectionId);
  }

  @Override
  public void onConnectionRemoved(String connectionId) {
    invalidate(connectionId);
  }

  private void invalidate(String connectionId) {
    if (supervisor.running()) {
      try (PluginSupervisor.Permit permit = supervisor.permit(deadline(Duration.ofSeconds(1)))) {
        permit.host().peer().notify(Gpp.CONNECTION_INVALIDATE, Messages.invalidate(connectionId));
        permit.returned();
      } catch (ConnectorException e) {
        // The host will see the fresh config on the next call anyway.
      }
    }
  }

  // ── Errors and egress ───────────────────────────────────────────────────────────────────────────────────────

  private ConnectorException connectorError(RpcException e, ToolCall call, ToolDefinition tool, ExecContext ctx) {
    if (e.code() == Gpp.CONNECTOR_ERROR) {
      JsonNode data = e.data();
      ConnectorException.Kind kind = parseKind(data);
      if (kind == ConnectorException.Kind.OUTCOME_UNKNOWN
          || (kind == ConnectorException.Kind.TIMEOUT && tool.nature() != ToolDefinition.Nature.READ && ctx.approved())) {
        return ConnectorException.outcomeUnknown(e.getMessage(), null);
      }
      return new ConnectorException(kind, e.getMessage(), null);
    }
    if (e.code() == Gpp.PLUGIN_NOT_LOADED || e.code() == Gpp.HOST_BUSY) {
      return ConnectorException.unavailable(manifest.name() + " isn't ready: " + e.getMessage(), null);
    }
    if (e.code() == Gpp.CANCELLED) {
      return ConnectorException.timeout(manifest.name() + " cancelled " + call.tool() + ".");
    }
    // An exception inside the plugin's code.
    return ConnectorException.resourceError(manifest.name() + " failed: " + e.getMessage(), null);
  }

  private static ConnectorException.Kind parseKind(JsonNode data) {
    if (data != null && data.path("kind").isString()) {
      try {
        return ConnectorException.Kind.valueOf(data.get("kind").asString());
      } catch (IllegalArgumentException ignored) {
        // Fall through.
      }
    }
    return ConnectorException.Kind.RESOURCE_ERROR;
  }

  /** The hosts the plugin may reach for this connection ({@code access.hosts} with {@code {connection.host}}). */
  private Messages.EgressGrant egressGrant(ConnectionConfig cfg) {
    String host = connectionHost(cfg);
    List<String> hosts = new ArrayList<>();
    for (String entry : manifest.access().hosts()) {
      String resolved = host == null ? entry : entry.replace("{connection.host}", host);
      if (!resolved.contains("{connection.host}")) {
        hosts.add(resolved);
      }
    }
    List<String> allowPrivate = policies.allowedPrivateRanges(cfg.env(), cfg.name());
    List<Integer> corePorts = new ArrayList<>(egress.settings().corePorts().get());
    return new Messages.EgressGrant(List.copyOf(hosts), List.copyOf(allowPrivate), List.copyOf(corePorts));
  }

  /** The host of the connection, from a {@code baseUrl}/{@code url}/{@code endpoint} field, or a {@code host} field. */
  private static String connectionHost(ConnectionConfig cfg) {
    for (String field : List.of("/baseUrl", "/url", "/endpoint", "/serverUrl")) {
      String value = cfg.string(field);
      if (value != null && !value.isBlank()) {
        try {
          String h = URI.create(value.strip()).getHost();
          if (h != null) {
            return h.toLowerCase(Locale.ROOT);
          }
        } catch (RuntimeException ignored) {
          // Not a URL; try the next field.
        }
      }
    }
    String host = cfg.string("/host");
    return host == null || host.isBlank() ? null : host.strip().toLowerCase(Locale.ROOT);
  }

  private Optional<ToolDefinition> tool(String name) {
    return manifest.tools().stream().filter(t -> t.name().equals(name)).findFirst();
  }

  private static Duration remaining(ExecContext ctx) {
    Duration d = ctx.remaining();
    return d.isNegative() || d.isZero() ? Duration.ofMillis(100) : d;
  }

  private java.time.Instant deadline(Duration budget) {
    return java.time.Instant.now().plus(budget);
  }
}
