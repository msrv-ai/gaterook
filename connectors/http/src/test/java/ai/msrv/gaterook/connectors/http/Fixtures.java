package ai.msrv.gaterook.connectors.http;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.SecretUnavailableException;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connectors.support.egress.AddressPolicy;
import ai.msrv.gaterook.connectors.support.egress.EgressClients;
import ai.msrv.gaterook.connectors.support.egress.EgressSpec;
import ai.msrv.gaterook.connectors.support.egress.GuardedEgress;
import ai.msrv.gaterook.connectors.support.egress.HostResolver;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Test doubles of the core's side of the SPI for the HTTP connector: config, policy, secrets, call context, guard. */
final class Fixtures {

  static final JsonMapper JSON = JsonMapper.builder().build();

  private Fixtures() {}

  static JsonNode tree(String json) {
    return JSON.readTree(json);
  }

  static ObjectNode object(String json) {
    return (ObjectNode) JSON.readTree(json);
  }

  static ConnectionConfig cfg(String name, Env env, String config) {
    JsonNode c = tree(config);
    Set<String> secrets = new LinkedHashSet<>();
    for (String ptr : new String[] {HttpSettings.TOKEN, HttpSettings.API_KEY, HttpSettings.PASSWORD}) {
      secrets.add(ptr);
    }
    for (int i = 0; i < c.path("secretHeaders").size(); i++) {
      secrets.add("/secretHeaders/" + i + "/value");
    }
    return new ConnectionConfig("cn_" + name, name, HttpConnector.TYPE, env, 1, c, secrets, null);
  }

  static Limits limits(long maxResponseBytes, long maxRequestBytes, Duration toolTimeout) {
    return new Limits(1_000, 1_000, maxResponseBytes, maxRequestBytes, 256 * 1024, 64 * 1024, 8 * 1024,
        Duration.ofSeconds(5), Duration.ofSeconds(30), toolTimeout, Duration.ofHours(168));
  }

  static Limits devLimits() {
    return limits(512 * 1024, 256 * 1024, Duration.ofSeconds(10));
  }

  /** The {@code http} section of an environment's policy with {@code allowedBaseUrls}. */
  static PolicyView policy(Env env, String... allowedBaseUrls) {
    ObjectNode section = JsonNodeFactory.instance.objectNode();
    ArrayNode urls = section.putArray("allowedBaseUrls");
    for (String u : allowedBaseUrls) {
      urls.add(u);
    }
    return new PolicyView(env, env.id() + ".http", section, devLimits());
  }

  static ToolCall call(String tool, String arguments) {
    return new ToolCall("tc_test", tool, "billing-api", object(arguments), null);
  }

  /** A resolver over fixed values; {@code unresolvable} fields act like a reference to a store that isn't connected. */
  static final class Secrets implements SecretResolver {
    final Map<String, String> values = new HashMap<>();
    final Set<String> unresolvable = new HashSet<>();
    final List<String> resolved = new ArrayList<>();

    static Secrets of(String field, String value) {
      Secrets s = new Secrets();
      s.values.put(field, value);
      return s;
    }

    Secrets with(String field, String value) {
      values.put(field, value);
      return this;
    }

    @Override
    public Secret secret(String field) throws SecretUnavailableException {
      if (unresolvable.contains(field)) {
        throw new SecretUnavailableException(field + " can't be resolved: AWS Secrets Manager isn't connected.",
            null);
      }
      String v = values.get(field);
      if (v == null) {
        throw new SecretUnavailableException(field + " has no value.", null);
      }
      resolved.add(field);
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
          return "1";
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
      throw new SecretUnavailableException("No login " + field, null);
    }

    @Override
    public boolean isSet(String field) {
      return values.containsKey(field) || unresolvable.contains(field);
    }

    @Override
    public Meta meta(String field) {
      return new Meta("local", "1", null);
    }

    @Override
    public SshCertificate sshCertificate(String field, PublicKey key, SshCertRequest request)
        throws SecretUnavailableException {
      throw new SecretUnavailableException("No SSH CA", null);
    }
  }

  /** Names the guard resolves: each name has a queue of answers, the last one repeats (DNS rebinding tests). */
  static final class Names implements HostResolver {
    final Map<String, Deque<List<InetAddress>>> answers = new HashMap<>();

    Names answer(String host, String... ips) {
      List<InetAddress> list = new ArrayList<>();
      for (String ip : ips) {
        list.add(ip(ip));
      }
      answers.computeIfAbsent(host, h -> new ArrayDeque<>()).add(list);
      return this;
    }

    @Override
    public List<InetAddress> resolve(String host) throws UnknownHostException {
      Deque<List<InetAddress>> q = answers.get(host);
      if (q == null || q.isEmpty()) {
        throw new UnknownHostException(host);
      }
      return q.size() > 1 ? q.poll() : q.peek();
    }
  }

  static InetAddress ip(String text) {
    try {
      return InetAddress.getByName(text);
    } catch (UnknownHostException e) {
      throw new IllegalArgumentException(e);
    }
  }

  static EgressClients clients(Names names, Set<Integer> corePorts) {
    return new EgressClients(new EgressClients.Settings("mcp-gateway/test", corePorts, names));
  }

  static GuardedEgress guard(EgressClients clients, String poolKey, String... allowedPrivate) {
    return clients.egress(EgressSpec.of(poolKey, AddressPolicy.builder().allowPrivate(allowedPrivate).build()));
  }

  /** The call context: limits, deadline, the guard; approved with a payload when an approval runs it. */
  static final class Context implements ExecContext {
    final Env env;
    final Limits limits;
    final Egress egress;
    final boolean approved;
    final JsonNode payload;
    final Clock clock = Clock.systemUTC();
    final Instant deadline;
    final List<String> taints = new ArrayList<>();
    final List<Runnable> cancels = new ArrayList<>();

    Context(Env env, Limits limits, Egress egress, boolean approved, JsonNode payload) {
      this.env = env;
      this.limits = limits;
      this.egress = egress;
      this.approved = approved;
      this.payload = payload;
      this.deadline = clock.instant().plus(limits.toolTimeout());
    }

    static Context of(GuardedEgress guard) {
      return new Context(Env.DEV, devLimits(), guard, false, null);
    }

    static Context approved(GuardedEgress guard, JsonNode payload) {
      return new Context(Env.DEV, devLimits(), guard, true, payload);
    }

    Context withLimits(Limits l) {
      return new Context(env, l, egress, approved, payload);
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
      return System.getLogger("ai.msrv.gaterook.connector.http.test");
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
      return Path.of("target", "http-work");
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
}
