package ai.msrv.gaterook.connectors.access;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.SecretUnavailableException;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.plugin.protocol.ModuleManifests;
import io.github.spannm.jackcess.Column;
import io.github.spannm.jackcess.ColumnBuilder;
import io.github.spannm.jackcess.DataType;
import io.github.spannm.jackcess.Database;
import io.github.spannm.jackcess.DatabaseBuilder;
import io.github.spannm.jackcess.Table;
import io.github.spannm.jackcess.TableBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.JsonNode;
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
      return ModuleManifests.write(descriptor, new AccessModule().connectors(null));
    } catch (RuntimeException e) {
      throw new IllegalStateException("module.json and the connector don't make a module manifest", e);
    }
  }

  /** A connection to {@code file}. */
  static ConnectionConfig config(Env env, String file) {
    return config(env, file, null);
  }

  static ConnectionConfig config(Env env, String file, ObjectNode options) {
    ObjectNode c = Manifests.JSON.createObjectNode();
    c.put("file", file);
    if (options != null) {
      c.set("options", options);
    }
    return new ConnectionConfig("c_access", "orders", "msaccess", env, 1, c, Set.of(), null);
  }

  static ToolCall call(String tool, String json) {
    return new ToolCall("tc_test", tool, "orders", (ObjectNode) Manifests.JSON.readTree(json), null);
  }

  static ToolCall sql(String tool, String sql) {
    ObjectNode args = Manifests.JSON.createObjectNode().put("sql", sql);
    return new ToolCall("tc_test", tool, "orders", args, null);
  }

  static PolicyView policy(Env env) {
    return new PolicyView(env, env.id() + ".msaccess", null, DEV);
  }

  /** An Access 2010 file with an Orders table of every common type, two rows, and a Lines table. */
  static Path database(Path file) throws IOException {
    Files.createDirectories(file.getParent());
    try (Database db = DatabaseBuilder.create(Database.FileFormat.V2010, file.toFile())) {
      Table t = new TableBuilder("Orders").addColumn(new ColumnBuilder("ID", DataType.LONG).withAutoNumber(true))
          .addColumn(new ColumnBuilder("Note", DataType.TEXT).withLength(40))
          .addColumn(new ColumnBuilder("Total", DataType.MONEY))
          .addColumn(new ColumnBuilder("Paid", DataType.BOOLEAN))
          .addColumn(new ColumnBuilder("OrderedAt", DataType.SHORT_DATE_TIME))
          .addColumn(new ColumnBuilder("Memo", DataType.MEMO))
          .addColumn(new ColumnBuilder("Picture", DataType.OLE))
          .addColumn(new ColumnBuilder("Ratio", DataType.DOUBLE))
          .withPrimaryKey("ID").toTable(db);
      t.addRow(Column.AUTO_NUMBER, "first", new java.math.BigDecimal("12.50"), true,
          java.time.LocalDateTime.of(2026, 11, 12, 9, 30), "long text", new byte[] {1, 2}, 0.25);
      t.addRow(Column.AUTO_NUMBER, "second", new java.math.BigDecimal("99.00"), false, null, null, null, null);
      new TableBuilder("Order Lines").addColumn(new ColumnBuilder("OrderID", DataType.LONG))
          .addColumn(new ColumnBuilder("N", DataType.LONG)).toTable(db);
    }
    return file;
  }

  static String sha256(Path file) throws Exception {
    return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
        .digest(Files.readAllBytes(file)));
  }

  /** A module's work folder under a GateRook home, as the host lays it out: {@code <home>/plugin-data/msaccess}. */
  static Path workDir(Path home) throws IOException {
    return Files.createDirectories(home.resolve("plugin-data").resolve("msaccess").resolve("tmp")).getParent();
  }

  /** No secret: an Access file here has none. */
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
      return System.getLogger("access-test");
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
        throw new AssertionError("The Access connector never uses the network: " + request.uri());
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
