package ai.msrv.gaterook.connectors.sql;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.SecretUnavailableException;
import ai.msrv.gaterook.connector.api.ToolCall;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** What the core hands a connector, built by hand for tests: configs, policies, limits, contexts and secrets. */
final class Fixtures {

  static final JsonMapper JSON = JsonMapper.builder().build();

  private Fixtures() {}

  static ObjectNode json(String text) {
    return (ObjectNode) JSON.readTree(text);
  }

  static ObjectNode object() {
    return JsonNodeFactory.instance.objectNode();
  }

  /** A connection of {@code type} with the form's sections; {@code advanced} may be {@code null}. */
  static ConnectionConfig config(String type, Env env, String host, int port, String database, String user,
      ObjectNode advanced, JsonNode metadata) {
    ObjectNode c = object();
    ObjectNode connection = c.putObject("connection").put("host", host).put("port", port).put("sslMode", "disable");
    if (database != null) {
      connection.put("database", database);
    }
    c.putObject("credentials").put("user", user);
    if (advanced != null) {
      c.set("advanced", advanced);
    }
    return new ConnectionConfig("conn-" + type + "-" + env.id(), env.id() + "-db", type, env, 1, c,
        Set.of(ConnectionSettings.PASSWORD), metadata);
  }

  static ConnectionConfig withVersion(ConnectionConfig cfg, long version) {
    return new ConnectionConfig(cfg.id(), cfg.name(), cfg.type(), cfg.env(), version, cfg.config(),
        cfg.secretFields(), cfg.metadata());
  }

  static ConnectionConfig withMetadata(ConnectionConfig cfg, JsonNode metadata) {
    return new ConnectionConfig(cfg.id(), cfg.name(), cfg.type(), cfg.env(), cfg.version(), cfg.config(),
        cfg.secretFields(), metadata);
  }

  /** The merged {@code sql} section of {@code env}'s policy, as the core would pass it. */
  static PolicyView policy(Env env, ObjectNode section) {
    return new PolicyView(env, env.id() + ".sql", section == null ? object() : section, limits(env));
  }

  /** The kit's defaults: dev 1,000 rows and 5 s, prod 100 rows and 3 s. */
  static Limits limits(Env env) {
    boolean prod = env == Env.PROD;
    return new Limits(prod ? 100 : 1_000, prod ? 200 : 1_000, 512 * 1024, 1024 * 1024, 256 * 1024, 64 * 1024,
        8 * 1024, Duration.ofSeconds(prod ? 3 : 5), Duration.ofSeconds(30), Duration.ofSeconds(10),
        Duration.ofHours(prod ? 24 : 168));
  }

  static Limits limits(int maxRows, Duration statementTimeout) {
    Limits l = limits(Env.DEV);
    return new Limits(maxRows, l.maxLines(), l.maxResponseBytes(), l.maxRequestBytes(), l.maxOutputBytes(),
        l.maxStderrBytes(), l.maxCellChars(), statementTimeout, l.commandTimeout(), l.toolTimeout(), l.maxRange());
  }

  static ToolCall call(String tool, ObjectNode args) {
    return new ToolCall("tc_" + Long.toHexString(System.nanoTime()), tool, "dev-db", args, null);
  }

  static ToolCall sql(String tool, String sql) {
    return call(tool, object().put("sql", sql));
  }

  // ── ExecContext ─────────────────────────────────────────────────────────────────────────────────────────────

  /** A call's context: deadline from the tool timeout, cancellable, approved only when built so. */
  static final class Context implements ExecContext {
    final String callId;
    final Env env;
    final Limits limits;
    final Instant deadline;
    final boolean approved;
    final JsonNode payload;
    final Path workDir;
    final List<Runnable> cancels = new ArrayList<>();
    final AtomicBoolean cancelled = new AtomicBoolean();
    final List<String> tainted = new ArrayList<>();

    Context(String callId, Env env, Limits limits, Duration timeout, boolean approved, JsonNode payload,
        Path workDir) {
      this.callId = callId;
      this.env = env;
      this.limits = limits;
      this.deadline = Instant.now().plus(timeout);
      this.approved = approved;
      this.payload = payload;
      this.workDir = workDir;
    }

    static Context of(Env env, Path workDir) {
      return new Context("tc_ctx", env, Fixtures.limits(env), Duration.ofSeconds(10), false, null, workDir);
    }

