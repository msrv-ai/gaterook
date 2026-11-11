package ai.msrv.gaterook.connectors.cloudflare;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.Limits;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiPredicate;
import tools.jackson.databind.JsonNode;

/**
 * The call context the core gives a Cloudflare call: limits, a deadline on a fixed clock, shared quotas a test can
 * refuse, the taint, and — for an approved call — the payload and prepared state the person approved.
 */
final class TestCtx implements ExecContext {

  static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");
  static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  final List<String> taints = new CopyOnWriteArrayList<>();
  final List<String> quotaKeys = new CopyOnWriteArrayList<>();
  private final Env env;
  private final Limits limits;
  private final Duration timeout;
  private final long startedNanos = System.nanoTime();
  private final boolean approved;
  private final JsonNode payload;
  private final JsonNode state;
  private final BiPredicate<String, Integer> quota;

  private TestCtx(Env env, Limits limits, Duration timeout, boolean approved, JsonNode payload, JsonNode state,
      BiPredicate<String, Integer> quota) {
    this.env = env;
    this.limits = limits;
    this.timeout = timeout;
    this.approved = approved;
    this.payload = payload;
    this.state = state;
    this.quota = quota;
  }

  static Limits dev() {
    return new Limits(1_000, 1_000, 512 * 1024, 256 * 1024, 256 * 1024, 64 * 1024, 8 * 1024, Duration.ofSeconds(5),
        Duration.ofSeconds(30), Duration.ofSeconds(10), null);
  }

  static Limits prod() {
    return new Limits(100, 200, 256 * 1024, 64 * 1024, 256 * 1024, 64 * 1024, 8 * 1024, Duration.ofSeconds(3),
        Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofHours(24));
  }

  static TestCtx of() {
    return new TestCtx(Env.DEV, dev(), Duration.ofSeconds(10), false, null, null, (k, n) -> true);
  }

  TestCtx with(Limits l) {
    return new TestCtx(env, l, timeout, approved, payload, state, quota);
  }

  TestCtx env(Env e) {
    return new TestCtx(e, e == Env.PROD ? prod() : dev(), timeout, approved, payload, state, quota);
  }

  TestCtx timeout(Duration d) {
    return new TestCtx(env, limits, d, approved, payload, state, quota);
  }

  TestCtx quota(BiPredicate<String, Integer> q) {
    return new TestCtx(env, limits, timeout, approved, payload, state, q);
  }

  /** The context of an approved call: what the person approved and what {@code prepare} kept. */
  TestCtx approved(JsonNode p, JsonNode preparedState) {
    return new TestCtx(env, limits, timeout, true, p, preparedState, quota);
  }

  @Override public String callId() { return "tc_cloudflare_test"; }
  @Override public String traceId() { return "4f2a9c71e8d04b5a9c3e2b1f0a6d8e81"; }
  @Override public String traceparent() { return "00-4f2a9c71e8d04b5a9c3e2b1f0a6d8e81-7a1c2e9b4d3f6a10-01"; }
  @Override public Env env() { return env; }
  @Override public Limits limits() { return limits; }
  @Override public Duration timeout() { return timeout; }

  /** A real deadline (the HTTP client's timeouts use it) measured from the context's creation. */
  @Override
  public Instant deadline() {
    return Instant.now().plus(timeout).minusNanos(System.nanoTime() - startedNanos);
  }

  @Override
  public Duration remaining() {
    Duration left = timeout.minusNanos(System.nanoTime() - startedNanos);
    return left.isNegative() ? Duration.ZERO : left;
  }

  @Override public boolean cancelled() { return Thread.currentThread().isInterrupted(); }
  @Override public void onCancel(Runnable action) {}
  @Override public boolean approved() { return approved; }
  @Override public Optional<String> approvalId() { return approved ? Optional.of("ap_x") : Optional.empty(); }
  @Override public Optional<JsonNode> preparedState() { return Optional.ofNullable(state); }
  @Override public Optional<JsonNode> approvedPayload() { return Optional.ofNullable(payload); }
  @Override public System.Logger log() { return System.getLogger("cloudflare-test"); }
  @Override public Clock clock() { return CLOCK; }
  @Override public HandleCodec handles() { throw new UnsupportedOperationException("no handles here"); }
  @Override public Path workDir() { return Path.of("target"); }

  @Override
  public boolean acquire(String remoteQuotaKey, int permitsPerMinute) {
    quotaKeys.add(remoteQuotaKey + "@" + permitsPerMinute);
    return quota.test(remoteQuotaKey, permitsPerMinute);
  }

  @Override
  public Egress egress() {
    return (request, maxBytes) -> {
      throw ConnectorException.denied("The Cloudflare connector uses its own client");
    };
  }

  @Override
  public void taint(CharSequence value) {
    taints.add(value.toString());
  }
}
