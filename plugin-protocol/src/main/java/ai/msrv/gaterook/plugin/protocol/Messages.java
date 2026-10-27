package ai.msrv.gaterook.plugin.protocol;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.EndpointView;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The params and results of GPP/1 (report 16 §4.4) as records, each with its JSON form: what the core writes, the host
 * reads, and the reverse, from one definition. The golden files in {@code src/test/resources/gpp/} pin every one.
 */
public final class Messages {

  private Messages() {}

  // ── host/hello ──────────────────────────────────────────────────────────────────────────────────────────────

  /** {@code host/hello} params: the core's protocol, version and SPI version. */
  public record Hello(int protocol, String coreVersion, String connectorApi, boolean nativeCore, int maxFrameBytes,
      int maxInFlight, String logLevel) {

    public ObjectNode write() {
      ObjectNode o = GppJson.object();
      o.put("protocol", protocol);
      ObjectNode core = o.putObject("core");
      core.put("version", coreVersion);
      core.put("connectorApi", connectorApi);
      core.put("native", nativeCore);
      ObjectNode limits = o.putObject("limits");
      limits.put("maxFrameBytes", maxFrameBytes);
      limits.put("maxInFlight", maxInFlight);
      o.put("logLevel", logLevel);
      return o;
    }

    public static Hello read(JsonNode n) {
      return new Hello(n.path("protocol").asInt(0), n.path("core").path("version").asString(),
          n.path("core").path("connectorApi").asString(), n.path("core").path("native").asBoolean(false),
          n.path("limits").path("maxFrameBytes").asInt(Gpp.MAX_FRAME_BYTES),
          n.path("limits").path("maxInFlight").asInt(8), n.path("logLevel").asString("info"));
    }
  }

  /** {@code host/hello} result. */
  public record HelloResult(int protocol, String hostVersion, String connectorApi, long pid, String javaVersion,
      boolean aotCache, List<String> capabilities, long readyMs) {

    public ObjectNode write() {
      ObjectNode o = GppJson.object();
      o.put("protocol", protocol);
      ObjectNode host = o.putObject("host");
      host.put("version", hostVersion);
      host.put("connectorApi", connectorApi);
      o.put("pid", pid);
      ObjectNode java = o.putObject("java");
      java.put("version", javaVersion);
      java.put("aotCache", aotCache);
      WireCodec.strings(o.putArray("capabilities"), capabilities);
      o.put("readyMs", readyMs);
      return o;
    }

    public static HelloResult read(JsonNode n) {
      return new HelloResult(n.path("protocol").asInt(0), n.path("host").path("version").asString(),
          n.path("host").path("connectorApi").asString(), n.path("pid").asLong(0),
          n.path("java").path("version").asString(null), n.path("java").path("aotCache").asBoolean(false),
          WireCodec.strings(n.path("capabilities")), n.path("readyMs").asLong(0));
    }
  }

  // ── plugin/load ─────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * The OS sandbox the core started the host in (ARCHITECTURE.md §7.5): read-only and writable paths besides the
   * runtime it needs itself. The sandbox is set up before the host's JVM starts (Landlock confines threads, so a host
   * can't confine itself); {@code enabled} says the host must be running in one, and a host that wasn't started in a
   * sandbox refuses the load. {@code false} only where the person's system has no sandbox.
   */
  public record Sandbox(boolean enabled, List<String> readOnly, List<String> readWrite) {

    ObjectNode write() {
      ObjectNode o = GppJson.object();
      o.put("enabled", enabled);
      WireCodec.strings(o.putArray("readOnly"), readOnly);
      WireCodec.strings(o.putArray("readWrite"), readWrite);
      return o;
    }

    static Sandbox read(JsonNode n) {
      return new Sandbox(n.path("enabled").asBoolean(false), WireCodec.strings(n.path("readOnly")),
          WireCodec.strings(n.path("readWrite")));
    }
  }

  /** {@code plugin/load} params: the generation, the JAR and its SHA-256 (checked again by the host). */
  public record Load(String generation, int instance, String jar, String sha256, String dataDir, Sandbox sandbox) {

    public ObjectNode write() {
      ObjectNode o = GppJson.object();
      o.put("generation", generation);
      o.put("instance", instance);
      o.put("jar", jar);
      o.put("sha256", sha256);
      o.put("dataDir", dataDir);
      o.set("sandbox", sandbox.write());
      return o;
    }

