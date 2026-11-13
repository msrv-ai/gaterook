package ai.msrv.gaterook.connectors.h2;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.SecretUnavailableException;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.plugin.protocol.ModuleManifests;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.PublicKey;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** What a call of the connector gets from the host, in this JVM: a connection, a context, its password. */
final class TestCalls {

  static final Limits DEV = new Limits(1000, 1000, 524_288, 1_048_576, 262_144, 65_536, 8192, Duration.ofSeconds(5),
      Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofHours(168));

  private TestCalls() {}

  /** The connector's own definition, as its JAR holds it. */
  static JsonNode definition() {
    return Manifests.own();
  }

  /** The module's manifest as its package holds it: the descriptor, and what the connector says of itself. */
  static JsonNode manifest() {
    try {
      JsonNode descriptor = Manifests.JSON.readTree(Path.of("module.json").toFile());
      return ModuleManifests.write(descriptor, new H2Module().connectors(null));
    } catch (RuntimeException e) {
      throw new IllegalStateException("module.json and the connector don't make a module manifest", e);
    }
  }

  /** A connection to {@code database} of the server on this computer at {@code port}, as {@code user}. */
  static ConnectionConfig config(Env env, int port, String database, String user) {
    return config(env, "127.0.0.1", port, database, user, null);
  }

  static ConnectionConfig config(Env env, String host, int port, String database, String user, ObjectNode options) {
    ObjectNode c = Manifests.JSON.createObjectNode();
    c.put("host", host);
    c.put("port", port);
    c.put("database", database);
    c.put("user", user);
    if (options != null) {
      c.set("options", options);
    }
    return new ConnectionConfig("c_h2", "warehouse", "h2", env, 1, c, Set.of("/password"), null);
  }

  static ToolCall call(String tool, String json) {
    return new ToolCall("tc_test", tool, "warehouse", (ObjectNode) Manifests.JSON.readTree(json), null);
  }

  static ToolCall sql(String tool, String sql) {
    ObjectNode args = Manifests.JSON.createObjectNode().put("sql", sql);
    return new ToolCall("tc_test", tool, "warehouse", args, null);
  }

  static PolicyView policy(Env env) {
    return new PolicyView(env, env.id() + ".h2", null, DEV);
  }

  /** Runs {@code statements} on {@code url} as {@code user}, the way a person sets a database up. */
  static void run(String url, String user, String password, String... statements) throws SQLException {
    try (Connection c = DriverManager.getConnection(url, user, password); Statement st = c.createStatement()) {
      for (String s : statements) {
        st.execute(s);
      }
    }
  }

  /** The password of the connection, as the core hands it out for one call. */
  static final class Secrets implements SecretResolver {
    private final Map<String, String> values;

    Secrets(Map<String, String> values) {
      this.values = Map.copyOf(values);
    }

    static Secrets password(String password) {
      return new Secrets(Map.of("/password", password));
    }

    @Override
    public Secret secret(String field) throws SecretUnavailableException {
      String v = values.get(field);
      if (v == null) {
        throw new SecretUnavailableException(field + " is not a secret field of this connection", null);
      }
      return new Secret() {
        @Override
        public char[] chars() {
          return v.toCharArray();
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
          return "1";
        }

        @Override
        public Optional<Instant> notAfter() {
          return Optional.empty();
        }

        @Override
        public void close() {}
      };
    }

    @Override
    public Login login(String field) throws SecretUnavailableException {
      throw new SecretUnavailableException("no login", null);
    }

    @Override
    public boolean isSet(String field) {
      return values.containsKey(field);
    }

    @Override
    public Meta meta(String field) {
      return new Meta("local", "1", null);
    }

    @Override
    public SshCertificate sshCertificate(String field, PublicKey key, SshCertRequest request)
        throws SecretUnavailableException {
      throw new SecretUnavailableException("no Vault", null);
    }
  }

  /** A call's context: approved or not, its limits, its work folder, the cancellations it was asked to run. */
  static class Context implements ExecContext {
    final boolean approved;
    final JsonNode payload;
    final Path workDir;
    final Env env;
    final Limits limits;
    final Duration timeout;
    final Instant deadline;
    final List<Runnable> onCancel = new CopyOnWriteArrayList<>();
    volatile boolean cancelled;

    Context(Path workDir, boolean approved) {
      this(workDir, approved, null, Env.DEV, DEV, Duration.ofSeconds(10));
    }

    Context(Path workDir, boolean approved, JsonNode payload, Env env, Limits limits, Duration timeout) {
      this.workDir = workDir;
      this.approved = approved;
      this.payload = payload;
      this.env = env;
      this.limits = limits;
      this.timeout = timeout;
      this.deadline = Instant.now().plus(timeout);
    }

    /** Runs the call's cancellation callbacks, as the host does on {@code $/cancel}. */
    void cancel() {
      cancelled = true;
      onCancel.forEach(Runnable::run);
    }

    @Override
    public String callId() {
      return "tc_test";
    }

    @Override
    public String traceId() {
      return null;
    }

    @Override
    public String traceparent() {
      return null;
    }

    @Override
    public Env env() {
      return env;
    }

    @Override
    public Limits limits() {
      return limits;
    }

    @Override
    public Duration timeout() {
      return timeout;
    }

    @Override
    public Instant deadline() {
      return deadline;
    }

    @Override
    public boolean cancelled() {
      return cancelled;
    }

    @Override
    public void onCancel(Runnable action) {
      onCancel.add(action);
    }

    @Override
    public boolean approved() {
      return approved;
    }

    @Override
    public Optional<String> approvalId() {
      return approved ? Optional.of("ap_test") : Optional.empty();
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
      return System.getLogger("h2-test");
    }

    @Override
    public Clock clock() {
      return Clock.systemUTC();
    }

    @Override
    public HandleCodec handles() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean acquire(String remoteQuotaKey, int permitsPerMinute) {
      return true;
    }

    @Override
    public Path workDir() {
      return workDir;
    }

    @Override
    public Egress egress() {
      return (request, maxBytes) -> {
        throw new AssertionError("The H2 connector never sends HTTP: " + request.uri());
      };
    }

    @Override
    public void taint(CharSequence value) {}
  }

  /** Limits like {@link #DEV} with another statement timeout and row limit. */
  static Limits limits(int maxRows, Duration statementTimeout) {
    return new Limits(maxRows, 1000, 524_288, 1_048_576, 262_144, 65_536, 8192, statementTimeout,
        Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofHours(168));
  }

  /** Limits like {@link #DEV} with a smaller response and cell size. */
  static Limits small(long maxResponseBytes, int maxCellChars) {
    return new Limits(1000, 1000, maxResponseBytes, 1_048_576, 262_144, 65_536, maxCellChars, Duration.ofSeconds(5),
        Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofHours(168));
  }
}
