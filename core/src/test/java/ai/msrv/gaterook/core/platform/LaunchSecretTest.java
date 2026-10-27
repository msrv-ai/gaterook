package ai.msrv.gaterook.core.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.core.platform.LaunchSecret.Verdict;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class LaunchSecretTest {

  private static final Instant NOW = Instant.parse("2026-10-22T12:30:00Z");
  private static final String NONCE = "AAAAAAAAAAAAAAAAAAAAAA";

  private final LaunchSecret secret = TestGateway.launchSecret();

  @Test
  void aSignatureVerifiesForTheSameRequestOnly() {
    String header = secret.sign("GET", "/api/events?x=1", NOW.toEpochMilli(), NONCE);

    assertThat(secret.verify("GET", "/api/events?x=1", header, NOW).verdict()).isEqualTo(Verdict.VALID);
    assertThat(secret.verify("GET", "/api/events?x=1", header, NOW).nonce()).isEqualTo(NONCE);
    assertThat(secret.verify("POST", "/api/events?x=1", header, NOW).verdict()).isEqualTo(Verdict.BAD_SIGNATURE);
    assertThat(secret.verify("GET", "/api/events?x=2", header, NOW).verdict()).isEqualTo(Verdict.BAD_SIGNATURE);
    assertThat(secret.verify("GET", "/api/events", header, NOW).verdict()).isEqualTo(Verdict.BAD_SIGNATURE);
  }

  @Test
  void anotherSecretDoesNotVerify() {
    LaunchSecret other = LaunchSecret.fromBase64Url(Base64.getUrlEncoder().encodeToString(new byte[32]));
    String header = other.sign("GET", "/api/health", NOW.toEpochMilli(), NONCE);

    assertThat(secret.verify("GET", "/api/health", header, NOW).verdict()).isEqualTo(Verdict.BAD_SIGNATURE);
  }

  @Test
  void timestampsOutsideThirtySecondsExpire() {
    String old = secret.sign("GET", "/api/health", NOW.minusSeconds(31).toEpochMilli(), NONCE);
    String future = secret.sign("GET", "/api/health", NOW.plusSeconds(31).toEpochMilli(), NONCE);
    String edge = secret.sign("GET", "/api/health", NOW.minusSeconds(30).toEpochMilli(), NONCE);

    assertThat(secret.verify("GET", "/api/health", old, NOW).verdict()).isEqualTo(Verdict.EXPIRED);
    assertThat(secret.verify("GET", "/api/health", future, NOW).verdict()).isEqualTo(Verdict.EXPIRED);
    assertThat(secret.verify("GET", "/api/health", edge, NOW).verdict()).isEqualTo(Verdict.VALID);
  }

  @Test
  void malformedHeadersAreRefusedBeforeAnyMac() {
    assertThat(secret.verify("GET", "/", null, NOW).verdict()).isEqualTo(Verdict.MISSING);
    assertThat(secret.verify("GET", "/", "", NOW).verdict()).isEqualTo(Verdict.MISSING);
    assertThat(secret.verify("GET", "/", "abc", NOW).verdict()).isEqualTo(Verdict.MALFORMED);
    assertThat(secret.verify("GET", "/", NOW.toEpochMilli() + ".short." + "A".repeat(43), NOW).verdict())
        .isEqualTo(Verdict.MALFORMED);
    assertThat(secret.verify("GET", "/", NOW.toEpochMilli() + "." + NONCE + ".bad", NOW).verdict())
        .isEqualTo(Verdict.MALFORMED);
    assertThat(secret.verify("GET", "/", "x." + NONCE + "." + "A".repeat(43), NOW).verdict())
        .isEqualTo(Verdict.MALFORMED);
  }

  @Test
  void shortKeysAreRejectedAndTheKeyNeverPrints() {
    assertThatThrownBy(() -> LaunchSecret.fromBase64Url(Base64.getUrlEncoder().encodeToString(new byte[31])))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> LaunchSecret.fromBase64Url("not base64 at all!")).isInstanceOf(IllegalArgumentException.class);
    assertThat(secret.toString()).doesNotContain(TestGateway.LAUNCH_SECRET).contains("redacted");
  }
}
