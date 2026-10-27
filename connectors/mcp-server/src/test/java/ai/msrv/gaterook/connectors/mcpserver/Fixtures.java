package ai.msrv.gaterook.connectors.mcpserver;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.SecretUnavailableException;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connectors.support.mcp.McpServerRuntime;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Test doubles of the core's side of the SPI for the MCP server runtime: config, secrets, call context, host. */
final class Fixtures {

  private Fixtures() {}

  static ObjectNode object(String json) {
    return (ObjectNode) Texts.JSON.readTree(json);
  }

  /** A stored (or draft) connection of type {@code mcp-server}; every {@code secretRef} of env and headers is set. */
  static ConnectionConfig cfg(String id, String name, Env env, long version, JsonNode config) {
    Set<String> secrets = new LinkedHashSet<>();
    for (String list : List.of("env", "headers")) {
      for (int i = 0; i < config.path(list).size(); i++) {
        secrets.add("/" + list + "/" + i + "/secretRef");
      }
    }
    return new ConnectionConfig(id, name, McpServerConnector.TYPE, env, version, config, secrets, null);
  }

  static PolicyView policy() {
    return new PolicyView(Env.DEV, "dev.mcp-server", null, limits(Duration.ofSeconds(30)));
  }

  static Limits limits(Duration toolTimeout) {
    return new Limits(1_000, 1_000, 512 * 1024, 256 * 1024, 256 * 1024, 64 * 1024, 8 * 1024, Duration.ofSeconds(5),
        Duration.ofSeconds(30), toolTimeout, Duration.ofHours(168));
  }

  static ToolCall call(String tool, String arguments) {
    return new ToolCall("tc_test", tool, "fake-mcp", object(arguments), null);
  }

  /** Values by field; a field's version changes with {@link #rotate}. */
  static final class Secrets implements SecretResolver {
    final Map<String, String> values = new HashMap<>();
    final Map<String, Integer> versions = new HashMap<>();

    Secrets with(String field, String value) {
      values.put(field, value);
      versions.merge(field, 1, (a, b) -> a);
      return this;
    }

    void rotate(String field, String value) {
      values.put(field, value);
      versions.merge(field, 1, Integer::sum);
    }

    @Override
    public Secret secret(String field) throws SecretUnavailableException {
      String v = values.get(field);
      if (v == null) {
        throw new SecretUnavailableException(field + " has no value.", null);
      }
      char[] chars = v.toCharArray();
      return new Secret() {
        @Override
        public char[] chars() {
          return chars;
        }

        @Override
        public byte[] bytes() {
          return v.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String unsafeString() {
          return v;
        }

        @Override
        public String version() {
          return String.valueOf(versions.get(field));
        }

        @Override
        public Optional<Instant> notAfter() {
          return Optional.empty();
        }

        @Override
        public void close() {
          java.util.Arrays.fill(chars, '\0');
        }
      };
    }

    @Override
    public Login login(String field) throws SecretUnavailableException {
      throw new SecretUnavailableException("No login " + field, null);
    }

    @Override
    public boolean isSet(String field) {
      return values.containsKey(field);
    }

    @Override
    public Meta meta(String field) {
      return new Meta("local", String.valueOf(versions.get(field)), null);
    }

    @Override
    public SshCertificate sshCertificate(String field, PublicKey key, SshCertRequest request)
        throws SecretUnavailableException {
      throw new SecretUnavailableException("No SSH CA", null);
    }
  }

  /** The call context: a deadline, the taints a call registered, approval with a payload. */
  static final class Context implements ExecContext {
    final Clock clock = Clock.systemUTC();
    final Instant deadline;
    final Limits limits;
    final boolean approved;
    final JsonNode payload;
    final Egress egress;
    final List<String> taints = new CopyOnWriteArrayList<>();

    Context(Duration timeout, boolean approved, JsonNode payload, Egress egress) {
      this.limits = Fixtures.limits(timeout);
      this.deadline = clock.instant().plus(timeout);
      this.approved = approved;
      this.payload = payload;
      this.egress = egress;
    }

    static Context of(Duration timeout) {
      return new Context(timeout, false, null, null);
    }

    static Context approved(JsonNode payload) {
      return new Context(Duration.ofSeconds(30), true, payload, null);
    }

    @Override
    public String callId() {
      return "tc_test";
    }

    @Override
    public String traceId() {
      return "4f2a9c71e8d04b5a9c3e2b1f0a6d8e81";
    }

    @Override
    public String traceparent() {
      return null;
    }

    @Override
    public Env env() {
      return Env.DEV;
    }

    @Override
    public Limits limits() {
      return limits;
    }

    @Override
    public Duration timeout() {
      return limits.toolTimeout();
    }

    @Override
    public Instant deadline() {
      return deadline;
    }

    @Override
    public boolean cancelled() {
      return false;
    }

    @Override
    public void onCancel(Runnable action) {}

    @Override
    public boolean approved() {
      return approved;
    }

    @Override
    public Optional<String> approvalId() {
      return approved ? Optional.of("ap_test00000001") : Optional.empty();
    }

    @Override
    public Optional<JsonNode> preparedState() {
      return Optional.empty();
    }

    @Override
    public Optional<JsonNode> approvedPayload() {
      return Optional.ofNullable(payload);
    }

    @Override
    public System.Logger log() {
      return System.getLogger("ai.msrv.gaterook.connectors.mcpserver.test");
    }

    @Override
    public Clock clock() {
      return clock;
    }

    @Override
    public HandleCodec handles() {
      return null;
    }

    @Override
    public boolean acquire(String remoteQuotaKey, int permitsPerMinute) {
      return true;
    }

    @Override
    public Path workDir() {
      return Path.of("target");
    }

    @Override
    public Egress egress() {
      return egress;
    }

    @Override
    public void taint(CharSequence value) {
      taints.add(value.toString());
    }
  }

  /** What the core does as the runtime's host, recorded. */
  static final class Host implements McpServerRuntime.Host {
    final Path home;
    final List<McpServerRuntime.Handshake> handshakes = new CopyOnWriteArrayList<>();
    final List<McpServerRuntime.ProcessView> states = new CopyOnWriteArrayList<>();
    final List<String> wanted = new CopyOnWriteArrayList<>();
    final Set<String> reserved = new LinkedHashSet<>(List.of("sql_query", "approval_status"));

    Host(Path home) {
      this.home = home;
    }

    @Override
    public Path home() {
      return home;
    }

    @Override
    public Optional<String> reservedProblem(String toolName) {
      return reserved.contains(toolName) ? Optional.of(toolName + " is a name of GateRook's own tools")
          : Optional.empty();
    }

    @Override
    public void handshake(String connectionId, McpServerRuntime.Handshake handshake) {
      handshakes.add(handshake);
    }

    @Override
    public void state(String connectionId, McpServerRuntime.ProcessView process) {
      states.add(process);
    }

    @Override
    public void wanted(String connectionId) {
      wanted.add(connectionId);
    }

    List<McpServerRuntime.State> stateNames() {
      List<McpServerRuntime.State> out = new ArrayList<>();
      states.forEach(s -> out.add(s.state()));
      return out;
    }
  }
}
