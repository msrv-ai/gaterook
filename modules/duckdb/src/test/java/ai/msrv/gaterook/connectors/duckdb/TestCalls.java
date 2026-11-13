package ai.msrv.gaterook.connectors.duckdb;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** What a call of the connector gets from the host, in this JVM: a connection, a context, no secrets. */
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
      return ModuleManifests.write(descriptor, new DuckDbModule().connectors(null));
    } catch (RuntimeException e) {
      throw new IllegalStateException("module.json and the connector don't make a module manifest", e);
    }
  }

  /** A connection to {@code file} that may read {@code folders}. */
  static ConnectionConfig config(Env env, String file, List<String> folders) {
    return config(env, file, folders, null);
  }

  static ConnectionConfig config(Env env, String file, List<String> folders, ObjectNode options) {
    ObjectNode c = Manifests.JSON.createObjectNode();
    c.put("file", file);
    if (!folders.isEmpty()) {
      ArrayNode list = c.putArray("dataFolders");
      folders.forEach(list::add);
    }
    if (options != null) {
      c.set("options", options);
    }
    return new ConnectionConfig("c_duck", "warehouse", "duckdb", env, 1, c, Set.of(), null);
  }

  static ToolCall call(String tool, String json) {
    return new ToolCall("tc_test", tool, "warehouse", (ObjectNode) Manifests.JSON.readTree(json), null);
  }

  static ToolCall sql(String tool, String sql) {
    ObjectNode args = Manifests.JSON.createObjectNode().put("sql", sql);
    return new ToolCall("tc_test", tool, "warehouse", args, null);
  }

  static PolicyView policy(Env env) {
    return new PolicyView(env, env.id() + ".duckdb", null, DEV);
  }

  /** A DuckDB file written by DuckDB itself, outside any GateRook home. */
  static Path database(Path file, String... statements) throws SQLException, IOException {
    Files.createDirectories(file.getParent());
    Properties p = new Properties();
    p.setProperty("jdbc_instance_cache", "false");
    try (Connection c = new org.duckdb.DuckDBDriver().connect("jdbc:duckdb:" + file, p);
         Statement st = c.createStatement()) {
      for (String s : statements) {
        st.execute(s);
      }
    }
    return file;
  }

  /** A module's work folder under a GateRook home, as the host lays it out: {@code <home>/plugin-data/duckdb}. */
  static Path workDir(Path home) throws IOException {
    return Files.createDirectories(home.resolve("plugin-data").resolve("duckdb").resolve("tmp")).getParent();
  }

  /** No secret: a DuckDB file has none. */
  static final class NoSecrets implements SecretResolver {
    @Override
    public Secret secret(String field) throws SecretUnavailableException {
      throw new SecretUnavailableException(field + " is not a secret field of this connection", null);
    }

    @Override
    public Login login(String field) throws SecretUnavailableException {
      throw new SecretUnavailableException("no login", null);
    }

    @Override
    public boolean isSet(String field) {
      return false;
    }

    @Override
    public Meta meta(String field) {
      return new Meta("local", null, null);
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
      return System.getLogger("duckdb-test");
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
        throw new AssertionError("DuckDB never uses the network: " + request.uri());
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
