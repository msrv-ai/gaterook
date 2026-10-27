package ai.msrv.gaterook.core.tunnel.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Settings › Tunnel › Callers (report 02 §6.2): Anthropic's outbound range and the self-test's single-use pass. */
class CallerGateTest {

  @Test
  void anthropicsOutboundRangeIsTheSlash21AndNothingAroundIt() {
    assertThat(CallerGate.anthropicRanges()).containsExactly("160.79.104.0/21");
    assertThat(CallerGate.anthropicRangesVerified()).isEqualTo("2026-10-23");
    for (String in : new String[] {"160.79.104.0", "160.79.104.1", "160.79.104.10", "160.79.107.200",
        "160.79.111.254", "160.79.111.255"}) {
      assertThat(AnthropicRanges.contains(in)).as(in).isTrue();
    }
    for (String out : new String[] {"160.79.103.255", "160.79.112.0", "160.79.112.1", "34.162.46.92", "127.0.0.1",
        "203.0.113.24", "2607:6bc0::10", "::ffff:160.79.104.10", "160.79.104", "160.79.104.010", "0x0a.0.0.1",
        "160.79.104.10 ", "", null}) {
      // A trailing blank is stripped by ipv4(); everything else that isn't four decimal octets is refused.
      boolean expected = "160.79.104.10 ".equals(out);
      assertThat(AnthropicRanges.contains(out)).as(String.valueOf(out)).isEqualTo(expected);
    }
  }

  @Test
  void theGateAdmitsAnthropicOrEveryoneAndTheSelfTestOncePerNonce() {
    AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-25T10:00:00Z"));
    Clock clock = new Clock() {
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
        return now.get();
      }
    };
    CallerGate gate = new CallerGate(clock);
    assertThat(gate.anthropicOnly()).as("closed by default").isTrue();
    assertThat(gate.admits("160.79.104.10", null)).isTrue();
    assertThat(gate.admits("203.0.113.24", null)).isFalse();
    assertThat(gate.admits(null, null)).isFalse();

    String nonce = gate.selftestNonce();
    assertThat(nonce).hasSize(22);
    assertThat(gate.admits("203.0.113.24", "x".repeat(22))).isFalse();
    assertThat(gate.admits("203.0.113.24", nonce)).isTrue();
    assertThat(gate.admits("203.0.113.24", nonce)).as("single use").isFalse();
    String late = gate.selftestNonce();
    now.set(now.get().plus(Duration.ofSeconds(31)));
    assertThat(gate.admits("203.0.113.24", late)).as("30 s").isFalse();

    gate.setAnthropicOnly(false);
    assertThat(gate.admits("203.0.113.24", null)).isTrue();
    assertThat(gate.admits(null, null)).isTrue();

    gate.refused("203.0.113.24");
    gate.refused("203.0.113.25");
    gate.served();
    assertThat(gate.refusedCount()).isEqualTo(2);
    assertThat(gate.servedCount()).isEqualTo(1);
    assertThat(gate.takeCounters()).containsExactly(2, 1);
    assertThat(gate.takeCounters()).containsExactly(0, 0);
  }
}
