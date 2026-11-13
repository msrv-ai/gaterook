package com.gaterook.core.mcpservers;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.core.mcpservers.internal.oauth.OAuthClient;
import com.gaterook.core.mcpservers.internal.oauth.SignInBundle;
import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * A token about to expire is renewed before it is sent, at most once a minute: an authorization server that issues
 * tokens for a second would otherwise be asked for new ones at every request, each a new token to mask everywhere.
 */
class McpServerRenewalTest {

  private static final Instant SIGNED_IN = Instant.parse("2026-11-10T10:00:00Z");

  private static SignInBundle bundle(Instant renewedAt) {
    return new SignInBundle("https://mcp.tracker.example.com/mcp", null, "https://auth.tracker.example.com",
        URI.create("https://auth.tracker.example.com/token"), null, new OAuthClient.Client("c-1", null, "none", true,
            "http://localhost:5/callback"), null, "at-1x7Kq9Ws3Zp0Lm4Rt8Yv", SIGNED_IN.plusSeconds(1),
        "rt-5Hn2Bc6Vx1Qa9Ze3Uj7", null, SIGNED_IN, renewedAt, null);
  }

  @Test
  void aTokenIsRenewedBeforeItIsSentAtMostOnceAMinute() {
    assertThat(McpServerSignIns.renewDue(bundle(SIGNED_IN), SIGNED_IN.plusSeconds(5))).isFalse();
    assertThat(McpServerSignIns.renewDue(bundle(SIGNED_IN), SIGNED_IN.plusSeconds(59))).isFalse();
    assertThat(McpServerSignIns.renewDue(bundle(SIGNED_IN), SIGNED_IN.plus(McpServerSignIns.MIN_RENEW_INTERVAL)))
        .isTrue();
    assertThat(McpServerSignIns.renewDue(bundle(null), SIGNED_IN.plusSeconds(5))).as("since the sign-in").isFalse();
  }
}
