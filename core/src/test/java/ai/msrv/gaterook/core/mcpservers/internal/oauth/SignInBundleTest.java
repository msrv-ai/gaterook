package ai.msrv.gaterook.core.mcpservers.internal.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SignInBundleTest {

  private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

  private static SignInBundle bundle() {
    Discovery.Server server = new Discovery.Server("https://auth.example.com", URI.create("https://auth.example.com/a"),
        URI.create("https://auth.example.com/token"), null, URI.create("https://auth.example.com/revoke"), List.of(),
        List.of("S256"), List.of(), true, false, false);
    OAuthClient.Client client = new OAuthClient.Client("cid-1", "csecret-1", "client_secret_basic", true,
        "http://localhost:51234/callback");
    return SignInBundle.signedIn("https://mcp.example.com/mcp", "https://mcp.example.com/mcp", server, client,
        "read write", new OAuthClient.Tokens("at-1", NOW.plusSeconds(3600), "rt-1", "read"), NOW);
  }

  @Test
  void itSurvivesTheRoundTripThroughItsJson() {
    SignInBundle b = bundle();
    SignInBundle back = SignInBundle.fromJson(new String(b.toJson()));
    assertThat(back).isEqualTo(b);
    assertThat(back.header()).isEqualTo("Bearer at-1");
    assertThat(b.toString()).doesNotContain("at-1").doesNotContain("rt-1").doesNotContain("csecret-1");
  }

  @Test
  void aSignInByTheMetadataDocumentStaysUnregisteredThroughItsJson() {
    Discovery.Server server = new Discovery.Server("https://auth.example.com", URI.create("https://auth.example.com/a"),
        URI.create("https://auth.example.com/token"), URI.create("https://auth.example.com/register"), null, List.of(),
        List.of("S256"), List.of("none"), false, true, false);
    OAuthClient.Client client = OAuthClient.metadataDocument(URI.create("http://localhost:51234/callback"));
    SignInBundle b = SignInBundle.signedIn("https://mcp.example.com/mcp", "https://mcp.example.com/mcp", server,
        client, null, new OAuthClient.Tokens("at-1", NOW.plusSeconds(3600), "rt-1", null), NOW);
    SignInBundle back = SignInBundle.fromJson(new String(b.toJson()));
    assertThat(back.client()).isEqualTo(client);
    // A later sign-in reuses only a registration GateRook made; the document goes by the server's metadata each time.
    assertThat(back.client().registered()).isFalse();
    assertThat(back.client().secret()).isNull();
  }

  @Test
  void aDeviceSignInWithGateRooksOwnAppKeepsItsClientWithoutARedirect() {
    Discovery.Server server = new Discovery.Server("https://github.example/login/oauth",
        URI.create("https://github.example/login/oauth/authorize"),
        URI.create("https://github.example/login/oauth/access_token"), null, null, List.of("offline_access"),
        List.of("S256"), List.of(), true, false, false, URI.create("https://github.example/login/device/code"),
        List.of(Discovery.DEVICE_GRANT));
    OAuthClient.Client client = OAuthClient.ownApp("Ov23liGateRookTest");
    SignInBundle b = SignInBundle.signedIn("https://api.githubcopilot.com/mcp/", "https://api.githubcopilot.com/mcp/",
        server, client, "repo offline_access", new OAuthClient.Tokens("gho_1", NOW.plusSeconds(28800), "ghr_1",
            "repo"), NOW);
    SignInBundle back = SignInBundle.fromJson(new String(b.toJson()));
    assertThat(back).isEqualTo(b);
    assertThat(back.client()).isEqualTo(client);
    assertThat(back.client().redirectUri()).isNull();
    // Renewed as a public client: GitHub takes a device sign-in's refresh token without a client secret.
    assertThat(back.client().authMethod()).isEqualTo("none");
    assertThat(back.renewable()).isTrue();
  }

  @Test
  void aRenewalKeepsTheRefreshTokenWhenNoNewOneCameAndTheSignInTime() {
    SignInBundle renewed = bundle().renewed(new OAuthClient.Tokens("at-2", NOW.plusSeconds(7200), null, null),
        NOW.plusSeconds(3500));
    assertThat(renewed.accessToken()).isEqualTo("at-2");
    assertThat(renewed.refreshToken()).isEqualTo("rt-1");
    assertThat(renewed.grantedScope()).isEqualTo("read");
    assertThat(renewed.signedInAt()).isEqualTo(NOW);
    assertThat(renewed.renewedAt()).isEqualTo(NOW.plusSeconds(3500));
  }

  @Test
  void expiryIsJudgedWithTheMarginAndATokenWithoutOneNeverExpires() {
    SignInBundle b = bundle();
    assertThat(b.expiresWithin(Duration.ofSeconds(60), NOW.plusSeconds(3539))).isFalse();
    assertThat(b.expiresWithin(Duration.ofSeconds(60), NOW.plusSeconds(3540))).isTrue();
    SignInBundle forever = b.renewed(new OAuthClient.Tokens("at-3", null, null, null), NOW);
    assertThat(forever.expiresWithin(Duration.ofDays(3650), NOW)).isFalse();
  }

  @Test
  void needingASignInDropsTheTokens() {
    SignInBundle n = bundle().needing("revoked");
    assertThat(n.accessToken()).isNull();
    assertThat(n.refreshToken()).isNull();
    assertThat(n.header()).isNull();
    assertThat(n.needsSignIn()).isEqualTo("revoked");
    assertThat(SignInBundle.fromJson(new String(n.toJson())).needsSignIn()).isEqualTo("revoked");
  }

  @Test
  void anotherLayoutIsRefused() {
    assertThatThrownBy(() -> SignInBundle.fromJson("{\"format\":9}")).isInstanceOf(IllegalArgumentException.class);
  }
}
