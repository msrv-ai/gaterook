package ai.msrv.gaterook.connectors.redis;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.Limits;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The call context the core gives a Redis call: limits, a deadline, the core's sealed handles (the same format as
 * {@code SealedHandles}, under a test key) and cancellation callbacks a test can fire.
 */
final class TestCtx implements ExecContext {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final Handles HANDLES = new Handles(Clock.systemUTC());

  final List<Runnable> onCancel = new CopyOnWriteArrayList<>();
  private final Env env;
  private final Limits limits;
  private final Instant deadline;
  private final JsonNode payload;
  private volatile boolean cancelled;

  private TestCtx(Env env, Limits limits, Duration timeout, JsonNode payload) {
    this.env = env;
    this.limits = limits;
    this.deadline = Instant.now().plus(timeout);
    this.payload = payload;
  }

  static Limits dev() {
    return new Limits(1_000, 1_000, 512 * 1024, 256 * 1024, 256 * 1024, 64 * 1024, 8 * 1024, Duration.ofSeconds(5),
        Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofHours(168));
  }

  static Limits prod() {
    return new Limits(100, 200, 256 * 1024, 64 * 1024, 256 * 1024, 64 * 1024, 8 * 1024, Duration.ofSeconds(3),
        Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofHours(24));
  }

  static TestCtx of() {
    return new TestCtx(Env.DEV, dev(), Duration.ofSeconds(10), null);
  }

  TestCtx with(Limits l) {
    return new TestCtx(env, l, l.toolTimeout(), payload);
  }

  TestCtx timeout(Duration d) {
    return new TestCtx(env, limits, d, payload);
  }

  TestCtx approved(JsonNode p) {
    return new TestCtx(env, limits, Duration.between(Instant.now(), deadline), p);
  }

  /** What the core does when the call's deadline passes: callbacks first, then the virtual thread's interrupt. */
  void cancel() {
    cancelled = true;
    onCancel.forEach(Runnable::run);
  }

  @Override public String callId() { return "tc_redis_test"; }
  @Override public String traceId() { return "4f2a9c71e8d04b5a9c3e2b1f0a6d8e81"; }
  @Override public String traceparent() { return "00-4f2a9c71e8d04b5a9c3e2b1f0a6d8e81-7a1c2e9b4d3f6a10-01"; }
  @Override public Env env() { return env; }
  @Override public Limits limits() { return limits; }
  @Override public Duration timeout() { return limits.toolTimeout(); }
  @Override public Instant deadline() { return deadline; }
  @Override public boolean cancelled() { return cancelled || Thread.currentThread().isInterrupted(); }
  @Override public void onCancel(Runnable action) { onCancel.add(action); }
  @Override public boolean approved() { return payload != null; }
  @Override public Optional<String> approvalId() { return payload == null ? Optional.empty() : Optional.of("ap_x"); }
  @Override public Optional<JsonNode> preparedState() { return Optional.empty(); }
  @Override public Optional<JsonNode> approvedPayload() { return Optional.ofNullable(payload); }
  @Override public System.Logger log() { return System.getLogger("redis-test"); }
  @Override public Clock clock() { return Clock.systemUTC(); }
  @Override public HandleCodec handles() { return HANDLES; }
  @Override public Path workDir() { return Path.of("target"); }
  @Override public boolean acquire(String remoteQuotaKey, int permitsPerMinute) { return true; }
  @Override public void taint(CharSequence value) {}

  @Override
  public Egress egress() {
    return (request, maxBytes) -> {
      throw ConnectorException.denied("The Redis connector uses its own sockets");
    };
  }

  /** {@code base64url(JSON{p,x,d}).base64url(HMAC)} like the core's {@code SealedHandles}, under a test key. */
  static final class Handles implements HandleCodec {
    private static final byte[] KEY = "redis-test-handle-key-0123456789".getBytes(StandardCharsets.UTF_8);
    private final Clock clock;

    Handles(Clock clock) {
      this.clock = clock;
    }

    @Override
    public String seal(String purpose, JsonNode payload, Duration ttl) {
      ObjectNode body = JsonNodeFactory.instance.objectNode();
      body.put("p", purpose);
      body.put("x", clock.instant().plus(ttl).getEpochSecond());
      body.set("d", payload);
      byte[] text = JSON.writeValueAsBytes(body);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(text) + "."
          + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(text));
    }

    @Override
    public Optional<JsonNode> open(String purpose, String handle) {
      int dot = handle.indexOf('.');
      if (dot <= 0) {
        return Optional.empty();
      }
      try {
        byte[] text = Base64.getUrlDecoder().decode(handle.substring(0, dot));
        byte[] mac = Base64.getUrlDecoder().decode(handle.substring(dot + 1));
        if (!MessageDigest.isEqual(mac, mac(text))) {
          return Optional.empty();
        }
        JsonNode body = JSON.readTree(text);
        if (!purpose.equals(body.path("p").asString()) || body.path("x").asLong() < clock.instant().getEpochSecond()) {
          return Optional.empty();
        }
        return Optional.of(body.path("d"));
      } catch (IllegalArgumentException e) {
        return Optional.empty();
      }
    }

    private static byte[] mac(byte[] text) {
      try {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(KEY, "HmacSHA256"));
        return m.doFinal(text);
      } catch (GeneralSecurityException e) {
        throw new IllegalStateException(e);
      }
    }
  }
}
