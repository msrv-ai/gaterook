package ai.msrv.gaterook.connectors.kubernetes;

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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** What the core hands a connector, in the small: calls, connections, policies, contexts and secrets. */
final class K8sTestKit {

  static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private K8sTestKit() {}

  static ObjectNode json(String text) {
    return (ObjectNode) K8sApi.JSON.readTree(text);
  }

  static ToolCall call(String tool, String argsJson) {
    return new ToolCall("tc_" + UUID.randomUUID(), tool, "dev-cluster", json(argsJson), null);
  }

  static ToolCall call(String callId, String tool, String argsJson) {
    return new ToolCall(callId, tool, "dev-cluster", json(argsJson), null);
  }

  static ConnectionConfig cfg(Env env, String configJson) {
    return cfg("c1", env, configJson, null);
  }

  static ConnectionConfig cfg(String id, Env env, String configJson, JsonNode metadata) {
    return new ConnectionConfig(id, env.id() + "-cluster", "kubernetes", env, 1, json(configJson),
        Set.of(K8sSettings.TOKEN, K8sSettings.KUBECONFIG), metadata);
  }

  /** A token connection to {@code server} (https), namespace app-dev. */
  static ConnectionConfig tokenCfg(Env env, String server) {
    return cfg(env, "{\"auth\":\"token\",\"server\":\"" + server + "\",\"namespace\":\"app-dev\"}");
  }

  static PolicyView policy(Env env, String sectionJson) {
    return new PolicyView(env, env.id() + ".k8s", json(sectionJson), limits(env));
  }

  static Limits limits(Env env) {
    boolean prod = env == Env.PROD;
    return new Limits(prod ? 100 : 1000, prod ? 200 : 1000, 512 * 1024, 256 * 1024, 256 * 1024, 64 * 1024, 8192,
        Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofHours(168));
  }

  static Secrets secrets(Map<String, String> values) {
    return new Secrets(values);
  }

  static Ctx ctx(Env env) {
    return new Ctx(env);
  }

  /** Values of the connection's secret fields; every value handed out is remembered like the core taints it. */
  static final class Secrets implements SecretResolver {
    private final Map<String, String> values;
    final List<String> handedOut = new CopyOnWriteArrayList<>();

    Secrets(Map<String, String> values) {
      this.values = values;
    }

    @Override
    public Secret secret(String field) throws SecretUnavailableException {
      String v = values.get(field);
      if (v == null) {
        throw new SecretUnavailableException(field + " is not set", null);
      }
      handedOut.add(v);
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
      throw new SecretUnavailableException("no logins", null);
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
      throw new SecretUnavailableException("no ssh", null);
    }
  }

  /** An ExecContext with a settable approval, a memory of tainted values and sealed handles kept in memory. */
  static final class Ctx implements ExecContext {
    private final Env env;
    final String callId = "tc_" + UUID.randomUUID();
    final List<String> tainted = new CopyOnWriteArrayList<>();
    final List<Runnable> cancels = new CopyOnWriteArrayList<>();
    Clock clock = Clock.systemUTC();
    Duration timeout = Duration.ofSeconds(10);
    Instant deadline;
    boolean approved;
    JsonNode preparedState;
    JsonNode approvedPayload;
    Limits limits;
    final Handles handles = new Handles();

    Ctx(Env env) {
      this.env = env;
      this.limits = K8sTestKit.limits(env);
    }

    Ctx approved(JsonNode payload, JsonNode state) {
      this.approved = true;
      this.approvedPayload = payload;
      this.preparedState = state;
      return this;
    }

    Ctx deadlineIn(Duration d) {
      this.deadline = clock.instant().plus(d);
      return this;
    }

    @Override
    public String callId() {
      return callId;
    }

    @Override
    public String traceId() {
      return "trace";
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
      if (deadline == null) {
        deadline = clock.instant().plus(timeout);
      }
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
      return approved;
    }

    @Override
    public Optional<String> approvalId() {
      return approved ? Optional.of("apr_1") : Optional.empty();
    }

    @Override
    public Optional<JsonNode> preparedState() {
      return Optional.ofNullable(preparedState);
    }

    @Override
    public Optional<JsonNode> approvedPayload() {
      return Optional.ofNullable(approvedPayload);
    }

    @Override
    public System.Logger log() {
      return System.getLogger("k8s-test");
    }

    @Override
    public Clock clock() {
      return clock;
    }

    @Override
    public HandleCodec handles() {
      return handles;
    }

    @Override
    public boolean acquire(String remoteQuotaKey, int permitsPerMinute) {
      return true;
    }

    @Override
    public Path workDir() {
      return Path.of(System.getProperty("java.io.tmpdir"));
    }

    @Override
    public Egress egress() {
      throw new UnsupportedOperationException("Kubernetes doesn't use the egress client");
    }

    @Override
    public void taint(CharSequence value) {
      if (value != null) {
        tainted.add(value.toString());
      }
    }
  }

  /** Sealed handles as the core keeps them: opaque, bound to their purpose, expiring. */
  static final class Handles implements ExecContext.HandleCodec {
    private record Sealed(String purpose, JsonNode payload, Instant until) {}

    private final Map<String, Sealed> byId = new ConcurrentHashMap<>();
    final List<String> issued = new ArrayList<>();

    @Override
    public String seal(String purpose, JsonNode payload, Duration ttl) {
      String id = UUID.randomUUID().toString().replace("-", "");
      byId.put(id, new Sealed(purpose, payload.deepCopy(), Instant.now().plus(ttl)));
      issued.add(id);
      return id;
    }

    @Override
    public Optional<JsonNode> open(String purpose, String handle) {
      Sealed s = byId.get(handle);
      if (s == null || !s.purpose().equals(purpose) || Instant.now().isAfter(s.until())) {
        return Optional.empty();
      }
      return Optional.of(s.payload());
    }
  }
}