    public static Load read(JsonNode n) {
      return new Load(n.path("generation").asString(), n.path("instance").asInt(1), n.path("jar").asString(),
          n.path("sha256").asString(), n.path("dataDir").asString(), Sandbox.read(n.path("sandbox")));
    }
  }

  /** {@code plugin/load} result: the tools the plugin's code reports, and the sandbox it runs in. */
  public record Loaded(String generation, String type, String connectorApi, long loadMs, List<ToolDefinition> tools,
      String sandbox) {

    public ObjectNode write() {
      ObjectNode o = GppJson.object();
      o.put("generation", generation);
      o.put("type", type);
      o.put("connectorApi", connectorApi);
      o.put("loadMs", loadMs);
      o.set("tools", WireCodec.tools(tools));
      o.put("sandbox", sandbox);
      return o;
    }

    public static Loaded read(JsonNode n) {
      return new Loaded(n.path("generation").asString(), n.path("type").asString(),
          n.path("connectorApi").asString(), n.path("loadMs").asLong(0), WireCodec.tools(n.path("tools")),
          WireCodec.text(n.get("sandbox")));
    }
  }

  // ── Calls ───────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * Where a call may go through {@code ExecContext.egress()}: {@code host:port} pairs (the manifest's
   * {@code access.hosts} resolved against the connection), the private ranges the environment's policy allows for the
   * connection, and the core's own ports, which are never reachable.
   */
  public record EgressGrant(List<String> hosts, List<String> allowPrivate, List<Integer> corePorts) {

    public static final EgressGrant NONE = new EgressGrant(List.of(), List.of(), List.of());

    ObjectNode write() {
      ObjectNode o = GppJson.object();
      WireCodec.strings(o.putArray("hosts"), hosts);
      WireCodec.strings(o.putArray("allowPrivate"), allowPrivate);
      ArrayNode ports = o.putArray("corePorts");
      corePorts.forEach(ports::add);
      return o;
    }

    static EgressGrant read(JsonNode n) {
      if (n == null || !n.isObject()) {
        return NONE;
      }
      List<Integer> ports = new ArrayList<>();
      for (JsonNode p : n.path("corePorts")) {
        ports.add(p.asInt());
      }
      return new EgressGrant(WireCodec.strings(n.path("hosts")), WireCodec.strings(n.path("allowPrivate")),
          List.copyOf(ports));
    }
  }

  /**
   * {@code connector/execute} params: the call, its connection, its budget and approval, the token secrets are asked
   * with. {@code timeoutMs} is what remains of the call's deadline when the core sends it.
   */
  public record Execute(String generation, String callToken, ToolCall call, ConnectionConfig connection,
      long timeoutMs, boolean approved, String approvalId, JsonNode preparedState, JsonNode approvedPayload,
      Limits limits, String traceId, String traceparent, EgressGrant egress) {

    public ObjectNode write() {
      ObjectNode o = GppJson.object();
      o.put("generation", generation);
      o.put("callId", call.callId());
      o.put("callToken", callToken);
      o.put("tool", call.tool());
      o.set("arguments", call.arguments() == null ? GppJson.object() : call.arguments().deepCopy());
      o.set("connection", WireCodec.connection(connection));
      o.put("timeoutMs", timeoutMs);
      o.put("approved", approved);
      if (approvalId != null) {
        o.put("approvalId", approvalId);
      }
      if (preparedState != null) {
        o.set("preparedState", preparedState.deepCopy());
      }
      if (approvedPayload != null) {
        o.set("approvedPayload", approvedPayload.deepCopy());
      }
      o.set("limits", WireCodec.limits(limits));
      if (traceId != null) {
        o.put("traceId", traceId);
      }
      if (traceparent != null) {
        o.put("traceparent", traceparent);
      }
      o.set("egress", egress.write());
      return o;
    }

    public static Execute read(JsonNode n) {
      ConnectionConfig cfg = WireCodec.connection(n.path("connection"));
      JsonNode args = n.get("arguments");
      ToolCall call = new ToolCall(n.path("callId").asString(), n.path("tool").asString(), cfg.name(),
          args instanceof ObjectNode obj ? obj.deepCopy() : GppJson.object(), null);
      return new Execute(n.path("generation").asString(), n.path("callToken").asString(), call, cfg,
          n.path("timeoutMs").asLong(10_000), n.path("approved").asBoolean(false), WireCodec.text(n.get("approvalId")),
          WireCodec.tree(n.get("preparedState")), WireCodec.tree(n.get("approvedPayload")),
          WireCodec.limits(n.path("limits")), WireCodec.text(n.get("traceId")), WireCodec.text(n.get("traceparent")),
          EgressGrant.read(n.get("egress")));
    }
  }

  /** {@code connector/test} params. */
  public record Test(String generation, String callId, String callToken, ConnectionConfig connection, long timeoutMs,
      Limits limits, EgressGrant egress) {

    public ObjectNode write() {
      ObjectNode o = GppJson.object();
      o.put("generation", generation);
      o.put("callId", callId);
      o.put("callToken", callToken);
      o.set("connection", WireCodec.connection(connection));
      o.put("timeoutMs", timeoutMs);
      o.set("limits", WireCodec.limits(limits));
      o.set("egress", egress.write());
      return o;
    }

    public static Test read(JsonNode n) {
      return new Test(n.path("generation").asString(), n.path("callId").asString(), n.path("callToken").asString(),
          WireCodec.connection(n.path("connection")), n.path("timeoutMs").asLong(10_000),
          WireCodec.limits(n.path("limits")), EgressGrant.read(n.get("egress")));
    }
  }

  /** {@code connector/classify} params (no secrets, no token). */
  public record Classify(String generation, ToolCall call, ConnectionConfig connection, PolicyView policy) {

    public ObjectNode write() {
      ObjectNode o = GppJson.object();
      o.put("generation", generation);
      o.set("call", WireCodec.call(call));
      o.set("connection", WireCodec.connection(connection));
      o.set("policy", WireCodec.policy(policy));
      return o;
    }

    public static Classify read(JsonNode n) {
      return new Classify(n.path("generation").asString(), WireCodec.call(n.path("call")),
          WireCodec.connection(n.path("connection")), WireCodec.policy(n.path("policy")));
    }
  }

  /** {@code connector/describeAction} params (no secrets, no token). */
  public record Describe(String generation, ToolCall call, ConnectionConfig connection) {

    public ObjectNode write() {
      ObjectNode o = GppJson.object();
      o.put("generation", generation);
      o.set("call", WireCodec.call(call));
      o.set("connection", WireCodec.connection(connection));
      return o;
    }

    public static Describe read(JsonNode n) {
      return new Describe(n.path("generation").asString(), WireCodec.call(n.path("call")),
          WireCodec.connection(n.path("connection")));
    }
  }

  /** {@code connector/tools} params: one endpoint's view of the plugin's connections. */
  public record Tools(String generation, String callToken, EndpointView endpoint) {

    public ObjectNode write() {
      ObjectNode o = GppJson.object();
      o.put("generation", generation);
      o.put("callToken", callToken);
      o.set("endpoint", WireCodec.endpoint(endpoint));
      return o;
    }

    public static Tools read(JsonNode n) {
      return new Tools(n.path("generation").asString(), n.path("callToken").asString(),
          WireCodec.endpoint(n.path("endpoint")));
    }
  }

  // ── Small ones ──────────────────────────────────────────────────────────────────────────────────────────────

  /** {@code $/cancel}. */
  public static ObjectNode cancel(long id, String reason) {
    ObjectNode o = GppJson.object();
    o.put("id", id);
    o.put("reason", reason);
    return o;
  }

  /** {@code secrets/get}, {@code secrets/login}, {@code secrets/meta}. */
  public static ObjectNode secretField(String callToken, String field) {
    ObjectNode o = GppJson.object();
    o.put("callToken", callToken);
    o.put("field", field);
    return o;
  }

  /** {@code taint/add}. */
  public static ObjectNode taint(String callToken, String value) {
    ObjectNode o = GppJson.object();
    o.put("callToken", callToken);
    o.put("value", value);
    return o;
  }

  /** {@code log}. */
  public static ObjectNode log(String level, String logger, String callId, String message) {
    ObjectNode o = GppJson.object();
    o.put("level", level);
    o.put("logger", logger);
    if (callId != null) {
      o.put("callId", callId);
    }
    o.put("message", message);
    return o;
  }

  /** {@code connection/invalidate}. */
  public static ObjectNode invalidate(String connectionId) {
    ObjectNode o = GppJson.object();
    o.put("connectionId", connectionId);
    return o;
  }
}
