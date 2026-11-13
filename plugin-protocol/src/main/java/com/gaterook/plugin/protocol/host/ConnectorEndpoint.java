package com.gaterook.plugin.protocol.host;

import com.gaterook.connector.api.Classification;
import com.gaterook.connector.api.Connector;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connector.api.PreparedAction;
import com.gaterook.connector.api.Suggestions;
import com.gaterook.connector.api.TemplateKind;
import com.gaterook.connector.api.ToolCall;
import com.gaterook.connector.api.ToolResult;
import com.gaterook.connector.api.TestResult;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.plugin.protocol.Gpp;
import com.gaterook.plugin.protocol.GppJson;
import com.gaterook.plugin.protocol.JsonRpcPeer;
import com.gaterook.plugin.protocol.Messages;
import com.gaterook.plugin.protocol.RpcException;
import com.gaterook.plugin.protocol.WireCodec;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code connector/*} half of a host (report 16 §4.3): turns a request into a call of the plugin's
 * {@link Connector} with a {@link RemoteSecretResolver} and a {@link HostExecContext}, and its outcome back into a
 * result or an error. The plugin host and the fake host the core's tests use share it, so both answer alike; loading
 * plugins, the sandbox and egress are the host's own.
 *
 * <p>Errors: a {@link ConnectorException} keeps its kind ({@code -32030}, {@code data.kind}) and message; a
 * cancelled call is {@code -32013}; anything else the plugin throws is {@code -32603} with its type, message and first
 * frames, which the core masks before anybody sees them. Secrets handed out during a call are zeroed when it ends.
 */
public final class ConnectorEndpoint {

  /**
   * One loaded generation: a plugin's one connector, or a connector module's connectors by connector type.
   *
   * @param module the generation is a connector module: its calls get the core's services ({@link
   *               HostExecContext.Spec#coreServices}) and keep their template binding
   */
  public record Loaded(String generation, Map<String, Connector> connectors, Path dataDir, boolean module) {

    public Loaded {
      connectors = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(connectors));
      if (connectors.isEmpty()) {
        throw new IllegalArgumentException(generation + " has no connector");
      }
    }

    /** A plugin: one connector, which serves every connection the core sends it. */
    public Loaded(String generation, Connector connector, Path dataDir) {
      this(generation, Map.of(connector.manifest().id(), connector), dataDir, false);
    }

    /** The first connector: a plugin's only one. */
    public Connector connector() {
      return connectors.values().iterator().next();
    }

