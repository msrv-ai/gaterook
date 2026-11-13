package com.gaterook.core.mcpservers.internal.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The client secret of the person's OAuth app goes to the authorization server it was entered for or first signed in
 * with: the same issuer and the same token endpoint. A new version of the secret, or another client ID, is a new binding.
 */
class AppBindingTest {

  private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");
  private static final String ISSUER = "https://auth.chat.example.com";
  private static final URI TOKEN = URI.create("https://auth.chat.example.com/oauth/token");

  private static Discovery.Server server(String issuer, String token) {
    return new Discovery.Server(issuer, URI.create(issuer + "/authorize"), URI.create(token), null, null, List.of(),
        List.of("S256"), List.of("client_secret_basic"), false, false, false);
  }

  @Test
  void aBindingAllowsOnlyTheAuthorizationServerItWasMadeFor() {
    AppBinding b = AppBinding.of("chat-app", "sec_1@v1", ISSUER, TOKEN, NOW);

    assertThat(b.allows(server(ISSUER, TOKEN.toString()))).isTrue();
    assertThat(b.allows(server(ISSUER + "/", "https://AUTH.chat.example.com:443/oauth/token")))
        .as("the same addresses, written otherwise").isTrue();
    assertThat(b.allows(server("https://auth.other.example", "https://auth.other.example/oauth/token")))
        .as("another issuer").isFalse();
    assertThat(b.allows(server(ISSUER, "https://collector.example/oauth/token")))
        .as("the same issuer's name, tokens taken elsewhere").isFalse();
    assertThat(b.allows(server("https://auth.other.example", TOKEN.toString()))).as("another issuer's name").isFalse();
  }

  @Test
  void aBindingHoldsForOneClientIdAndOneVersionOfItsStoredSecret() {
    AppBinding b = AppBinding.of("chat-app", "sec_1@v1", ISSUER, TOKEN, NOW);

    assertThat(b.covers("chat-app", "sec_1@v1")).isTrue();
    assertThat(b.covers("chat-app", "sec_1@v2")).as("the secret entered again, whatever its value").isFalse();
    assertThat(b.covers("chat-app", "sec_2@v1")).as("another secret").isFalse();
    assertThat(b.covers("other-app", "sec_1@v1")).as("another app").isFalse();
  }

  @Test
  void aSignInFromBeforeBindingsBindsTheSecretItWasMadeWith() {
    OAuthClient.Client app = new OAuthClient.Client("chat-app", "chat-app-secret-5678", "client_secret_basic", false,
        "http://127.0.0.1:7471/callback");
    SignInBundle bundle = SignInBundle.signedIn("https://mcp.chat.example.com/mcp", "https://mcp.chat.example.com/mcp",
        server(ISSUER, TOKEN.toString()), app, null, new OAuthClient.Tokens("at-1x7Kq9Ws3Zp0Lm4Rt8Yv", null, null,
            null), NOW);

    AppBinding earlier = AppBinding.fromBundle(bundle, "chat-app", "chat-app-secret-5678", "sec_1@v3", NOW);
    assertThat(earlier).isEqualTo(AppBinding.of("chat-app", "sec_1@v3", ISSUER, TOKEN, NOW));

    assertThat(AppBinding.fromBundle(bundle, "chat-app", "another-secret-value", "sec_1@v4", NOW))
        .as("the person entered another secret since").isNull();
    assertThat(AppBinding.fromBundle(bundle, "other-app", "chat-app-secret-5678", "sec_1@v3", NOW)).isNull();
    assertThat(AppBinding.fromBundle(null, "chat-app", "chat-app-secret-5678", "sec_1@v3", NOW)).isNull();
    OAuthClient.Client registered = new OAuthClient.Client("chat-app", "chat-app-secret-5678", "client_secret_basic",
        true, "http://localhost:5/callback");
    assertThat(AppBinding.fromBundle(SignInBundle.signedIn("https://mcp.chat.example.com/mcp", null,
        server(ISSUER, TOKEN.toString()), registered, null, new OAuthClient.Tokens("at-1x7Kq9Ws3Zp0Lm4Rt8Yv", null,
            null, null), NOW), "chat-app", "chat-app-secret-5678", "sec_1@v3", NOW))
        .as("a client GateRook registered itself").isNull();
  }

  @Test
  void aReviewedServerMatchesTheSameIssuerAndTokenEndpointOnly() {
    AppBinding.Server reviewed = new AppBinding.Server(ISSUER, TOKEN.toString());

    assertThat(reviewed.matches(server(ISSUER, TOKEN.toString()))).isTrue();
    assertThat(reviewed.matches(server("https://auth.other.example", TOKEN.toString()))).isFalse();
    assertThat(reviewed.matches(server(ISSUER, "https://collector.example/token"))).isFalse();
    assertThat(new AppBinding.Server(null, TOKEN.toString()).matches(server(ISSUER, TOKEN.toString()))).isFalse();
  }

  @Test
  void itKeepsAsJsonOfItsOwnMaking() {
    AppBinding b = AppBinding.of("chat-app", "sec_1@v1", ISSUER, TOKEN, NOW);

    assertThat(AppBinding.fromJson(new String(b.toJson()))).isEqualTo(b);
    assertThatThrownBy(() -> AppBinding.fromJson("{\"format\": 2, \"clientId\": \"chat-app\"}"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
