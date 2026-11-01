package ai.msrv.gaterook.core.mcpservers.internal.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * GateRook as an OAuth client: it registers under its own name as a native public client (or with the method the
 * server offers), sends PKCE and the resource, authenticates an app with a secret as RFC 6749 says, and turns the
 * server's refusals into words for the person.
 */
class OAuthClientTest {

  private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
  private MockWebServer server;
  private OAuthHttp http;
  private String base;

  @BeforeEach
  void start() throws Exception {
    server = new MockWebServer();
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    base = "http://127.0.0.1:" + server.getPort();
    http = new OAuthHttp(new OkHttpClient.Builder().followRedirects(false).build(), Duration.ofSeconds(5),
        "GateRook/test");
  }

  @AfterEach
  void stop() {
    server.close();
  }

  private Discovery.Server as(List<String> authMethods) {
    return new Discovery.Server(base, URI.create(base + "/authorize?tenant=a"), URI.create(base + "/token"),
        URI.create(base + "/register"), URI.create(base + "/revoke"), List.of("read", "offline_access"),
        List.of("S256"), authMethods, true, false, false);
  }

  @Test
  void registrationIsANativePublicClientNamedGateRook() throws Exception {
    server.enqueue(json(201, "{\"client_id\":\"c-1\",\"token_endpoint_auth_method\":\"none\"}"));
    OAuthClient.Client c = OAuthClient.register(http, as(List.of("none", "client_secret_basic")),
        URI.create("http://localhost:50123/callback"));
    assertThat(c.id()).isEqualTo("c-1");
    assertThat(c.secret()).isNull();
    assertThat(c.authMethod()).isEqualTo("none");
    assertThat(c.registered()).isTrue();
    JsonNode body = OAuthHttp.JSON.readTree(server.takeRequest().getBody().utf8());
    assertThat(body.path("client_name").asString()).isEqualTo("GateRook");
    assertThat(body.path("application_type").asString()).isEqualTo("native");
    assertThat(body.path("token_endpoint_auth_method").asString()).isEqualTo("none");
    assertThat(body.path("redirect_uris")).extracting(JsonNode::asString)
        .containsExactly("http://localhost:50123/callback");
    assertThat(body.path("grant_types")).extracting(JsonNode::asString)
        .containsExactly("authorization_code", "refresh_token");
  }

  @Test
  void aServerWithoutPublicClientsGetsAskedForASecretItIssues() throws Exception {
    server.enqueue(json(201, "{\"client_id\":\"c-2\",\"client_secret\":\"s-2\"}"));
    OAuthClient.Client c = OAuthClient.register(http, as(List.of("client_secret_post", "client_secret_basic")),
        URI.create("http://localhost:1/callback"));
    assertThat(OAuthHttp.JSON.readTree(server.takeRequest().getBody().utf8()).path("token_endpoint_auth_method")
        .asString()).isEqualTo("client_secret_basic");
    assertThat(c.secret()).isEqualTo("s-2");
    assertThat(c.authMethod()).isEqualTo("client_secret_basic");
  }

  @Test
  void aRegistrationForApprovedAppsOnlySaysSo() {
    server.enqueue(json(403, "{\"error\":\"forbidden\"}"));
    assertThatThrownBy(() -> OAuthClient.register(http, as(List.of()), URI.create("http://localhost:1/callback")))
        .isInstanceOf(OAuthHttp.Failure.class).hasMessageContaining("registers only the apps it approved");
  }

  @Test
  void aRefusedRedirectUriCarriesTheServersError() {
    server.enqueue(json(400, "{\"error\":\"invalid_redirect_uri\",\"error_description\":\"not allowed\"}"));
    assertThatThrownBy(() -> OAuthClient.register(http, as(List.of()), URI.create("http://127.0.0.1:1/callback")))
        .isInstanceOfSatisfying(OAuthHttp.Failure.class, e -> {
          assertThat(e.error()).isEqualTo("invalid_redirect_uri");
          assertThat(e.getMessage()).contains("not allowed");
        });
  }

  @Test
  void theAuthorizationUrlHasPkceStateTheResourceAndKeepsTheEndpointsQuery() {
    OAuthClient.Client c = new OAuthClient.Client("c-1", null, "none", true, "http://localhost:5/callback");
    Pkce pkce = Pkce.create();
    HttpUrl url = HttpUrl.get(OAuthClient.authorizationUrl(as(List.of()), c, pkce, "st-1", "read offline_access",
        "https://mcp.example.com/mcp").toString());
    assertThat(url.queryParameter("tenant")).isEqualTo("a");
    assertThat(url.queryParameter("response_type")).isEqualTo("code");
    assertThat(url.queryParameter("client_id")).isEqualTo("c-1");
    assertThat(url.queryParameter("redirect_uri")).isEqualTo("http://localhost:5/callback");
    assertThat(url.queryParameter("code_challenge")).isEqualTo(pkce.challenge());
    assertThat(url.queryParameter("code_challenge_method")).isEqualTo("S256");
    assertThat(url.queryParameter("state")).isEqualTo("st-1");
    assertThat(url.queryParameter("scope")).isEqualTo("read offline_access");
    assertThat(url.queryParameter("resource")).isEqualTo("https://mcp.example.com/mcp");
  }