    /**
     * The connector of a connector type. A plugin's connector answers for whatever type the core names; a module's
     * types are exactly its connectors.
     */
    public Connector connector(String type) throws RpcException {
      if (!module) {
        return connector();
      }
      Connector c = type == null ? null : connectors.get(type);
      if (c == null) {
        throw new RpcException(Gpp.INVALID_PARAMS, type + " isn't a connector type of " + generation);
      }
      return c;
    }
  }

  /** The generations this host has loaded. */
  public interface Generations {
    Optional<Loaded> find(String generation);

    Collection<Loaded> all();
  }

  /**
   * The guarded egress of one call: the plugin host's {@code EgressHttpClient}, and where the plugin declares TCP
   * destinations a {@link ScopedEgress} that also gates its own connections for the call's {@code timeout}.
   */
  @FunctionalInterface
  public interface EgressFactory {
    ExecContext.Egress egress(Messages.EgressGrant grant, ConnectionConfig cfg);

    default ExecContext.Egress egress(Messages.EgressGrant grant, ConnectionConfig cfg, Duration timeout) {
      return egress(grant, cfg);
    }
  }

  private final Supplier<JsonRpcPeer> peer;
  private final Generations generations;
  private final EgressFactory egress;
  private final Clock clock;
  private final Supplier<System.Logger.Level> logLevel;
  private final LocalQuotas quotas = new LocalQuotas();

  /** {@code logLevel} is read per call: {@code host/hello} sets it after the endpoint exists. */
  public ConnectorEndpoint(Supplier<JsonRpcPeer> peer, Generations generations, EgressFactory egress, Clock clock,
      Supplier<System.Logger.Level> logLevel) {
    this.peer = Objects.requireNonNull(peer, "peer");
    this.generations = Objects.requireNonNull(generations, "generations");
    this.egress = egress;
    this.clock = Objects.requireNonNull(clock, "clock");
    this.logLevel = Objects.requireNonNull(logLevel, "logLevel");
  }

  /** Whether this endpoint answers {@code method}. */
  public static boolean handles(String method) {
    return switch (method) {
      case Gpp.CONNECTOR_EXECUTE, Gpp.CONNECTOR_TEST, Gpp.CONNECTOR_DISCOVER_DATABASES, Gpp.CONNECTOR_SUGGEST,
          Gpp.CONNECTOR_CLASSIFY, Gpp.CONNECTOR_DESCRIBE_ACTION, Gpp.CONNECTOR_TOOLS, Gpp.CONNECTOR_PREPARE,
          Gpp.CONNECTOR_DESCRIBE, Gpp.CONNECTOR_SUPPORTS_TOOL, Gpp.TEMPLATE_COMPILE, Gpp.TEMPLATE_PREVIEW -> true;
      default -> false;
    };
  }

  public Object handle(String method, JsonNode params, JsonRpcPeer.Incoming incoming) throws RpcException {
    return switch (method) {
      case Gpp.CONNECTOR_EXECUTE -> execute(Messages.Execute.read(params), incoming);
      case Gpp.CONNECTOR_TEST -> test(Messages.Test.read(params), incoming, false);
      case Gpp.CONNECTOR_DISCOVER_DATABASES -> test(Messages.Test.read(params), incoming, true);
      case Gpp.CONNECTOR_SUGGEST -> suggest(Messages.Suggest.read(params), incoming);
      case Gpp.CONNECTOR_CLASSIFY -> classify(Messages.Classify.read(params));
      case Gpp.CONNECTOR_DESCRIBE_ACTION -> describe(Messages.Describe.read(params));
      case Gpp.CONNECTOR_TOOLS -> tools(Messages.Tools.read(params));
      case Gpp.CONNECTOR_PREPARE -> prepare(Messages.Prepare.read(params), incoming);
      case Gpp.CONNECTOR_DESCRIBE -> describeConnection(Messages.DescribeConnection.read(params), incoming);
      case Gpp.CONNECTOR_SUPPORTS_TOOL -> supportsTool(Messages.SupportsTool.read(params));
      case Gpp.TEMPLATE_COMPILE -> template(Messages.Template.read(params), false);
      case Gpp.TEMPLATE_PREVIEW -> template(Messages.Template.read(params), true);
      default -> throw new RpcException(Gpp.METHOD_NOT_FOUND, "No method " + method);
    };
  }

  /** A connection changed or went away: every loaded plugin drops what it pooled for it. */
  public void invalidate(String connectionId) {
    for (Loaded l : generations.all()) {
      for (Connector connector : l.connectors().values()) {
        try {
          connector.onConnectionChanged(connectionId);
        } catch (RuntimeException e) {
          // One connector failing to drop a pool must not keep the others from hearing of it.
        }
      }
    }
  }

  private Object execute(Messages.Execute e, JsonRpcPeer.Incoming incoming) throws RpcException {
    Loaded l = loaded(e.generation());
    Connector connector = l.connector(e.connection().type());
    long started = System.nanoTime();
    ExecContext.Egress callEgress = egress(e.egress(), e.connection(), e.timeoutMs());
    ScopedEgress scoped = callEgress instanceof ScopedEgress s ? s : null;
    HostExecContext ctx = context(e.call().callId(), e.callToken(), e.traceId(), e.traceparent(), e.connection(),
        e.limits(), e.timeoutMs(), e.approved(), e.approvalId(), e.preparedState(), e.approvedPayload(), l,
        callEgress, incoming, e.wantsProgress(), e.clientLogLevel());
    // A template's binding is a module's to read (its SQL templates); a plugin has no templates.
    ToolCall call = new ToolCall(e.call().callId(), e.call().tool(), e.connection().name(), e.call().arguments(),
        l.module() ? e.call().template() : null);
    try (RemoteSecretResolver secrets = new RemoteSecretResolver(peer.get(), e.callToken()); scoped) {
      ToolResult result = scoped == null ? connector.execute(call, e.connection(), secrets, ctx)
          : scoped.run(() -> connector.execute(call, e.connection(), secrets, ctx));
      if (incoming != null && incoming.cancelled()) {
        throw new RpcException(Gpp.CANCELLED, "Cancelled");
      }
      ObjectNode out = WireCodec.result(result == null ? ToolResult.text("No result.") : result);
      ObjectNode stats = out.putObject("stats");
      stats.put("durationMs", (System.nanoTime() - started) / 1_000_000);
      return out;
    } catch (ConnectorException ce) {
      throw cancelledOr(incoming, connectorError(explained(ce, scoped)));
    } catch (RuntimeException | LinkageError | StackOverflowError t) {
      throw cancelledOr(incoming, explainedInternal(t, scoped));
    }
  }

  private Object test(Messages.Test t, JsonRpcPeer.Incoming incoming, boolean discovery) throws RpcException {
    Loaded l = loaded(t.generation());
    Connector connector = l.connector(t.connection().type());
    ExecContext.Egress callEgress = egress(t.egress(), t.connection(), t.timeoutMs());
    ScopedEgress scoped = callEgress instanceof ScopedEgress s ? s : null;
    HostExecContext ctx = context(t.callId(), t.callToken(), null, null, t.connection(), t.limits(), t.timeoutMs(),
        false, null, null, null, l, callEgress, incoming, false, null);
    try (RemoteSecretResolver secrets = new RemoteSecretResolver(peer.get(), t.callToken()); scoped) {
      TestResult result;
      if (scoped == null) {
        result = discovery ? connector.discoverDatabases(t.connection(), secrets, ctx)
            : connector.test(t.connection(), secrets, ctx);
      } else {
        result = scoped.run(() -> discovery ? connector.discoverDatabases(t.connection(), secrets, ctx)
            : connector.test(t.connection(), secrets, ctx));
      }
      if (result == null) {
        result = TestResult.failure("no-result", "The " + (l.module() ? "connector" : "plugin")
            + " returned no result.", null);
      }
      return WireCodec.test(explained(result, scoped));
    } catch (RuntimeException | LinkageError | StackOverflowError e) {
      throw cancelledOr(incoming, explainedInternal(e, scoped));
    }
  }

  /**
   * Setup help ({@code Connector.suggest}) with a test's secrets and context: what the plugin found, or its own
   * refusal as a connector error, which the core turns into a note.
   */
  private Object suggest(Messages.Suggest s, JsonRpcPeer.Incoming incoming) throws RpcException {
    Messages.Test t = s.test();
    Loaded l = loaded(t.generation());
    Connector connector = l.connector(t.connection().type());
    ExecContext.Egress callEgress = egress(t.egress(), t.connection(), t.timeoutMs());
    ScopedEgress scoped = callEgress instanceof ScopedEgress e ? e : null;
    HostExecContext ctx = context(t.callId(), t.callToken(), null, null, t.connection(), t.limits(), t.timeoutMs(),
        false, null, null, null, l, callEgress, incoming, false, null);
    try (RemoteSecretResolver secrets = new RemoteSecretResolver(peer.get(), t.callToken()); scoped) {
      Suggestions result = scoped == null ? connector.suggest(t.connection(), secrets, ctx, s.scope())
          : scoped.run(() -> connector.suggest(t.connection(), secrets, ctx, s.scope()));
      if (incoming != null && incoming.cancelled()) {
        throw new RpcException(Gpp.CANCELLED, "Cancelled");
      }
      return WireCodec.suggestions(result == null ? Suggestions.none() : result);
    } catch (ConnectorException ce) {
      throw cancelledOr(incoming, connectorError(explained(ce, scoped)));
    } catch (RuntimeException | LinkageError | StackOverflowError e) {
      throw cancelledOr(incoming, explainedInternal(e, scoped));
    }
  }

  private ExecContext.Egress egress(Messages.EgressGrant grant, ConnectionConfig cfg, long timeoutMs) {
    return egress == null ? null : egress.egress(grant, cfg, Duration.ofMillis(Math.max(1, timeoutMs)));
  }

  /**
   * A failure while the TCP gate refused a connection of the call: the refusal is the explanation when the plugin
   * couldn't reach its resource (a driver says "SOCKS: Connection not allowed by ruleset"), and is added to any other
   * failure.
   */
  static ConnectorException explained(ConnectorException ce, ScopedEgress scoped) {
    Optional<ScopedEgress.Denial> denial = scoped == null ? Optional.empty() : scoped.lastDenial();
    if (denial.isEmpty()) {
      return ce;
    }
    String message = ce.getMessage() == null ? "" : ce.getMessage();
    if (ce.kind() == ConnectorException.Kind.RESOURCE_UNAVAILABLE || ce.kind() == ConnectorException.Kind.DENIED
        || refusedByGate(message)) {
      return ConnectorException.denied(denial.get().text());
    }
    return new ConnectorException(ce.kind(), message + " GateRook also refused a connection: "
        + denial.get().text(), null);
  }

  /** A failed test explained by the gate's refusal, with the policy change that would allow a private address. */
  static TestResult explained(TestResult result, ScopedEgress scoped) {
    Optional<ScopedEgress.Denial> denial = scoped == null ? Optional.empty() : scoped.lastDenial();
    if (result.ok() || result.needsConfirmation() != null || denial.isEmpty()) {
      return result;
    }
    ScopedEgress.Denial d = denial.get();
    String code = switch (d.reason()) {
      case PRIVATE_ADDRESS -> "tcp.private-address";
      case ALWAYS_BLOCKED, CORE_PORT -> "tcp.address-blocked";
      case OUTSIDE_CALL -> "tcp.outside-call";
      case LIMIT -> "tcp.limit";
      case NOT_GRANTED -> "tcp.not-granted";
    };
    String technical = result.error() == null ? null : result.error().message();
    TestResult explained = TestResult.failure(code, d.text(), technical).withChecks(result.checks());
    for (TestResult.Hint h : result.hints()) {
      explained = explained.withHint(h);
    }
    if (d.reason() == ScopedEgress.Reason.PRIVATE_ADDRESS && d.suggestedCidr() != null) {
      ObjectNode change = GppJson.object();
      change.put("kind", "allow-private");
      change.put("cidr", d.suggestedCidr());
      if (d.connection() != null) {
        change.put("connection", d.connection());
      }
      explained = explained.withSuggestedPolicyChange(change);
    }
    return explained;
  }

  private static RpcException explainedInternal(Throwable t, ScopedEgress scoped) {
    Optional<ScopedEgress.Denial> denial = scoped == null ? Optional.empty() : scoped.lastDenial();
    if (denial.isPresent() && refusedByGate(String.valueOf(t.getMessage()))) {
      return connectorError(ConnectorException.denied(denial.get().text()));
    }
    return internal(t);
  }

  /** The JDK's SOCKS client's words for a refusal, which drivers quote. */
  static boolean refusedByGate(String message) {
    return message != null && message.toLowerCase(java.util.Locale.ROOT).contains("not allowed by ruleset");
  }

  private Object classify(Messages.Classify c) throws RpcException {
    Loaded l = loaded(c.generation());
    Connector connector = l.connector(c.connection().type());
    try {
      Classification result = connector.classify(c.call(), c.connection(), c.policy());
      return WireCodec.classification(result == null
          ? Classification.deny("plugin-classify-empty", "The " + (l.module() ? "connector" : "plugin")
              + " didn't classify the call.", null) : result);
    } catch (RuntimeException | LinkageError | StackOverflowError e) {
      throw internal(e);
    }
  }

  private Object describe(Messages.Describe d) throws RpcException {
    Loaded l = loaded(d.generation());
    Connector connector = l.connector(d.connection().type());
    try {
      return WireCodec.action(connector.describeAction(d.call(), d.connection()));
    } catch (RuntimeException | LinkageError | StackOverflowError e) {
      throw internal(e);
    }
  }

  private Object tools(Messages.Tools t) throws RpcException {
    Loaded l = loaded(t.generation());
    Connector connector = l.module() ? l.connector(t.type()) : l.connector();
    try {
      ObjectNode out = GppJson.object();
      out.set("tools", WireCodec.tools(connector.tools(t.endpoint())));
      return out;
    } catch (RuntimeException | LinkageError | StackOverflowError e) {
      throw internal(e);
    }
  }

  // ── Connector modules: the rest of the SPI ──────────────────────────────────────────────────────────────────

  /** {@code Connector.prepare}: the dry run, the impact, the exact action; a refusal crosses as a connector error. */
  private Object prepare(Messages.Prepare p, JsonRpcPeer.Incoming incoming) throws RpcException {
    Loaded l = loaded(p.generation());
    Connector connector = l.connector(p.connection().type());
    ExecContext.Egress callEgress = egress(p.egress(), p.connection(), p.timeoutMs());
    ScopedEgress scoped = callEgress instanceof ScopedEgress s ? s : null;
    HostExecContext ctx = context(p.call().callId(), p.callToken(), p.traceId(), p.traceparent(), p.connection(),
        p.limits(), p.timeoutMs(), false, null, null, null, l, callEgress, incoming, false, null);
    ToolCall call = new ToolCall(p.call().callId(), p.call().tool(), p.connection().name(), p.call().arguments(),
        l.module() ? p.call().template() : null);
    try (RemoteSecretResolver secrets = new RemoteSecretResolver(peer.get(), p.callToken()); scoped) {
      PreparedAction result = scoped == null ? connector.prepare(call, p.connection(), secrets, ctx)
          : scoped.run(() -> connector.prepare(call, p.connection(), secrets, ctx));
      if (incoming != null && incoming.cancelled()) {
        throw new RpcException(Gpp.CANCELLED, "Cancelled");
      }
      if (result == null) {
        throw new RpcException(Gpp.INTERNAL_ERROR, "The connector prepared nothing.");
      }
      return WireCodec.prepared(result);
    } catch (ConnectorException ce) {
      throw cancelledOr(incoming, connectorError(explained(ce, scoped)));
    } catch (RuntimeException | LinkageError | StackOverflowError t) {
      throw cancelledOr(incoming, explainedInternal(t, scoped));
    }
  }

  /** {@code Connector.describe}: {@code {"value": …}}, or an empty object when the connector has nothing to add. */
  private Object describeConnection(Messages.DescribeConnection d, JsonRpcPeer.Incoming incoming) throws RpcException {
    Messages.Test t = d.test();
    Loaded l = loaded(t.generation());
    Connector connector = l.connector(t.connection().type());
    ExecContext.Egress callEgress = egress(t.egress(), t.connection(), t.timeoutMs());
    ScopedEgress scoped = callEgress instanceof ScopedEgress s ? s : null;
    HostExecContext ctx = context(t.callId(), t.callToken(), null, null, t.connection(), t.limits(), t.timeoutMs(),
        false, null, null, null, l, callEgress, incoming, false, null);
    try (RemoteSecretResolver secrets = new RemoteSecretResolver(peer.get(), t.callToken()); scoped) {
      Optional<JsonNode> value = scoped == null ? connector.describe(t.connection(), secrets, ctx, d.object())
          : scoped.run(() -> connector.describe(t.connection(), secrets, ctx, d.object()));
      ObjectNode out = GppJson.object();
      if (value != null && value.isPresent()) {
        out.set("value", value.get());
      }
      return out;
    } catch (ConnectorException ce) {
      throw cancelledOr(incoming, connectorError(explained(ce, scoped)));
    } catch (RuntimeException | LinkageError | StackOverflowError e) {
      throw cancelledOr(incoming, explainedInternal(e, scoped));
    }
  }

  private Object supportsTool(Messages.SupportsTool s) throws RpcException {
    Loaded l = loaded(s.generation());
    Connector connector = l.connector(s.connection().type());
    try {
      List<String> supported = new java.util.ArrayList<>();
      for (String tool : s.tools()) {
        if (connector.supportsTool(tool, s.connection())) {
          supported.add(tool);
        }
      }
      return Messages.SupportsTool.result(supported);
    } catch (RuntimeException | LinkageError | StackOverflowError e) {
      throw internal(e);
    }
  }

  /** {@code TemplateKind.compile} or {@code preview} of the kind the connection's type offers. */
  private Object template(Messages.Template t, boolean preview) throws RpcException {
    Loaded l = loaded(t.generation());
    Connector connector = l.connector(t.runsOn().type());
    try {
      TemplateKind kind = connector.templateKinds().stream().filter(k -> k.kind().equals(t.kind())).findFirst()
          .orElseThrow(() -> new RpcException(Gpp.INVALID_PARAMS, t.runsOn().type() + " has no template kind "
              + t.kind()));
      if (preview) {
        return WireCodec.action(kind.preview(t.definition(),
            t.arguments() == null ? GppJson.object() : t.arguments(), t.runsOn()));
      }
      return WireCodec.compiled(kind.compile(t.definition(), t.runsOn()));
    } catch (RpcException e) {
      throw e;
    } catch (RuntimeException | LinkageError | StackOverflowError e) {
      throw internal(e);
    }
  }

  private HostExecContext context(String callId, String callToken, String traceId, String traceparent,
      ConnectionConfig cfg, com.gaterook.connector.api.Limits limits, long timeoutMs, boolean approved,
      String approvalId, JsonNode preparedState, JsonNode approvedPayload, Loaded l, ExecContext.Egress e,
      JsonRpcPeer.Incoming incoming, boolean wantsProgress, String clientLogLevel) {
    String logger = "plugin." + l.generation().substring(0, Math.max(0, l.generation().indexOf('@')));
    return new HostExecContext(peer.get(), incoming, new HostExecContext.Spec(callId, callToken, traceId,
        traceparent, cfg.env(), limits, Duration.ofMillis(Math.max(1, timeoutMs)), approved, approvalId,
        preparedState, approvedPayload, l.dataDir(), e, logger, logLevel.get(), l.module(), wantsProgress,
        clientLogLevel), clock, quotas);
  }

  private Loaded loaded(String generation) throws RpcException {
    return generations.find(generation).orElseThrow(() -> new RpcException(Gpp.PLUGIN_NOT_LOADED,
        generation + " isn't loaded in this process"));
  }

  private static RpcException cancelledOr(JsonRpcPeer.Incoming incoming, RpcException e) {
    if ((incoming != null && incoming.cancelled()) || Thread.currentThread().isInterrupted()) {
      return new RpcException(Gpp.CANCELLED, "Cancelled");
    }
    return e;
  }

  /** A connector's own refusal crosses with its kind, so the core treats it as it would a built-in's. */
  public static RpcException connectorError(ConnectorException e) {
    ObjectNode data = GppJson.object();
    data.put("kind", e.kind().name());
    return new RpcException(Gpp.CONNECTOR_ERROR, e.getMessage() == null ? "The call failed." : e.getMessage(), data);
  }

  private static RpcException internal(Throwable t) {
    return new RpcException(Gpp.INTERNAL_ERROR, t.getClass().getName() + ": " + t.getMessage(),
        JsonRpcPeer.errorData(t));
  }
}
