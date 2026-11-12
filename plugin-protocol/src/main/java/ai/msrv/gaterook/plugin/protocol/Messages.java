package ai.msrv.gaterook.plugin.protocol;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.EndpointView;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.Suggestions;
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

  /** The tools the code of one connector type reports at load. */
  public record TypeTools(String type, List<ToolDefinition> tools) {

    public TypeTools {
      tools = List.copyOf(tools);
    }
  }

  /**
   * {@code plugin/load} result: the tools the package's code reports, and the sandbox it runs in. A plugin has one
   * connector type, {@code type} with {@code tools}. A module may have several: {@code types} lists each with its
   * tools, and {@code type} and {@code tools} are the first one's. {@code types} travels only when it says more than
   * {@code type} and {@code tools} do.
   */
  public record Loaded(String generation, String type, String connectorApi, long loadMs, List<ToolDefinition> tools,
      String sandbox, List<TypeTools> types) {

    public Loaded {
      types = types == null || types.isEmpty() ? List.of(new TypeTools(type, tools)) : List.copyOf(types);
    }

    /** A package with one connector type. */
    public Loaded(String generation, String type, String connectorApi, long loadMs, List<ToolDefinition> tools,
        String sandbox) {
      this(generation, type, connectorApi, loadMs, tools, sandbox, null);
    }

    public ObjectNode write() {
      ObjectNode o = GppJson.object();
      o.put("generation", generation);
      o.put("type", type);
      o.put("connectorApi", connectorApi);
      o.put("loadMs", loadMs);
      o.set("tools", WireCodec.tools(tools));
      o.put("sandbox", sandbox);
      if (types.size() > 1) {
        ArrayNode all = o.putArray("types");
        for (TypeTools t : types) {
          ObjectNode one = all.addObject();
          one.put("type", t.type());
          one.set("tools", WireCodec.tools(t.tools()));
        }
      }
      return o;
    }

    public static Loaded read(JsonNode n) {
      List<TypeTools> types = new ArrayList<>();
      for (JsonNode t : n.path("types")) {
        types.add(new TypeTools(t.path("type").asString(), WireCodec.tools(t.path("tools"))));
      }
      return new Loaded(n.path("generation").asString(), n.path("type").asString(),
          n.path("connectorApi").asString(), n.path("loadMs").asLong(0), WireCodec.tools(n.path("tools")),
          WireCodec.text(n.get("sandbox")), types);
    }
  }

  // ── Calls ───────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * Where a call may go through {@code ExecContext.egress()}: {@code host:port} pairs (the manifest's
   * {@code access.hosts} resolved against the connection), the private ranges the environment's policy allows for the
   * connection, and the core's own ports, which are never reachable. {@code tcp} lists the {@code host:port}
   * destinations of the plugin's own TCP connections ({@code access.tcp} resolved the same way), which the host's TCP
   * gate allows under the same private ranges; it is written only when the plugin declares some.
   */
  public record EgressGrant(List<String> hosts, List<String> allowPrivate, List<Integer> corePorts, List<String> tcp) {

    public static final EgressGrant NONE = new EgressGrant(List.of(), List.of(), List.of(), List.of());

    public EgressGrant {
      hosts = List.copyOf(hosts);
      allowPrivate = List.copyOf(allowPrivate);
      corePorts = List.copyOf(corePorts);
      tcp = tcp == null ? List.of() : List.copyOf(tcp);
    }

    /** A grant without TCP destinations. */
    public EgressGrant(List<String> hosts, List<String> allowPrivate, List<Integer> corePorts) {
      this(hosts, allowPrivate, corePorts, List.of());
    }

    ObjectNode write() {
      ObjectNode o = GppJson.object();
      WireCodec.strings(o.putArray("hosts"), hosts);
      WireCodec.strings(o.putArray("allowPrivate"), allowPrivate);
      ArrayNode ports = o.putArray("corePorts");
      corePorts.forEach(ports::add);
      if (!tcp.isEmpty()) {
        WireCodec.strings(o.putArray("tcp"), tcp);
      }
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
          List.copyOf(ports), WireCodec.strings(n.path("tcp")));
    }
  }

  /**
   * {@code connector/execute} params: the call, its connection, its budget and approval, the token secrets are asked
   * with. {@code timeoutMs} is what remains of the call's deadline when the core sends it.
   */
  public record Execute(String generation, String callToken, ToolCall call, ConnectionConfig connection,
      long timeoutMs, boolean approved, String approvalId, JsonNode preparedState, JsonNode approvedPayload,
      Limits limits, String traceId, String traceparent, EgressGrant egress, boolean wantsProgress,
      String clientLogLevel) {

    /** A call whose client asked for neither progress nor log messages. */
    public Execute(String generation, String callToken, ToolCall call, ConnectionConfig connection, long timeoutMs,
        boolean approved, String approvalId, JsonNode preparedState, JsonNode approvedPayload, Limits limits,
        String traceId, String traceparent, EgressGrant egress) {
      this(generation, callToken, call, connection, timeoutMs, approved, approvalId, preparedState, approvedPayload,
          limits, traceId, traceparent, egress, false, null);
    }

    public ObjectNode write() {
      ObjectNode o = GppJson.object();
      o.put("generation", generation);
      o.put("callId", call.callId());
      o.put("callToken", callToken);
      o.put("tool", call.tool());
      o.set("arguments", call.arguments() == null ? GppJson.object() : call.arguments().deepCopy());
      WireCodec.template(o, call.template());
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
      if (wantsProgress) {
        o.put("wantsProgress", true);
      }
      if (clientLogLevel != null) {
        o.put("clientLogLevel", clientLogLevel);
      }
      return o;
    }

    public static Execute read(JsonNode n) {
      ConnectionConfig cfg = WireCodec.connection(n.path("connection"));
      JsonNode args = n.get("arguments");
      ToolCall call = new ToolCall(n.path("callId").asString(), n.path("tool").asString(), cfg.name(),
          args instanceof ObjectNode obj ? obj.deepCopy() : GppJson.object(), WireCodec.template(n.get("template")));
      return new Execute(n.path("generation").asString(), n.path("callToken").asString(), call, cfg,
          n.path("timeoutMs").asLong(10_000), n.path("approved").asBoolean(false), WireCodec.text(n.get("approvalId")),
          WireCodec.tree(n.get("preparedState")), WireCodec.tree(n.get("approvedPayload")),
          WireCodec.limits(n.path("limits")), WireCodec.text(n.get("traceId")), WireCodec.text(n.get("traceparent")),
          EgressGrant.read(n.get("egress")), n.path("wantsProgress").asBoolean(false),
          WireCodec.text(n.get("clientLogLevel")));
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

  /** {@code connector/suggest} params: those of {@code connector/test} and where the plugin may look. */
  public record Suggest(Test test, Suggestions.Scope scope) {

    public ObjectNode write() {
      ObjectNode o = test.write();
      o.put("scope", scope.name());
      return o;
    }

    /** A scope this side doesn't know reads as {@code LOCAL}: this computer only, nothing signs in. */
    public static Suggest read(JsonNode n) {
      Suggestions.Scope scope = Suggestions.Scope.SERVICE.name().equals(n.path("scope").asString(""))
          ? Suggestions.Scope.SERVICE : Suggestions.Scope.LOCAL;
      return new Suggest(Test.read(n), scope);
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

  /**
   * {@code connector/tools} params: one endpoint's view of the plugin's connections. {@code type} names the connector
   * type of a module that is asked; a plugin has one and the field is absent.
   */
  public record Tools(String generation, String callToken, EndpointView endpoint, String type) {

    public Tools(String generation, String callToken, EndpointView endpoint) {
      this(generation, callToken, endpoint, null);
    }

    public ObjectNode write() {
      ObjectNode o = GppJson.object();
      o.put("generation", generation);
      o.put("callToken", callToken);
      if (type != null) {
        o.put("type", type);
      }
      o.set("endpoint", WireCodec.endpoint(endpoint));
      return o;
    }

    public static Tools read(JsonNode n) {
      return new Tools(n.path("generation").asString(), n.path("callToken").asString(),
          WireCodec.endpoint(n.path("endpoint")), WireCodec.text(n.get("type")));
    }
  }

  // ── Connector modules: the rest of the SPI ──────────────────────────────────────────────────────────────────

  /**
   * {@code connector/prepare} params: what {@code connector/execute} carries before a request exists. No approval
   * yet; the connector may dry-run within {@code timeoutMs}, with the connection's secrets and its egress.
   */
  public record Prepare(String generation, String callToken, ToolCall call, ConnectionConfig connection,
      long timeoutMs, Limits limits, String traceId, String traceparent, EgressGrant egress) {

    public ObjectNode write() {
      ObjectNode o = GppJson.object();
      o.put("generation", generation);
      o.put("callToken", callToken);
      o.set("call", WireCodec.call(call));
      o.set("connection", WireCodec.connection(connection));
      o.put("timeoutMs", timeoutMs);
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

    public static Prepare read(JsonNode n) {
      return new Prepare(n.path("generation").asString(), n.path("callToken").asString(),
          WireCodec.call(n.path("call")), WireCodec.connection(n.path("connection")),
          n.path("timeoutMs").asLong(5_000), WireCodec.limits(n.path("limits")), WireCodec.text(n.get("traceId")),
          WireCodec.text(n.get("traceparent")), EgressGrant.read(n.get("egress")));
    }
  }

  /** {@code connector/describe} params: those of {@code connector/test} and the object to narrow to, if any. */
  public record DescribeConnection(Test test, String object) {

    public ObjectNode write() {
      ObjectNode o = test.write();
      if (object != null) {
        o.put("object", object);
      }
      return o;
    }

    public static DescribeConnection read(JsonNode n) {
      return new DescribeConnection(Test.read(n), WireCodec.text(n.get("object")));
    }
  }

  /** {@code connector/supportsTool} params: the tools asked about for one connection; no secrets, no token. */
  public record SupportsTool(String generation, ConnectionConfig connection, List<String> tools) {

    public SupportsTool {
      tools = List.copyOf(tools);
    }

    public ObjectNode write() {
      ObjectNode o = GppJson.object();
      o.put("generation", generation);
      o.set("connection", WireCodec.connection(connection));
      WireCodec.strings(o.putArray("tools"), tools);
      return o;
    }

    public static SupportsTool read(JsonNode n) {
      return new SupportsTool(n.path("generation").asString(), WireCodec.connection(n.path("connection")),
          WireCodec.strings(n.path("tools")));
    }

    /** The answer: the tools of the request the connection supports. */
    public static ObjectNode result(List<String> supported) {
      ObjectNode o = GppJson.object();
      WireCodec.strings(o.putArray("supported"), supported);
      return o;
    }

    /** Reads the answer. */
    public static List<String> supported(JsonNode result) {
      return WireCodec.strings(result.path("supported"));
    }
  }

  /**
   * {@code template/compile} and {@code template/preview} params: the template kind of the connection's type, the
   * definition, and for a preview the arguments. No secrets, no token.
   */
  public record Template(String generation, String kind, JsonNode definition, ObjectNode arguments,
      ConnectionConfig runsOn) {

    public ObjectNode write() {
      ObjectNode o = GppJson.object();
      o.put("generation", generation);
      o.put("kind", kind);
      o.set("definition", definition == null ? GppJson.MAPPER.nullNode() : definition.deepCopy());
      if (arguments != null) {
        o.set("arguments", arguments.deepCopy());
      }
      o.set("runsOn", WireCodec.connection(runsOn));
      return o;
    }

    public static Template read(JsonNode n) {
      return new Template(n.path("generation").asString(), n.path("kind").asString(),
          WireCodec.tree(n.get("definition")), n.get("arguments") instanceof ObjectNode a ? a.deepCopy() : null,
          WireCodec.connection(n.path("runsOn")));
    }
  }

  /** {@code quota/acquire} params: a permit of a quota every connector of the same account shares. */
  public static ObjectNode quota(String callToken, String key, int permitsPerMinute) {
    ObjectNode o = GppJson.object();
    o.put("callToken", callToken);
    o.put("key", key);
    o.put("permitsPerMinute", permitsPerMinute);
    return o;
  }

  /** {@code mask/preview} params: a tree to mask as the call's answer will be. */
  public static ObjectNode mask(String callToken, JsonNode tree) {
    ObjectNode o = GppJson.object();
    o.put("callToken", callToken);
    o.set("tree", tree == null ? GppJson.MAPPER.nullNode() : tree.deepCopy());
    return o;
  }

  /** {@code call/progress}. */
  public static ObjectNode progress(String callToken, double progress, Double total, String message) {
    ObjectNode o = GppJson.object();
    o.put("callToken", callToken);
    o.put("progress", progress);
    if (total != null) {
      o.put("total", total);
    }
    if (message != null) {
      o.put("message", message);
    }
    return o;
  }

  /** {@code call/clientLog}. */
  public static ObjectNode clientLog(String callToken, String level, String logger, JsonNode data) {
    ObjectNode o = GppJson.object();
    o.put("callToken", callToken);
    o.put("level", level);
    if (logger != null) {
      o.put("logger", logger);
    }
    o.set("data", data == null ? GppJson.MAPPER.nullNode() : data.deepCopy());
    return o;
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
