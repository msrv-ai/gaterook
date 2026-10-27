package ai.msrv.gaterook.connectors.logs;

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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiPredicate;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The call context the core gives a Logs call: limits, a deadline, the core's sealed handles (same format, a test key),
 * shared quotas a test can refuse, and the taint every derived credential must join.
 */
final class TestCtx implements ExecContext {

  static final JsonMapper JSON = JsonMapper.builder().build();

  final List<String> taints = new CopyOnWriteArrayList<>();
  final List<String> quotaKeys = new CopyOnWriteArrayList<>();
  private final Env env;
  private final Limits limits;
  private final Clock clock;
  private final Instant deadline;
  private final JsonNode payload;
  private final BiPredicate<String, Integer> quota;
  private final Handles handles;

  private TestCtx(Env env, Limits limits, Clock clock, Duration timeout, JsonNode payload,
      BiPredicate<String, Integer> quota, Handles handles) {
    this.env = env;
    this.limits = limits;
    this.clock = clock;
    this.deadline = clock.instant().plus(timeout);
    this.payload = payload;
    this.quota = quota;
    this.handles = handles;
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
    return new TestCtx(Env.DEV, dev(), Clock.systemUTC(), Duration.ofSeconds(10), null, (k, n) -> true,
        new Handles(Clock.systemUTC()));
  }

  TestCtx with(Limits l) {
    return new TestCtx(env, l, clock, l.toolTimeout(), payload, quota, handles);
  }

  TestCtx env(Env e) {
    return new TestCtx(e, e == Env.PROD ? prod() : dev(), clock, (e == Env.PROD ? prod() : dev()).toolTimeout(),
        payload, quota, handles);
  }

  TestCtx clock(Clock c) {
    return new TestCtx(env, limits, c, Duration.between(clock.instant(), deadline), payload, quota, new Handles(c));
  }

  TestCtx timeout(Duration d) {
    return new TestCtx(env, limits, clock, d, payload, quota, handles);
  }

  TestCtx quota(BiPredicate<String, Integer> q) {
    return new TestCtx(env, limits, clock, Duration.between(clock.instant(), deadline), payload, q, handles);
  }

  TestCtx approved(JsonNode p) {
    return new TestCtx(env, limits, clock, Duration.between(clock.instant(), deadline), p, quota, handles);
  }

  /** A context of a later call: the same handle key, a fresh deadline. */
  TestCtx next() {
    return new TestCtx(env, limits, clock, limits.toolTimeout(), payload, quota, handles);
  }

  @Override public String callId() { return "tc_logs_test"; }
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
  @Override public System.Logger log() { return System.getLogger("logs-test"); }
  @Override public Clock clock() { return clock; }
  @Override public HandleCodec handles() { return handles; }
  @Override public Path workDir() { return Path.of("target"); }

  @Override
  public boolean acquire(String remoteQuotaKey, int permitsPerMinute) {
    quotaKeys.add(remoteQuotaKey + "@" + permitsPerMinute);
    return quota.test(remoteQuotaKey, permitsPerMinute);
  }

  @Override
  public Egress egress() {
    return (request, maxBytes) -> {
      throw ConnectorException.denied("The Logs connector uses its own clients");
    };
  }

  @Override
  public void taint(CharSequence value) {
    taints.add(value.toString());
  }

  /** {@code base64url(JSON).base64url(HMAC)} like the core's {@code SealedHandles}, under a test key. */
  static final class Handles implements HandleCodec {
    private static final byte[] KEY = "logs-test-handle-key-0123456789ab".getBytes(StandardCharsets.UTF_8);
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

  /** A clock tests move by hand. */
  static final class MovingClock extends Clock {
    private volatile Instant now;

    MovingClock(Instant start) {
      this.now = start;
    }

    void advance(Duration d) {
      now = now.plus(d);
    }

    @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
  }

  static List<String> lines(String text) {
    return new ArrayList<>(List.of(text.split("\n")));
  }
}
