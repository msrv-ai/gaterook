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
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.support.egress.EgressClients;
import ai.msrv.gaterook.connectors.support.OutputSanitizer;
import ai.msrv.gaterook.core.connectors.EnvironmentPolicies;
import ai.msrv.gaterook.core.connectors.PluginBuild;
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
import java.util.regex.Pattern;
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
final class RemoteConnector implements Connector, PluginBuild {

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
  public boolean signed() {
    return signed;
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
      JsonNode result = answer(permit, permit.host().request(Gpp.CONNECTOR_CLASSIFY, params.write()),
          CLASSIFY_TIMEOUT);
      return fromPlugin(WireCodec.classification(result), policy);
    } catch (TimeoutException e) {
      return Classification.deny(policy.rule("plugin-classify-timeout"), manifest.name() + " didn't classify "
          + call.tool() + " in time.", null);
    } catch (RpcException | ConnectorException e) {
      return Classification.deny(policy.rule("plugin-classify-failed"), manifest.name() + " couldn't classify "
          + call.tool() + ": " + pluginText(e.getMessage()), null);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Classification.deny(policy.rule("plugin-classify-failed"), "The call was cancelled.", null);
    } catch (RuntimeException e) {
      // An answer that isn't a classification (an unknown kind, no kind): refused, never guessed.
      return Classification.deny(policy.rule("plugin-classify-failed"), manifest.name() + " didn't give a valid "
          + "classification of " + call.tool() + ".", null);
    }
  }

  /**
   * A plugin's rule name after its namespace: up to 64 lower-case letters, digits, dashes, underscores and single dots
   * between them ({@code clickhouse.read-only}).
   */
  private static final Pattern RULE_NAME = Pattern.compile("[a-z0-9](?:[a-z0-9_-]|\\.(?=[a-z0-9])){0,63}");

  /** At most this many characters of what a plugin says in a classification (its reason, its hint). */
  static final int MAX_PLUGIN_TEXT = 500;

  /** The rule a plugin's classification names when its own id isn't a plain rule name. */
  static final String UNNAMED_RULE = "plugin-rule";

  /**
   * A plugin's classification as the core passes it to the policy, the agent and the audit. Its rule id always lies in
   * the plugin's own namespace, the prefix of the policy view it was given ({@code dev.jira-service-desk.}), so it
   * can't pass for a rule of the core or of another connector ({@code core.integrity}, {@code prod.sql.block_ddl}):
   * an id outside that prefix is put inside it, one that isn't a plain rule name becomes {@link #UNNAMED_RULE}. Its
   * reason and hint get their invisible characters made visible and are clipped to {@link #MAX_PLUGIN_TEXT}.
   */
  static Classification fromPlugin(Classification said, PolicyView policy) {
    String ruleId = said.ruleId();
    if (ruleId != null) {
      String prefix = policy.rulePrefix() + ".";
      String name = ruleId.startsWith(prefix) ? ruleId.substring(prefix.length()) : ruleId;
      ruleId = policy.rule(RULE_NAME.matcher(name).matches() ? name : UNNAMED_RULE);
    }
    return new Classification(said.kind(), ruleId, pluginText(said.reason()), pluginText(said.hint()), said.facts());
  }

  /** Text a plugin wrote, as the agent and the audit may see it: invisible characters shown, clipped. */
  private static String pluginText(String text) {
    return text == null ? null : clip(OutputSanitizer.sanitize(text), MAX_PLUGIN_TEXT);
  }

  @Override
  public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
    ActionPreview described = null;
    try (PluginSupervisor.Permit permit = supervisor.permit(deadline(DESCRIBE_TIMEOUT))) {
      Messages.Describe params = new Messages.Describe(generation(), call, cfg);
      JsonNode result = answer(permit, permit.host().request(Gpp.CONNECTOR_DESCRIBE_ACTION, params.write()),
          DESCRIBE_TIMEOUT);
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
    return exactAction(call, cfg, described, pluginId + " " + manifest.version() + (signed ? "" : " (unsigned)"));
  }

  /**
   * The exact action of a call whose description GateRook doesn't vouch for: the call's arguments as the main block,
   * and what {@code by} (a plugin, or a connector module that GateRook didn't sign) said of it as a field.
   */
  static ActionPreview exactAction(ToolCall call, ConnectionConfig cfg, ActionPreview described, String by) {
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
        fields.add(new ActionPreview.Field("Described by " + by + ", not verified",
            clip(OutputSanitizer.sanitize(said), 4_000)));
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

  /**
   * The plugin's setup help, with a test's secrets and egress. A local suggestion never starts the plugin's host (a JVM
   * and maybe its runtime's download, while the person types): it asks a host that already runs, else offers nothing.
   * A host from before {@code connector/suggest} offers nothing either. What the plugin says is bounded and shown as a
   * classification's words are ({@link #fromPlugin(Suggestions)}).
   */
  @Override
  public Suggestions suggest(ConnectionConfig cfg, SecretResolver resolver, ExecContext ctx, Suggestions.Scope scope)
      throws ConnectorException {
    if (scope == Suggestions.Scope.LOCAL && !supervisor.running()) {
      return Suggestions.none();
    }
    try (PluginSupervisor.Permit permit = supervisor.permit(ctx.deadline())) {
      HostProcess host = permit.host();
      try (SecretCallbackRegistry.Permit token = secrets.open(host.instance(), generation(), cfg.id(),
          ctx.callId(), cfg.secretFields(), resolver, ctx, host.log())) {
        Messages.Suggest params = new Messages.Suggest(new Messages.Test(generation(), ctx.callId(), token.token(),
            cfg, remaining(ctx).toMillis(), ctx.limits(), egressGrant(cfg)), scope);
        JsonNode result = answer(permit, host.request(Gpp.CONNECTOR_SUGGEST, params.write()), remaining(ctx));
        if (token.taintOverLimit()) {
          return Suggestions.builder().note(manifest.name() + " sent more values to mask than a call may: its "
              + "suggestions aren't shown.").build();
        }
        return fromPlugin(WireCodec.suggestions(result));
      }
    } catch (RpcException e) {
      if (e.code() == Gpp.METHOD_NOT_FOUND) {
        return Suggestions.none();
      }
      if (e.code() == Gpp.CONNECTOR_ERROR) {
        throw new ConnectorException(parseKind(e.data()), pluginText(e.getMessage()), null);
      }
      throw ConnectorException.resourceError(manifest.name() + " couldn't suggest settings: "
          + pluginText(e.getMessage()), null);
    } catch (TimeoutException e) {
      return Suggestions.builder().note(manifest.name() + " didn't answer in time: nothing to suggest yet.").build();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Suggestions.none();
    }
  }

  /** The longest value a plugin may suggest for a field; a longer one isn't offered. */
  static final int MAX_SUGGESTED_VALUE = 4_096;

  /**
   * A plugin's suggestions as the form may show them: pointers that are JSON Pointers, values only where they read
   * exactly as offered (no control or invisible characters, at most {@link #MAX_SUGGESTED_VALUE} characters: an
   * option the person picks is what fills the field), and labels, details, sources and notes with their invisible
   * characters made visible and clipped, as a classification's words are.
   */
  static Suggestions fromPlugin(Suggestions said) {
    Suggestions.Builder out = Suggestions.builder();
    said.fields().forEach((pointer, f) -> {
      if (pointer == null || !pointer.startsWith("/") || pointer.length() > 512 || !OutputSanitizer.isClean(pointer)) {
        return;
      }
      List<Suggestions.Option> options = new ArrayList<>();
      for (Suggestions.Option o : f.options()) {
        if (offerable(o.value())) {
          options.add(new Suggestions.Option(o.value(), pluginText(o.label()), pluginText(o.detail())));
        }
      }
      String value = offerable(f.value()) ? f.value() : null;
      if (!options.isEmpty() || value != null) {
        out.field(pointer, new Suggestions.Field(options, value, pluginText(f.source()),
            f.complete() && options.size() == f.options().size()));
      }
    });
    said.notes().forEach(n -> out.note(pluginText(n)));
    return out.build();
  }

  private static boolean offerable(String value) {
    return value != null && value.length() <= MAX_SUGGESTED_VALUE && OutputSanitizer.isClean(value);
  }

  private TestResult test(ConnectionConfig cfg, SecretResolver resolver, ExecContext ctx, String method) {
    try (PluginSupervisor.Permit permit = supervisor.permit(ctx.deadline())) {
      HostProcess host = permit.host();
      try (SecretCallbackRegistry.Permit token = secrets.open(host.instance(), generation(), cfg.id(),
          ctx.callId(), cfg.secretFields(), resolver, ctx, host.log())) {
        Messages.Test params = new Messages.Test(generation(), ctx.callId(), token.token(), cfg,
            remaining(ctx).toMillis(), ctx.limits(), egressGrant(cfg));
        JsonNode result = answer(permit, host.request(method, params.write()), remaining(ctx));
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
          JsonNode answer = answer(permit, pending, remaining(ctx));
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

  /**
   * Where the plugin may go for this connection: {@code access.hosts} over HTTP and {@code access.tcp} over its own TCP
   * connections, with {@code {connection.host}} and {@code {connection.port}} filled in from the connection. An entry
   * whose placeholder the connection can't fill grants nothing.
   */
  private Messages.EgressGrant egressGrant(ConnectionConfig cfg) {
    String host = connectionHost(cfg);
    String port = connectionPort(cfg, manifest.configSchema());
    List<String> hosts = resolved(manifest.access().hosts(), host, port);
    List<String> tcp = resolved(manifest.access().tcp(), host, port);
    List<String> allowPrivate = policies.allowedPrivateRanges(cfg.env(), cfg.name());
    List<Integer> corePorts = new ArrayList<>(egress.settings().corePorts().get());
    return new Messages.EgressGrant(List.copyOf(hosts), List.copyOf(allowPrivate), List.copyOf(corePorts),
        List.copyOf(tcp));
  }

  /** {@code entries} with the connection's host and port filled in; those left with a placeholder are dropped. */
  static List<String> resolved(List<String> entries, String host, String port) {
    List<String> out = new ArrayList<>();
    for (String entry : entries) {
      String r = entry;
      if (host != null) {
        r = r.replace("{connection.host}", host);
      }
      if (port != null) {
        r = r.replace("{connection.port}", port);
      }
      if (!r.contains("{connection.")) {
        out.add(r);
      }
    }
    return out;
  }

  /**
   * The port of the connection: its {@code port} field, else the default its config schema gives that field, else
   * {@code null} (an entry that names the connection's port then grants nothing).
   */
  static String connectionPort(ConnectionConfig cfg, JsonNode configSchema) {
    int port = cfg.integer("/port", -1);
    if (port < 1 && configSchema != null) {
      JsonNode dflt = configSchema.path("properties").path("port").path("default");
      port = dflt.isIntegralNumber() ? dflt.asInt() : -1;
    }
    return port >= 1 && port <= 65_535 ? String.valueOf(port) : null;
  }

  /** The host of the connection, from a {@code baseUrl}/{@code url}/{@code endpoint} field, or a {@code host} field. */
  static String connectionHost(ConnectionConfig cfg) {
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
    if (host == null || host.isBlank()) {
      return null;
    }
    String h = host.strip().toLowerCase(Locale.ROOT);
    // An IPv6 literal as a URL writes it ([::1]): a bare one before a ":port" of the manifest would read as a port.
    return h.indexOf(':') >= 0 && !h.startsWith("[") ? "[" + h + "]" : h;
  }

  private Optional<ToolDefinition> tool(String name) {
    return manifest.tools().stream().filter(t -> t.name().equals(name)).findFirst();
  }

  /**
   * The host's answer to {@code call}. An answer, an error answer included, tells the supervisor the host isn't stuck
   * ({@link PluginSupervisor.Permit#returned}): only a call with no answer by its deadline counts toward replacing it,
   * so calls that fail inside the plugin don't restart its host.
   */
  static JsonNode answer(PluginSupervisor.Permit permit, JsonRpcPeer.Call<JsonNode> call, Duration timeout)
      throws RpcException, TimeoutException, InterruptedException {
    try {
      JsonNode result = call.get(timeout);
      permit.returned();
      return result;
    } catch (RpcException e) {
      permit.returned();
      throw e;
    }
  }

  private static Duration remaining(ExecContext ctx) {
    Duration d = ctx.remaining();
    return d.isNegative() || d.isZero() ? Duration.ofMillis(100) : d;
  }

  private java.time.Instant deadline(Duration budget) {
    return java.time.Instant.now().plus(budget);
  }
}
