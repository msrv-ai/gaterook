package com.gaterook.connectors.search;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connector.api.Limits;
import com.gaterook.connector.api.PolicyView;
import com.gaterook.connector.api.ToolCall;
import com.gaterook.connectors.support.egress.AddressPolicy;
import com.gaterook.connectors.support.egress.EgressClients;
import com.gaterook.connectors.support.egress.EgressSpec;
import com.gaterook.connectors.support.egress.HostResolver;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The call context the core gives a call of this module: limits, a deadline, the approved payload, the taint, and the
 * core's own egress guard ({@link EgressClients} with the environment's public-only policy, which the connectors derive
 * their own address rules from, as in the core).
 */
final class TestCtx implements ExecContext {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final EgressClients CLIENTS = new EgressClients(new EgressClients.Settings("GateRook/test", Set.of(),
      HostResolver.SYSTEM));

  final List<String> taints = new CopyOnWriteArrayList<>();
  private final Env env;
  private final Limits limits;
  private final Instant deadline;
  private final JsonNode payload;
  private final String connectionId;

  private TestCtx(Env env, Limits limits, Duration timeout, JsonNode payload, String connectionId) {
    this.env = env;
    this.limits = limits;
    this.deadline = Instant.now().plus(timeout);
    this.payload = payload;
    this.connectionId = connectionId;
  }

  static Limits dev() {
    return new Limits(1_000, 1_000, 512 * 1024, 256 * 1024, 256 * 1024, 64 * 1024, 8 * 1024, Duration.ofSeconds(5),
        Duration.ofSeconds(30), Duration.ofSeconds(20), Duration.ofHours(168));
  }

  static TestCtx of() {
    return new TestCtx(Env.DEV, dev(), Duration.ofSeconds(20), null, "c-test");
  }

  TestCtx with(Limits l) {
    return new TestCtx(env, l, l.toolTimeout(), payload, connectionId);
  }

  TestCtx approved(JsonNode p) {
    return new TestCtx(env, limits, Duration.ofSeconds(20), p, connectionId);
  }

  static PolicyView policy(String type) {
    return new PolicyView(Env.DEV, "dev." + type, JsonNodeFactory.instance.objectNode(), dev());
  }

  static ConnectionConfig config(String type, String json) {
    return config(type, JSON.readTree(json));
  }

  static ConnectionConfig config(String type, JsonNode config) {
    return new ConnectionConfig("c-test", "test-" + type, type, Env.DEV, 1, config, Set.of(), null);
  }

  static ToolCall call(String tool, String args) {
    return new ToolCall("tc_search_test", tool, "test", (ObjectNode) JSON.readTree(args), null);
  }

  @Override public String callId() { return "tc_search_test"; }
  @Override public String traceId() { return "4f2a9c71e8d04b5a9c3e2b1f0a6d8e81"; }
  @Override public String traceparent() { return "00-4f2a9c71e8d04b5a9c3e2b1f0a6d8e81-7a1c2e9b4d3f6a10-01"; }
  @Override public Env env() { return env; }
  @Override public Limits limits() { return limits; }
  @Override public Duration timeout() { return limits.toolTimeout(); }
  @Override public Instant deadline() { return deadline; }
  @Override public boolean cancelled() { return Thread.currentThread().isInterrupted(); }
  @Override public void onCancel(Runnable action) {}
  @Override public boolean approved() { return payload != null; }
  @Override public Optional<String> approvalId() { return payload == null ? Optional.empty() : Optional.of("ap_x"); }
  @Override public Optional<JsonNode> preparedState() { return Optional.empty(); }
  @Override public Optional<JsonNode> approvedPayload() { return Optional.ofNullable(payload); }
  @Override public System.Logger log() { return System.getLogger("search-test"); }
  @Override public Clock clock() { return Clock.systemUTC(); }
  @Override public HandleCodec handles() { throw new UnsupportedOperationException("no handles here"); }
  @Override public Path workDir() { return Path.of("target"); }
  @Override public boolean acquire(String remoteQuotaKey, int permitsPerMinute) { return true; }

  /** The core's guard for this connection: public addresses only, as the core's default environment policy. */
  @Override
  public Egress egress() {
    return CLIENTS.egress(EgressSpec.of("connection:" + connectionId, AddressPolicy.publicOnly(Set.of())));
  }

  @Override
  public void taint(CharSequence value) {
    taints.add(value.toString());
  }
}
