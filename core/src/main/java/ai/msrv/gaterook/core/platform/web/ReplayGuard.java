package ai.msrv.gaterook.core.platform.web;

import ai.msrv.gaterook.core.platform.LaunchSecret;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Single use of the nonces of {@code X-Gateway-Auth} (A21). Only signatures that already verified are recorded, so
 * the cache holds what the secret's owner sent in the last minute or so; an entry can be forgotten once its timestamp
 * is out of the acceptance window, because the signature then fails as expired anyway.
 */
final class ReplayGuard {

  /** Far above what one desktop app sends per minute; beyond it requests are refused rather than remembered. */
  static final int CAPACITY = 100_000;

  private static final int SWEEP_EVERY = 1_024;

  private final Clock clock;
  private final ConcurrentHashMap<String, Long> seen = new ConcurrentHashMap<>();
  private final AtomicInteger sinceSweep = new AtomicInteger();

  ReplayGuard(Clock clock) {
    this.clock = clock;
  }

  /** Records {@code nonce}; {@code false} when it was used before (a replay) or the cache is full. */
  boolean firstUse(String nonce, long timestampMillis) {
    long now = clock.millis();
    if (sinceSweep.incrementAndGet() >= SWEEP_EVERY || seen.size() >= CAPACITY) {
      sinceSweep.set(0);
      seen.values().removeIf(expiry -> expiry < now);
      if (seen.size() >= CAPACITY) {
        return false;
      }
    }
    // Keep it until the signature could no longer pass the timestamp check, measured from either clock.
    long expiry = Math.max(now, timestampMillis) + LaunchSecret.WINDOW.toMillis();
    return seen.putIfAbsent(nonce, expiry) == null;
  }

  int size() {
    return seen.size();
  }
}
