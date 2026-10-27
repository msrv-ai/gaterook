package ai.msrv.gaterook.plugin.protocol.host;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.plugin.protocol.Gpp;
import ai.msrv.gaterook.plugin.protocol.GppJson;
import ai.msrv.gaterook.plugin.protocol.JsonRpcPeer;
import ai.msrv.gaterook.plugin.protocol.Messages;
import ai.msrv.gaterook.plugin.protocol.RpcException;
import ai.msrv.gaterook.plugin.protocol.WireCodec;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
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

  /** One loaded plugin generation. */
  public record Loaded(String generation, Connector connector, Path dataDir) {}

  /** The generations this host has loaded. */
  public interface Generations {
    Optional<Loaded> find(String generation);

    Collection<Loaded> all();
  }

  /** The guarded HTTP client of one call (the plugin host's {@code EgressHttpClient}). */
  @FunctionalInterface
  public interface EgressFactory {
    ExecContext.Egress egress(Messages.EgressGrant grant, ConnectionConfig cfg);
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
      case Gpp.CONNECTOR_EXECUTE, Gpp.CONNECTOR_TEST, Gpp.CONNECTOR_CLASSIFY, Gpp.CONNECTOR_DESCRIBE_ACTION,
          Gpp.CONNECTOR_TOOLS -> true;
      default -> false;
    };
  }

  public Object handle(String method, JsonNode params, JsonRpcPeer.Incoming incoming) throws RpcException {
    return switch (method) {
      case Gpp.CONNECTOR_EXECUTE -> execute(Messages.Execute.read(params), incoming);
      case Gpp.CONNECTOR_TEST -> test(Messages.Test.read(params), incoming);
      case Gpp.CONNECTOR_CLASSIFY -> classify(Messages.Classify.read(params));
      case Gpp.CONNECTOR_DESCRIBE_ACTION -> describe(Messages.Describe.read(params));
      case Gpp.CONNECTOR_TOOLS -> tools(Messages.Tools.read(params));
      default -> throw new RpcException(Gpp.METHOD_NOT_FOUND, "No method " + method);
    };
  }

  /** A connection changed or went away: every loaded plugin drops what it pooled for it. */
  public void invalidate(String connectionId) {
    for (Loaded l : generations.all()) {
      try {
        l.connector().onConnectionChanged(connectionId);
      } catch (RuntimeException e) {
        // One plugin failing to drop a pool must not keep the others from hearing of it.
      }
    }
  }

  private Object execute(Messages.Execute e, JsonRpcPeer.Incoming incoming) throws RpcException {
    Loaded l = loaded(e.generation());
    long started = System.nanoTime();
    HostExecContext ctx = context(e.call().callId(), e.callToken(), e.traceId(), e.traceparent(), e.connection(),
        e.limits(), e.timeoutMs(), e.approved(), e.approvalId(), e.preparedState(), e.approvedPayload(), l,
        e.egress(), incoming);
    ToolCall call = new ToolCall(e.call().callId(), e.call().tool(), e.connection().name(), e.call().arguments(), null);
    try (RemoteSecretResolver secrets = new RemoteSecretResolver(peer.get(), e.callToken())) {
      ToolResult result = l.connector().execute(call, e.connection(), secrets, ctx);
      if (incoming != null && incoming.cancelled()) {
        throw new RpcException(Gpp.CANCELLED, "Cancelled");
      }
      ObjectNode out = WireCodec.result(result == null ? ToolResult.text("No result.") : result);
      ObjectNode stats = out.putObject("stats");
      stats.put("durationMs", (System.nanoTime() - started) / 1_000_000);
      return out;
    } catch (ConnectorException ce) {
      throw cancelledOr(incoming, connectorError(ce));
    } catch (RuntimeException | LinkageError | StackOverflowError t) {
      throw cancelledOr(incoming, internal(t));
    }
  }

  private Object test(Messages.Test t, JsonRpcPeer.Incoming incoming) throws RpcException {
    Loaded l = loaded(t.generation());
    HostExecContext ctx = context(t.callId(), t.callToken(), null, null, t.connection(), t.limits(), t.timeoutMs(),
        false, null, null, null, l, t.egress(), incoming);
    try (RemoteSecretResolver secrets = new RemoteSecretResolver(peer.get(), t.callToken())) {
      TestResult result = l.connector().test(t.connection(), secrets, ctx);
      return WireCodec.test(result == null ? TestResult.failure("no-result", "The plugin returned no result.", null)
          : result);
    } catch (RuntimeException | LinkageError | StackOverflowError e) {
      throw cancelledOr(incoming, internal(e));
    }
  }

  private Object classify(Messages.Classify c) throws RpcException {
    Loaded l = loaded(c.generation());
    try {
      Classification result = l.connector().classify(c.call(), c.connection(), c.policy());
      return WireCodec.classification(result == null
          ? Classification.deny("plugin-classify-empty", "The plugin didn't classify the call.", null) : result);
    } catch (RuntimeException | LinkageError | StackOverflowError e) {
      throw internal(e);
    }
  }

  private Object describe(Messages.Describe d) throws RpcException {
    Loaded l = loaded(d.generation());
    try {
      return WireCodec.action(l.connector().describeAction(d.call(), d.connection()));
    } catch (RuntimeException | LinkageError | StackOverflowError e) {
      throw internal(e);
    }
  }

  private Object tools(Messages.Tools t) throws RpcException {
    Loaded l = loaded(t.generation());
    try {
      ObjectNode out = GppJson.object();
      out.set("tools", WireCodec.tools(l.connector().tools(t.endpoint())));
      return out;
    } catch (RuntimeException | LinkageError | StackOverflowError e) {
      throw internal(e);
    }
  }

  private HostExecContext context(String callId, String callToken, String traceId, String traceparent,
      ConnectionConfig cfg, ai.msrv.gaterook.connector.api.Limits limits, long timeoutMs, boolean approved,
      String approvalId, JsonNode preparedState, JsonNode approvedPayload, Loaded l, Messages.EgressGrant grant,
      JsonRpcPeer.Incoming incoming) {
    ExecContext.Egress e = egress == null ? null : egress.egress(grant, cfg);
    String logger = "plugin." + l.generation().substring(0, Math.max(0, l.generation().indexOf('@')));
    return new HostExecContext(peer.get(), incoming, new HostExecContext.Spec(callId, callToken, traceId,
        traceparent, cfg.env(), limits, Duration.ofMillis(Math.max(1, timeoutMs)), approved, approvalId,
        preparedState, approvedPayload, l.dataDir(), e, logger, logLevel.get()), clock, quotas);
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