    Context approved(JsonNode payload) {
      return new Context(callId, env, limits, Duration.between(Instant.now(), deadline), true, payload, workDir);
    }

    Context with(Limits l) {
      return new Context(callId, env, l, Duration.between(Instant.now(), deadline), approved, payload, workDir);
    }

    Context timeout(Duration t) {
      return new Context(callId, env, limits, t, approved, payload, workDir);
    }

    void cancel() {
      cancelled.set(true);
      List<Runnable> copy;
      synchronized (cancels) {
        copy = List.copyOf(cancels);
      }
      copy.forEach(Runnable::run);
    }

    @Override
    public String callId() {
      return callId;
    }

    @Override
    public String traceId() {
      return "0af7651916cd43dd8448eb211c80319c";
    }

    @Override
    public String traceparent() {
      return "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
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
      return limits.toolTimeout();
    }

    @Override
    public Instant deadline() {
      return deadline;
    }

    @Override
    public boolean cancelled() {
      return cancelled.get();
    }

    @Override
    public void onCancel(Runnable action) {
      synchronized (cancels) {
        cancels.add(action);
      }
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
      return approved ? Optional.ofNullable(payload) : Optional.empty();
    }

    @Override
    public System.Logger log() {
      return System.getLogger("sql-test");
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
      throw new UnsupportedOperationException("The SQL connector never uses HTTP egress");
    }

    @Override
    public void taint(CharSequence value) {
      tainted.add(value.toString());
    }
  }

  // ── Secrets ─────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * A connection's secrets: a stored password with a version, or short-lived logins (Vault dynamic) handed out one
   * after the other. Every value handed out is remembered, as the core taints it.
   */
  static final class Secrets implements SecretResolver {
    private final Map<String, char[]> values = new ConcurrentHashMap<>();
    private final Map<String, String> versions = new ConcurrentHashMap<>();
    private volatile Supplier<Login> dynamic;
    final List<String> handedOut = new ArrayList<>();

    static Secrets password(String password) {
      Secrets s = new Secrets();
      s.set(password, "1");
      return s;
    }

    static Secrets none() {
      return new Secrets();
    }

    /** Vault dynamic credentials: each login comes from {@code next}. */
    static Secrets dynamic(Supplier<Login> next) {
      Secrets s = new Secrets();
      s.dynamic = next;
      return s;
    }

    void set(String password, String version) {
      values.put(ConnectionSettings.PASSWORD, password.toCharArray());
      versions.put(ConnectionSettings.PASSWORD, version);
    }

    static Login login(String user, String password, String version, Instant notAfter) {
      return new Login(user, secret(password, version, notAfter), version, notAfter);
    }

    @Override
    public Secret secret(String field) throws SecretUnavailableException {
      char[] v = values.get(field);
      if (v == null) {
        throw new SecretUnavailableException(field + " has no value", null);
      }
      handedOut.add(new String(v));
      return secret(new String(v), versions.get(field), null);
    }

    @Override
    public Login login(String field) throws SecretUnavailableException {
      if (dynamic == null) {
        throw new SecretUnavailableException(field + " isn't a login", null);
      }
      Login l = dynamic.get();
      handedOut.add(new String(l.password().chars()));
      return l;
    }

    @Override
    public boolean isSet(String field) {
      return dynamic != null || values.containsKey(field);
    }

    @Override
    public Meta meta(String field) {
      return dynamic != null ? new Meta("dynamic", null, null) : new Meta("local", versions.get(field), null);
    }

    @Override
    public SshCertificate sshCertificate(String field, PublicKey key, SshCertRequest request) {
      throw new UnsupportedOperationException();
    }

    private static Secret secret(String value, String version, Instant notAfter) {
      char[] chars = value.toCharArray();
      return new Secret() {
        @Override
        public char[] chars() {
          return chars;
        }

        @Override
        public byte[] bytes() {
          return new String(chars).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String unsafeString() {
          return new String(chars);
        }

        @Override
        public String version() {
          return version;
        }

        @Override
        public Optional<Instant> notAfter() {
          return Optional.ofNullable(notAfter);
        }

        @Override
        public void close() {
          Arrays.fill(chars, '\0');
        }

        @Override
        public String toString() {
          return "‹secret›";
        }
      };
    }
  }
}
