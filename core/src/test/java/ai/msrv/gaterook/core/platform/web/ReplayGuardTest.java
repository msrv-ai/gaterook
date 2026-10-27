package ai.msrv.gaterook.core.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ReplayGuardTest {

  private final AtomicLong now = new AtomicLong(Instant.parse("2026-10-22T12:30:00Z").toEpochMilli());
  private final Clock clock = new Clock() {
    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return Instant.ofEpochMilli(now.get());
    }
  };

  @Test
  void aNonceIsAcceptedOnce() {
    ReplayGuard guard = new ReplayGuard(clock);

    assertThat(guard.firstUse("nonce-0000000001", now.get())).isTrue();
    assertThat(guard.firstUse("nonce-0000000001", now.get())).isFalse();
    assertThat(guard.firstUse("nonce-0000000002", now.get())).isTrue();
  }

  @Test
  void expiredNoncesAreForgottenOnTheNextSweep() {
    ReplayGuard guard = new ReplayGuard(clock);
    long signedAt = now.get();
    guard.firstUse("nonce-old-000001", signedAt);

    now.addAndGet(61_000);
    for (int i = 0; i < 2_048; i++) {
      guard.firstUse("nonce-new-" + String.format("%06d", i), now.get());
    }
    // The old entry was swept; a replay of it fails the timestamp check of the signature instead.
    assertThat(guard.size()).isEqualTo(2_048);
  }
}
