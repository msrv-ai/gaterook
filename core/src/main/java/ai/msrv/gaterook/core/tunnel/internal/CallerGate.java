package ai.msrv.gaterook.core.tunnel.internal;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The first gate of the public URL (Settings › Tunnel › Callers, report 02 §6.2): with "Anthropic IP ranges only" on
 * (the default), server calls ({@code /mcp}, metadata, {@code /oauth/token|register|revoke}) from any other address get
 * an empty 403 before anything else sees them. The browser pages of the sign-in ({@code /oauth/authorize[/status]})
 * are opened by the person's own browser and are never gated here: the consent on this computer guards them.
 *
 * <p>The caller's address comes from a header of the provider ({@code CF-Connecting-IP}, ngrok's
 * {@code X-Forwarded-For}): any local process could forge it on 7466, so the gate only narrows who reaches sign-in and
 * never authenticates. The self-test of a published tunnel calls from this computer's own address and passes once per
 * nonce ({@code X-Gateway-Selftest}: 128 random bits, single use, 30 s).
 *
 * <p>Refusals are counted, not audited one by one (a scanner would fill the append-only log): a WARN in
 * {@code core.log} once a minute at most, and the count of this tunnel session in its state.
 */
public final class CallerGate {

  /** Header of the self-test's requests. */
  public static final String SELFTEST_HEADER = "X-Gateway-Selftest";

  static final Duration NONCE_TTL = Duration.ofSeconds(30);

  private static final Logger log = LoggerFactory.getLogger(CallerGate.class);
  private static final SecureRandom RANDOM = new SecureRandom();

  private final Clock clock;
  private final Map<String, Instant> nonces = new ConcurrentHashMap<>();
  private final AtomicLong refused = new AtomicLong();
  private final AtomicLong refusedSinceLog = new AtomicLong();
  private final AtomicLong served = new AtomicLong();
  private volatile boolean anthropicOnly = true;
  private volatile Instant lastLog = Instant.EPOCH;

  public CallerGate(Clock clock) {
    this.clock = clock;
  }

  /** The CIDR blocks the gate admits with "Anthropic IP ranges only" on. */
  public static List<String> anthropicRanges() {
    return AnthropicRanges.RANGES;
  }

  /** When {@link #anthropicRanges()} was last checked against Anthropic's documentation (ISO date). */
  public static String anthropicRangesVerified() {
    return AnthropicRanges.VERIFIED;
  }

  /** Settings › Tunnel › Callers; on until the stored setting says otherwise (fail closed at start). */
  public boolean anthropicOnly() {
    return anthropicOnly;
  }

  public void setAnthropicOnly(boolean anthropicOnly) {
    this.anthropicOnly = anthropicOnly;
  }

  /** Whether a server call from {@code ip} gets through the gate (the self-test's nonce is consumed here). */
  boolean admits(String ip, String selftestNonce) {
    if (!anthropicOnly || AnthropicRanges.contains(ip)) {
      return true;
    }
    return consume(selftestNonce);
  }

  /** A fresh single-use nonce for one request of the self-test. */
  public String selftestNonce() {
    byte[] bytes = new byte[16];
    RANDOM.nextBytes(bytes);
    String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    Instant now = clock.instant();
    nonces.entrySet().removeIf(e -> e.getValue().isBefore(now));
    nonces.put(nonce, now.plus(NONCE_TTL));
    return nonce;
  }

  private boolean consume(String nonce) {
    if (nonce == null || nonce.length() != 22) {
      return false;
    }
    Instant now = clock.instant();
    for (Iterator<Map.Entry<String, Instant>> it = nonces.entrySet().iterator(); it.hasNext(); ) {
      Map.Entry<String, Instant> e = it.next();
      if (e.getValue().isBefore(now)) {
        it.remove();
      } else if (MessageDigest.isEqual(e.getKey().getBytes(), nonce.getBytes())) {
        it.remove();
        return true;
      }
    }
    return false;
  }

  void refused(String ip) {
    refused.incrementAndGet();
    refusedSinceLog.incrementAndGet();
    Instant now = clock.instant();
    if (Duration.between(lastLog, now).compareTo(Duration.ofMinutes(1)) >= 0) {
      lastLog = now;
      long n = refusedSinceLog.getAndSet(0);
      log.warn("Tunnel callers: refused {} request(s) from outside Anthropic's IP ranges in the last minute "
          + "(latest from {})", n, ip == null ? "an unknown address" : ip);
    }
  }

  /** A call to the published endpoint got an answer below 400. */
  void served() {
    served.incrementAndGet();
  }

  /** Refused callers and served calls since the tunnel came up; resets them for the next session. */
  public long[] takeCounters() {
    return new long[] {refused.getAndSet(0), served.getAndSet(0)};
  }

  public long refusedCount() {
    return refused.get();
  }

  public long servedCount() {
    return served.get();
  }
}
