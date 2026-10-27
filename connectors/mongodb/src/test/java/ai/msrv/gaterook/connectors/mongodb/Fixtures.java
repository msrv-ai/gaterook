package ai.msrv.gaterook.connectors.mongodb;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.SecretUnavailableException;
import ai.msrv.gaterook.connector.api.ToolCall;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Test doubles of the SPI around the connector: configs, secrets, the call context. */
final class Fixtures {

  static final JsonMapper JSON = JsonMapper.builder().build();
  private static final AtomicLong VERSIONS = new AtomicLong(1);

  private Fixtures() {}

  static ObjectNode json(String text) {
    return (ObjectNode) JSON.readTree(text);
  }

  static ConnectionConfig config(Env env, String json) {
    return config("orders-mongo", env, json, 1);
  }

  static ConnectionConfig config(String id, Env env, String json, long version) {
    return new ConnectionConfig(id, id, MongoConnector.TYPE, env, version, JSON.readTree(json),
        Set.of(MongoSettings.PASSWORD), null);
  }

  static ConnectionConfig uri(Env env, String uri) {
    return config(env, "{\"connection\":{\"uri\":\"" + uri + "\"}}");
  }

  static long nextVersion() {
    return VERSIONS.incrementAndGet();
  }

  static ToolCall call(String tool, String arguments) {
    return new ToolCall("tc_test", tool, "orders-mongo", json(arguments), null);
  }

  static PolicyView policy(Env env) {
    return new PolicyView(env, env.id() + ".mongodb", null, Context.limits(env));
  }

  /** A password for {@code /credentials/password}, or none. */
  static final class Secrets implements SecretResolver {
    private final String password;
    private final String version;
    final List<String> handedOut = new ArrayList<>();

    Secrets(String password, String version) {
      this.password = password;
      this.version = version;
    }

    static Secrets password(String password) {
      return new Secrets(password, "v1");
    }

    static Secrets none() {
      return new Secrets(null, null);
    }

    @Override
    public Secret secret(String field) throws SecretUnavailableException {
      if (password == null || !MongoSettings.PASSWORD.equals(field)) {
        throw new SecretUnavailableException(field + " isn't set", null);
      }
      handedOut.add(field);
      char[] chars = password.toCharArray();
      return new Secret() {
        @Override
        public char[] chars() {
          return chars;
        }

        @Override
        public byte[] bytes() {
          return password.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override
        public String unsafeString() {
          return password;
        }

        @Override
        public String version() {
          return version;
        }

        @Override
        public Optional<Instant> notAfter() {
          return Optional.empty();
        }

        @Override
        public void close() {
          java.util.Arrays.fill(chars, '\0');
        }

        @Override
        public String toString() {
          return "‹secret›";
        }
      };
    }

    @Override
    public Login login(String field) throws SecretUnavailableException {
      throw new SecretUnavailableException("No dynamic login here", null);
    }

    @Override
    public boolean isSet(String field) {
      return password != null && MongoSettings.PASSWORD.equals(field);
    }

    @Override
    public Meta meta(String field) {
      return isSet(field) ? new Meta("local", version, null) : new Meta(null, null, null);
    }

    @Override
    public SshCertificate sshCertificate(String field, PublicKey key, SshCertRequest request)
        throws SecretUnavailableException {
      throw new SecretUnavailableException("No SSH CA here", null);
    }
  }

  /** The call's context with an environment's limits, optionally approved with a payload. */
  static final class Context implements ExecContext {
    private final Env env;
    private final Limits limits;
    private final Instant deadline;
    private final JsonNode payload;
    final List<Runnable> cancels = new ArrayList<>();
    final List<String> taint = new ArrayList<>();

    Context(Env env, Limits limits, Duration timeout, JsonNode payload) {
      this.env = env;
      this.limits = limits;
      this.deadline = Instant.now().plus(timeout);
      this.payload = payload;
    }

    static Context of(Env env) {
      return new Context(env, limits(env), Duration.ofSeconds(15), null);
    }

    static Context with(Env env, Limits limits) {
      return new Context(env, limits, Duration.ofSeconds(15), null);
    }

    static Context approved(Env env, JsonNode payload) {
      return new Context(env, limits(env), Duration.ofSeconds(15), payload);
    }

    /** BLUEPRINT §9: dev 1,000 rows · 5 s · 512 KB · 10 s; prod 100 rows · 3 s · 256 KB · 5 s. */
    static Limits limits(Env env) {
      boolean prod = env == Env.PROD;
      return new Limits(prod ? 100 : 1_000, prod ? 200 : 1_000, (prod ? 256 : 512) * 1024L, 256 * 1024L,
          256 * 1024L, 64 * 1024L, 8 * 1024, Duration.ofMillis(prod ? 3_000 : 5_000), Duration.ofSeconds(30),
          Duration.ofSeconds(prod ? 5 : 10), Duration.ofHours(prod ? 24 : 168));
    }

    static Limits withRows(Limits l, int rows, long responseBytes, Duration statement) {
      return new Limits(rows, l.maxLines(), responseBytes, l.maxRequestBytes(), l.maxOutputBytes(),
          l.maxStderrBytes(), l.maxCellChars(), statement, l.commandTimeout(), l.toolTimeout(), l.maxRange());
    }

    @Override
    public String callId() {
      return "tc_test";
    }

    @Override
    public String traceId() {
      return "0".repeat(32);
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
    public void onCancel(Runnable action) {
      cancels.add(action);
    }

    @Override
    public boolean approved() {
      return payload != null;
    }

    @Override
    public Optional<String> approvalId() {
      return payload == null ? Optional.empty() : Optional.of("ap_test");
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
      return System.getLogger("test");
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
      return Path.of("target", "work");
    }

    @Override
    public Egress egress() {
      throw new UnsupportedOperationException("MongoDB doesn't use the HTTP egress");
    }

    @Override
    public void taint(CharSequence value) {
      taint.add(value.toString());
    }
  }
}
