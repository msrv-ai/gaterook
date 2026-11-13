package com.gaterook.core.connectors.plugins;

import com.gaterook.connector.api.ActionPreview;
import com.gaterook.connector.api.Classification;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Connector;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ConnectorManifest;
import com.gaterook.connector.api.EndpointView;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connector.api.PolicyView;
import com.gaterook.connector.api.PreparedAction;
import com.gaterook.connector.api.SecretResolver;
import com.gaterook.connector.api.Suggestions;
import com.gaterook.connector.api.TemplateKind;
import com.gaterook.connector.api.TestResult;
import com.gaterook.connector.api.ToolCall;
import com.gaterook.connector.api.ToolDefinition;
import com.gaterook.connector.api.ToolResult;
import com.gaterook.connectors.support.egress.EgressClients;
import com.gaterook.core.connectors.EnvironmentPolicies;
import com.gaterook.core.platform.CanonicalJson;
import com.gaterook.plugin.protocol.Gpp;
import com.gaterook.plugin.protocol.GppJson;
import com.gaterook.plugin.protocol.JsonRpcPeer;
import com.gaterook.plugin.protocol.Messages;
import com.gaterook.plugin.protocol.PluginManifest;
import com.gaterook.plugin.protocol.RpcException;
import com.gaterook.plugin.protocol.WireCodec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * One connector type of a connector module, seen as an ordinary {@link Connector} (docs/connector-delivery.md). A
 * module is GateRook's own connector code with another delivery: it runs in a module host, and this class forwards the
 * whole connector SPI to it over GPP/1. What the connector says is taken as a compiled-in connector's is: its
 * classification, its exact action and its prepared state are the connector's own, where {@link RemoteConnector}
 * takes a plugin's word for none of them.
 *
 * <p>That holds for a package GateRook's signed list pins. A module a person installed from a file that the list
 * doesn't pin ({@link #signed} is false) is code nobody reviewed, with a module's richer interface: the exact action
 * a person approves is the core's own, the call's arguments, with the module's words beside them as unverified; its
 * tools are the declared ones, whose definitions the installed row pins; and it adds no template kinds. Its tools
 * start in Approval, and Read only is a person's confirmation per tool ({@code ToolPairs}).
 *
 * <p>The manifest comes from the installed row, so the registry and {@code tools/list} start no host. A call starts
 * the host when it sleeps, and first asks the {@link Gate} for the module's files: a module whose files are missing
 * (a restored backup on a fresh installation) is downloaded again by the call that needs it. A call that can't reach
 * its connector fails closed: a classification that didn't arrive is a DENY, a change whose answer didn't arrive is
 * {@code OUTCOME_UNKNOWN} and is never sent twice.
 */
final class ModuleConnector implements Connector, com.gaterook.core.connectors.PluginBuild {

  /**
   * What stands between a call and its module's host: the module's files.
   */
  @FunctionalInterface
  interface Gate {

    /**
     * Returns once the module's package is in place.
     *
     * @throws ConnectorException {@code RESOURCE_UNAVAILABLE} while it is being downloaded or can't be
     */
    void await(Instant deadline) throws ConnectorException;
  }

  private static final Logger log = LoggerFactory.getLogger(ModuleConnector.class);
  /** How long a call that needs no I/O of its own waits for a sleeping host to start. */
  static final Duration START_BUDGET = Duration.ofSeconds(20);
  static final Duration CLASSIFY_TIMEOUT = Duration.ofSeconds(5);
  static final Duration DESCRIBE_TIMEOUT = Duration.ofSeconds(5);
  static final Duration TEMPLATE_TIMEOUT = Duration.ofSeconds(10);
  /** Answers of {@code supportsTool} kept per connection version. */
  private static final int MAX_SUPPORT_ENTRIES = 4_096;

  private final PluginManifest module;
  private final PluginManifest.Type type;
  private final ConnectorManifest manifest;
  private final PluginSupervisor supervisor;
  private final SecretCallbackRegistry secrets;
  private final EnvironmentPolicies policies;
  private final EgressClients egress;
  private final Gate gate;
  private final boolean signed;
  private final List<TemplateKind> templateKinds;
  private final Map<String, Boolean> supports = new ConcurrentHashMap<>();