  @Test
  void theCodeExchangeSendsTheVerifierAndResourceAndReadsTheTokens() throws Exception {
    server.enqueue(json(200, "{\"access_token\":\"at\",\"token_type\":\"bearer\",\"expires_in\":\"3600\","
        + "\"refresh_token\":\"rt\",\"scope\":\"read\"}"));
    OAuthClient.Client c = new OAuthClient.Client("c-1", null, "none", true, "http://localhost:5/callback");
    OAuthClient.Tokens t = OAuthClient.exchange(http, URI.create(base + "/token"), c, "code-1", "verifier-1",
        "https://mcp.example.com/mcp", NOW);
    assertThat(t.accessToken()).isEqualTo("at");
    assertThat(t.refreshToken()).isEqualTo("rt");
    assertThat(t.expiresAt()).isEqualTo(NOW.plusSeconds(3600));
    RecordedRequest r = server.takeRequest();
    assertThat(r.getHeaders().get("Accept")).isEqualTo("application/json");
    Map<String, String> form = OAuthHttp.query(r.getBody().utf8());
    assertThat(form).containsEntry("grant_type", "authorization_code").containsEntry("code", "code-1")
        .containsEntry("code_verifier", "verifier-1").containsEntry("client_id", "c-1")
        .containsEntry("redirect_uri", "http://localhost:5/callback")
        .containsEntry("resource", "https://mcp.example.com/mcp");
  }

  @Test
  void anAppsSecretGoesAsBasicAuthenticationFormEncodedFirst() throws Exception {
    server.enqueue(json(200, "{\"access_token\":\"at\",\"token_type\":\"Bearer\"}"));
    OAuthClient.Client c = OAuthClient.preRegistered(as(List.of("client_secret_basic")), "id:1", "s&cret",
        URI.create("http://127.0.0.1:7471/callback"));
    OAuthClient.Tokens t = OAuthClient.refresh(http, URI.create(base + "/token"), c, "rt-old", "res", NOW);
    assertThat(t.refreshToken()).as("no new one: the old one stays").isEqualTo("rt-old");
    assertThat(t.expiresAt()).isNull();
    RecordedRequest r = server.takeRequest();
    String basic = new String(Base64.getDecoder().decode(r.getHeaders().get("Authorization").substring(6)),
        StandardCharsets.UTF_8);
    assertThat(basic).isEqualTo("id%3A1:s%26cret");
    assertThat(OAuthHttp.query(r.getBody().utf8())).doesNotContainKey("client_secret")
        .containsEntry("grant_type", "refresh_token").containsEntry("refresh_token", "rt-old");
  }

  @Test
  void anAppThatOnlyTakesThePostMethodGetsItsSecretInTheForm() throws Exception {
    server.enqueue(json(200, "{\"access_token\":\"at\"}"));
    OAuthClient.Client c = OAuthClient.preRegistered(as(List.of("client_secret_post")), "id", "sec",
        URI.create("http://127.0.0.1:7471/callback"));
    OAuthClient.refresh(http, URI.create(base + "/token"), c, "rt", "res", NOW);
    RecordedRequest r = server.takeRequest();
    assertThat(r.getHeaders().get("Authorization")).isNull();
    assertThat(OAuthHttp.query(r.getBody().utf8())).containsEntry("client_id", "id").containsEntry("client_secret",
        "sec");
  }

  @Test
  void aRefusedGrantKeepsItsErrorCodeAndOtherTokenTypesAreRefused() {
    server.enqueue(json(400, "{\"error\":\"invalid_grant\",\"error_description\":\"Refresh token revoked\"}"));
    OAuthClient.Client c = new OAuthClient.Client("c", null, "none", true, "http://localhost:5/callback");
    assertThatThrownBy(() -> OAuthClient.refresh(http, URI.create(base + "/token"), c, "rt", "res", NOW))
        .isInstanceOfSatisfying(OAuthHttp.Failure.class, e -> {
          assertThat(e.error()).isEqualTo("invalid_grant");
          assertThat(e.getMessage()).contains("Refresh token revoked").doesNotContain("rt\"");
        });
    server.enqueue(json(200, "{\"access_token\":\"at\",\"token_type\":\"DPoP\"}"));
    assertThatThrownBy(() -> OAuthClient.exchange(http, URI.create(base + "/token"), c, "x", "v", "r", NOW))
        .hasMessageContaining("DPoP");
  }

  private static MockResponse json(int code, String body) {
    return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build();
  }
}