  /**
   * @param signed whether GateRook's signed list pins the package: {@code false} for a person's own module from a
   *               file, whose word the core doesn't take
   */
  ModuleConnector(PluginManifest module, ConnectorManifest manifest, PluginSupervisor supervisor,
      SecretCallbackRegistry secrets, EnvironmentPolicies policies, EgressClients egress, Gate gate, boolean signed) {
    this.module = module;
    this.manifest = manifest;
    this.type = module.type(manifest.id()).orElseThrow(() -> new IllegalArgumentException(manifest.id()
        + " isn't a connector type of " + module.id()));
    this.supervisor = supervisor;
    this.secrets = secrets;
    this.policies = policies;
    this.egress = egress;
    this.gate = gate;
    this.signed = signed;
    List<TemplateKind> kinds = new ArrayList<>();
    if (signed) {
      type.templateKinds().forEach(k -> kinds.add(new RemoteKind(k)));
    }
    this.templateKinds = List.copyOf(kinds);
  }

  /** Whether GateRook's signed list pins the package this connector runs. */
  @Override
  public boolean signed() {
    return signed;
  }

  /** A build GateRook didn't sign is known by its version and its package: another build starts unconfirmed. */
  @Override
  public String provenance() {
    if (signed) {
      return null;
    }
    HostLauncher.Spec spec = supervisor.spec();
    String sha = spec == null ? null : spec.sha256();
    return module.version() + (sha == null || sha.length() < 12 ? "" : "+" + sha.substring(0, 12));
  }

  /** Who said what the core doesn't vouch for: "orders 1.2.0 (unsigned)". */
  private String unsignedBuild() {
    return module.id() + " " + module.version() + " (unsigned)";
  }

  /** The module this type belongs to. */
  String moduleId() {
    return module.id();
  }

  private String generation() {
    return supervisor.spec() == null ? module.id() + "@" + module.version() : supervisor.spec().generation();
  }

  private boolean can(String capability) {
    return type.capabilities().contains(capability);
  }

  /** A permit on the module's host, started if it sleeps, its package downloaded again if it is missing. */
  private PluginSupervisor.Permit permit(Instant deadline) throws ConnectorException {
    gate.await(deadline);
    return supervisor.permit(deadline);
  }

  @Override
  public ConnectorManifest manifest() {
    return manifest;
  }

  @Override
  public int maxConcurrency() {
    return type.maxConcurrency();
  }

  @Override
  public boolean runsCommand(String tool) {
    return type.commandTools().contains(tool);
  }

  @Override
  public List<TemplateKind> templateKinds() {
    return templateKinds;
  }

  // ── No I/O of the connector's own: tools, classification, the exact action ──────────────────────────────────

  /**
   * The type's tools on one endpoint. Only a type whose tools depend on the endpoint is asked; when its host can't
   * answer, the tools are the declared ones, which the endpoint's own checks still apply to.
   */
  @Override
  public List<ToolDefinition> tools(EndpointView endpoint) {
    if (!signed || !can("tools.endpoint")) {
      return manifest.tools();
    }
    try (PluginSupervisor.Permit permit = permit(deadline(START_BUDGET))) {
      Messages.Tools params = new Messages.Tools(generation(), "", endpoint, manifest.id());
      JsonNode result = RemoteConnector.answer(permit, permit.host().request(Gpp.CONNECTOR_TOOLS, params.write()),
          DESCRIBE_TIMEOUT);
      return WireCodec.tools(result.path("tools"));
    } catch (TimeoutException | RpcException | ConnectorException | RuntimeException e) {
      log.debug("tools of {} on {} weren't answered: {}", manifest.id(), endpoint.slug(), e.toString());
      return manifest.tools();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return manifest.tools();
    }
  }

  /**
   * Whether a tool applies to a connection. Asked once per connection version; while the host can't answer, a tool
   * counts as supported: classification still refuses a call the connection can't serve.
   */
  @Override
  public boolean supportsTool(String tool, ConnectionConfig cfg) {
    if (!can("supportsTool")) {
      return true;
    }
    String key = cfg.id() + "\n" + cfg.version() + "\n" + tool;
    Boolean known = supports.get(key);
    if (known != null) {
      return known;
    }
    List<String> names = manifest.tools().stream().map(ToolDefinition::name).toList();
    try (PluginSupervisor.Permit permit = permit(deadline(START_BUDGET))) {
      JsonNode result = RemoteConnector.answer(permit, permit.host().request(Gpp.CONNECTOR_SUPPORTS_TOOL,
          new Messages.SupportsTool(generation(), cfg, names).write()), DESCRIBE_TIMEOUT);
      List<String> supported = Messages.SupportsTool.supported(result);
      if (supports.size() > MAX_SUPPORT_ENTRIES) {
        supports.clear();
      }
      names.forEach(n -> supports.put(cfg.id() + "\n" + cfg.version() + "\n" + n, supported.contains(n)));
      return supported.contains(tool);
    } catch (TimeoutException | RpcException | ConnectorException | RuntimeException e) {
      log.debug("supportsTool of {} wasn't answered: {}", manifest.id(), e.toString());
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return true;
    }
  }

  /**
   * The connector's own classification of the call. Every tool is asked, a read too: a connector reads the statement
   * before it calls a query a read. No answer is a DENY.
   */
  @Override
  public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
    try (PluginSupervisor.Permit permit = permit(deadline(START_BUDGET))) {
      Messages.Classify params = new Messages.Classify(generation(), call, cfg, policy);
      JsonNode result = RemoteConnector.answer(permit, permit.host().request(Gpp.CONNECTOR_CLASSIFY,
          params.write()), CLASSIFY_TIMEOUT);
      Classification said = WireCodec.classification(result);
      // The rule and the words of code nobody reviewed are held to the type's own rules and made plain text.
      return signed ? said : RemoteConnector.fromPlugin(said, policy);
    } catch (TimeoutException e) {
      return Classification.deny(policy.rule("connector-unavailable"), manifest.name() + " didn't classify "
          + call.tool() + " in time. Try again.", null);
    } catch (ConnectorException e) {
      return Classification.deny(policy.rule("connector-unavailable"), e.getMessage(), null);
    } catch (RpcException e) {
      return Classification.deny(policy.rule("connector-unavailable"), manifest.name() + " couldn't classify "
          + call.tool() + ": " + e.getMessage(), null);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Classification.deny(policy.rule("connector-unavailable"), "The call was cancelled.", null);
    } catch (RuntimeException e) {
      // An answer that isn't a classification: refused, never guessed.
      log.warn("{} answered {} with something that isn't a classification", manifest.id(), call.tool(), e);
      return Classification.deny(policy.rule("connector-unavailable"), manifest.name() + " didn't give a valid "
          + "classification of " + call.tool() + ".", null);
    }
  }

  /**
   * The exact action as the connector writes it. When its host can't answer, the action is the call itself: the
   * tool, the connection and the arguments as canonical JSON, which is all the connector will be given to run.
   */
  @Override
  public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
    try (PluginSupervisor.Permit permit = permit(deadline(START_BUDGET))) {
      Messages.Describe params = new Messages.Describe(generation(), call, cfg);
      JsonNode result = RemoteConnector.answer(permit, permit.host().request(Gpp.CONNECTOR_DESCRIBE_ACTION,
          params.write()), DESCRIBE_TIMEOUT);
      ActionPreview described = WireCodec.action(result);
      return signed ? described : RemoteConnector.exactAction(call, cfg, described, unsignedBuild());
    } catch (TimeoutException | RpcException | ConnectorException | RuntimeException e) {
      log.debug("describeAction of {} by {} failed: {}", call.tool(), manifest.id(), e.toString());
      return callAsAction(call, cfg, e instanceof ConnectorException ? e.getMessage()
          : manifest.name() + " didn't describe this action.");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return callAsAction(call, cfg, "The call was cancelled.");
    }
  }

  static ActionPreview callAsAction(ToolCall call, ConnectionConfig cfg, String why) {
    JsonNode arguments = call.arguments() == null ? GppJson.object() : call.arguments();
    return new ActionPreview(ActionPreview.Kind.JSON, "json", CanonicalJson.text(arguments), cfg.name(),
        call.tool() + " on " + cfg.name(), List.of(), List.of(), List.of(why + " Shown as the call's arguments."),
        Map.of(), null, null);
  }

  // ── Calls with the connection's secrets and egress ──────────────────────────────────────────────────────────

  /** {@code Connector.prepare} in the host: the dry run, the impact, the state the approved call gets back. */
  @Override
  public PreparedAction prepare(ToolCall call, ConnectionConfig cfg, SecretResolver resolver, ExecContext ctx)
      throws ConnectorException {
    if (!can("prepare")) {
      return PreparedAction.approval(describeAction(call, cfg), null);
    }
    try (PluginSupervisor.Permit permit = permit(ctx.deadline())) {
      HostProcess host = permit.host();
      try (SecretCallbackRegistry.Permit token = open(host, cfg, resolver, ctx)) {
        Messages.Prepare params = new Messages.Prepare(generation(), token.token(), call, cfg,
            remaining(ctx).toMillis(), ctx.limits(), ctx.traceId(), ctx.traceparent(), egressGrant(cfg));
        JsonRpcPeer.Call<JsonNode> pending = host.request(Gpp.CONNECTOR_PREPARE, params.write());
        try {
          JsonNode answer = RemoteConnector.answer(permit, pending, remaining(ctx));
          requireMaskable(token);
          PreparedAction prepared = WireCodec.prepared(answer);
          // What a person approves of code nobody reviewed is the call itself; the state stays the connector's.
          return signed || prepared.preview() == null ? prepared : new PreparedAction(prepared.outcome(),
              RemoteConnector.exactAction(call, cfg, prepared.preview(), unsignedBuild()), prepared.preparedState(),
              prepared.message());
        } catch (TimeoutException e) {
          host.cancel(pending, "deadline");
          throw ConnectorException.timeout(manifest.name() + " didn't prepare " + call.tool() + " in time.");
        }
      }
    } catch (RpcException e) {
      throw connectorError(e, call.tool(), false);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ConnectorException.timeout("The call was cancelled.");
    }
  }

  @Override
  public TestResult test(ConnectionConfig cfg, SecretResolver resolver, ExecContext ctx) {
    return test(cfg, resolver, ctx, Gpp.CONNECTOR_TEST);
  }

  @Override
  public TestResult discoverDatabases(ConnectionConfig cfg, SecretResolver resolver, ExecContext ctx) {
    return can("discoverDatabases") ? test(cfg, resolver, ctx, Gpp.CONNECTOR_DISCOVER_DATABASES)
        : Connector.super.discoverDatabases(cfg, resolver, ctx);
  }

  private TestResult test(ConnectionConfig cfg, SecretResolver resolver, ExecContext ctx, String method) {
    try (PluginSupervisor.Permit permit = permit(ctx.deadline())) {
      HostProcess host = permit.host();
      try (SecretCallbackRegistry.Permit token = open(host, cfg, resolver, ctx)) {
        Messages.Test params = new Messages.Test(generation(), ctx.callId(), token.token(), cfg,
            remaining(ctx).toMillis(), ctx.limits(), egressGrant(cfg));
        JsonNode result = RemoteConnector.answer(permit, host.request(method, params.write()), remaining(ctx));
        if (token.taintOverLimit()) {
          return TestResult.failure("connector-error", manifest.name() + " sent more values to mask than a call "
              + "may: its answer isn't shown.", null);
        }
        return WireCodec.test(result);
      }
    } catch (TimeoutException e) {
      return TestResult.failure("timeout", manifest.name() + " didn't answer the test in time.", null);
    } catch (RpcException e) {
      return TestResult.failure("connector-error", manifest.name() + " couldn't run the test: " + e.getMessage(),
          null);
    } catch (ConnectorException e) {
      return TestResult.failure("unavailable", e.getMessage(), null);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return TestResult.failure("cancelled", "The test was cancelled.", null);
    }
  }

  /**
   * The connector's setup help. A local suggestion never starts the host, nor a download, while the person types: it
   * asks a host that already runs, else offers nothing.
   */
  @Override
  public Suggestions suggest(ConnectionConfig cfg, SecretResolver resolver, ExecContext ctx, Suggestions.Scope scope)
      throws ConnectorException {
    if (!can("suggest") || (scope == Suggestions.Scope.LOCAL && !supervisor.running())) {
      return Suggestions.none();
    }
    try (PluginSupervisor.Permit permit = permit(ctx.deadline())) {
      HostProcess host = permit.host();
      try (SecretCallbackRegistry.Permit token = open(host, cfg, resolver, ctx)) {
        Messages.Suggest params = new Messages.Suggest(new Messages.Test(generation(), ctx.callId(), token.token(),
            cfg, remaining(ctx).toMillis(), ctx.limits(), egressGrant(cfg)), scope);
        JsonNode result = RemoteConnector.answer(permit, host.request(Gpp.CONNECTOR_SUGGEST, params.write()),
            remaining(ctx));
        requireMaskable(token);
        return WireCodec.suggestions(result);
      }
    } catch (RpcException e) {
      throw connectorError(e, "suggest", false);
    } catch (TimeoutException e) {
      return Suggestions.builder().note(manifest.name() + " didn't answer in time: nothing to suggest yet.").build();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Suggestions.none();
    }
  }

  @Override
  public Optional<JsonNode> describe(ConnectionConfig cfg, SecretResolver resolver, ExecContext ctx, String object)
      throws ConnectorException {
    if (!can("describe")) {
      return Optional.empty();
    }
    try (PluginSupervisor.Permit permit = permit(ctx.deadline())) {
      HostProcess host = permit.host();
      try (SecretCallbackRegistry.Permit token = open(host, cfg, resolver, ctx)) {
        Messages.DescribeConnection params = new Messages.DescribeConnection(new Messages.Test(generation(),
            ctx.callId(), token.token(), cfg, remaining(ctx).toMillis(), ctx.limits(), egressGrant(cfg)), object);
        JsonNode result = RemoteConnector.answer(permit, host.request(Gpp.CONNECTOR_DESCRIBE, params.write()),
            remaining(ctx));
        requireMaskable(token);
        JsonNode value = result.get("value");
        return value == null || value.isNull() ? Optional.empty() : Optional.of(value);
      }
    } catch (RpcException e) {
      throw connectorError(e, "describe", false);
    } catch (TimeoutException e) {
      throw ConnectorException.timeout(manifest.name() + " didn't describe " + cfg.name() + " in time.");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ConnectorException.timeout("The call was cancelled.");
    }
  }

  @Override
  public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver resolver, ExecContext ctx)
      throws ConnectorException {
    ToolDefinition tool = type.tool(call.tool()).orElse(null);
    // A template's tool isn't one of the type's own: what it may change, its binding tells the connector.
    boolean mayChange = tool == null || tool.nature() != ToolDefinition.Nature.READ;
    try (PluginSupervisor.Permit permit = permit(ctx.deadline())) {
      HostProcess host = permit.host();
      try (SecretCallbackRegistry.Permit token = open(host, cfg, resolver, ctx)) {
        Messages.Execute params = new Messages.Execute(generation(), token.token(), call, cfg,
            remaining(ctx).toMillis(), ctx.approved(), ctx.approvalId().orElse(null), ctx.preparedState().orElse(null),
            ctx.approvedPayload().orElse(null), ctx.limits(), ctx.traceId(), ctx.traceparent(), egressGrant(cfg),
            ctx.wantsProgress(), ctx.clientLogLevel());
        JsonRpcPeer.Call<JsonNode> pending = host.request(Gpp.CONNECTOR_EXECUTE, params.write());
        try {
          JsonNode answer = RemoteConnector.answer(permit, pending, remaining(ctx));
          requireMaskable(token);
          return WireCodec.result(answer);
        } catch (TimeoutException e) {
          host.cancel(pending, "deadline");
          if (mayChange && ctx.approved()) {
            throw ConnectorException.outcomeUnknown(manifest.name() + " didn't answer " + call.tool() + " in "
                + remaining(ctx).toSeconds() + " s: it may have done it. Check before you try again.", e);
          }
          throw ConnectorException.timeout(manifest.name() + " didn't answer " + call.tool() + " in time.");
        }
      }
    } catch (RpcException e) {
      throw connectorError(e, call.tool(), mayChange && ctx.approved());
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
    supports.keySet().removeIf(k -> k.startsWith(connectionId + "\n"));
    if (supervisor.running()) {
      try (PluginSupervisor.Permit permit = supervisor.permit(deadline(Duration.ofSeconds(1)))) {
        permit.host().peer().notify(Gpp.CONNECTION_INVALIDATE, Messages.invalidate(connectionId));
        permit.returned();
      } catch (ConnectorException e) {
        // The host sees the new configuration with the next call anyway.
      }
    }
  }

  // ── Template kinds ──────────────────────────────────────────────────────────────────────────────────────────

  /** A template kind of this type: its form is the manifest's, it compiles and previews in the module's host. */
  private final class RemoteKind implements TemplateKind {

    private final PluginManifest.TemplateKindManifest declared;

    RemoteKind(PluginManifest.TemplateKindManifest declared) {
      this.declared = declared;
    }

    @Override
    public String kind() {
      return declared.kind();
    }

    @Override
    public List<String> connectionTypes() {
      return declared.connectionTypes();
    }

    @Override
    public JsonNode definitionSchema() {
      return declared.definitionSchema();
    }

    @Override
    public CompileResult compile(JsonNode definition, ConnectionConfig runsOn) {
      try {
        return WireCodec.compiled(ask(Gpp.TEMPLATE_COMPILE, definition, null, runsOn));
      } catch (ConnectorException | RuntimeException e) {
        return new CompileResult(List.of(), List.of(), List.of(manifest.name() + " couldn't check the template: "
            + e.getMessage()), List.of(), null);
      }
    }

    @Override
    public ActionPreview preview(JsonNode definition, ObjectNode arguments, ConnectionConfig runsOn) {
      try {
        return WireCodec.action(ask(Gpp.TEMPLATE_PREVIEW, definition, arguments, runsOn));
      } catch (ConnectorException e) {
        throw new IllegalStateException(e.getMessage(), e);
      }
    }

    private JsonNode ask(String method, JsonNode definition, ObjectNode arguments, ConnectionConfig runsOn)
        throws ConnectorException {
      try (PluginSupervisor.Permit permit = permit(deadline(START_BUDGET))) {
        Messages.Template params = new Messages.Template(generation(), declared.kind(), definition, arguments,
            runsOn);
        return RemoteConnector.answer(permit, permit.host().request(method, params.write()), TEMPLATE_TIMEOUT);
      } catch (TimeoutException e) {
        throw ConnectorException.timeout(manifest.name() + " didn't answer in time.");
      } catch (RpcException e) {
        throw ConnectorException.resourceError(e.getMessage(), null);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw ConnectorException.timeout("The call was cancelled.");
      }
    }
  }

  // ── Internals ───────────────────────────────────────────────────────────────────────────────────────────────

  private SecretCallbackRegistry.Permit open(HostProcess host, ConnectionConfig cfg, SecretResolver resolver,
      ExecContext ctx) {
    return secrets.openForModule(host.instance(), generation(), cfg.id(), ctx.callId(), cfg.secretFields(), resolver,
        ctx, host.log());
  }

  /** An answer whose values to mask went over what a call may send goes nowhere: one of them may show. */
  private void requireMaskable(SecretCallbackRegistry.Permit token) throws ConnectorException {
    if (token.taintOverLimit()) {
      throw ConnectorException.resourceError(manifest.name() + " sent more values to mask than a call may: its "
          + "answer isn't returned.", null);
    }
  }

  private ConnectorException connectorError(RpcException e, String what, boolean approvedChange) {
    if (e.code() == Gpp.CONNECTOR_ERROR) {
      ConnectorException.Kind kind = kind(e.data());
      if (kind == ConnectorException.Kind.OUTCOME_UNKNOWN
          || (kind == ConnectorException.Kind.TIMEOUT && approvedChange)) {
        return ConnectorException.outcomeUnknown(e.getMessage(), null);
      }
      return new ConnectorException(kind, e.getMessage(), null);
    }
    if (e.code() == Gpp.PLUGIN_NOT_LOADED || e.code() == Gpp.HOST_BUSY) {
      return ConnectorException.unavailable(manifest.name() + " isn't ready: " + e.getMessage(), null);
    }
    if (e.code() == Gpp.CANCELLED) {
      return ConnectorException.timeout(manifest.name() + " cancelled " + what + ".");
    }
    // An exception inside the connector's code: the outcome of an approved change is unknown.
    return approvedChange ? ConnectorException.outcomeUnknown(manifest.name() + " failed while it ran " + what
        + ": " + e.getMessage() + " It may have done it. Check before you try again.", null)
        : ConnectorException.resourceError(manifest.name() + " failed: " + e.getMessage(), null);
  }

  private static ConnectorException.Kind kind(JsonNode data) {
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
   * Where the connector may go for this connection: the module's {@code access.hosts} over HTTP and {@code
   * access.tcp} over its own connections, with the connection's host and port filled in.
   */
  private Messages.EgressGrant egressGrant(ConnectionConfig cfg) {
    String host = RemoteConnector.connectionHost(cfg);
    String port = RemoteConnector.connectionPort(cfg, manifest.configSchema());
    List<String> hosts = RemoteConnector.resolved(manifest.access().hosts(), host, port);
    List<String> tcp = RemoteConnector.resolved(manifest.access().tcp(), host, port);
    return new Messages.EgressGrant(List.copyOf(hosts), List.copyOf(policies.allowedPrivateRanges(cfg.env(),
        cfg.name())), List.copyOf(new ArrayList<>(egress.settings().corePorts().get())), List.copyOf(tcp));
  }

  private static Duration remaining(ExecContext ctx) {
    Duration d = ctx.remaining();
    return d.isNegative() || d.isZero() ? Duration.ofMillis(100) : d;
  }

  private static Instant deadline(Duration budget) {
    return Instant.now().plus(budget);
  }
}
