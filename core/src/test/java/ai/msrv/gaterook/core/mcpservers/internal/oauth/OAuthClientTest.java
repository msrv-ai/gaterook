package ai.msrv.gaterook.core.mcpservers.internal.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

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
 * GateRook as an OAuth client: it signs in by its published metadata document where the server takes one, registers
 * under its own name as a native public client (or with the method the server offers) elsewhere, sends PKCE and the
 * resource, authenticates an app with a secret as RFC 6749 says, and turns the server's refusals into words for the
 * person.
 */
class OAuthClientTest {

  private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
  /** Tokens shaped as authorization servers issue them: the client refuses anything shorter or with spaces. */
  private static final String AT = "at-1x7Kq9Ws3Zp0Lm4Rt8Yv";
  private static final String AT2 = "at-2Fd8Gh4Jk6Ll0Pp2Oo9";
  private static final String RT = "rt-5Hn2Bc6Vx1Qa9Ze3Uj7";
  /** GitHub's: a user-to-server token and its refresh token. */
  private static final String GHO = "gho_16C7e42F292c6912E7710c838347Ae178B4a";
  private static final String GHR = "ghr_1B4a2e77838347a7E420ce178F2E7c6912E169246c34E1ccbF66C46812d16D5B1A9Dc86A1498";
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
    return as(authMethods, false);
  }

  private Discovery.Server as(List<String> authMethods, boolean metadataDocuments) {
    return new Discovery.Server(base, URI.create(base + "/authorize?tenant=a"), URI.create(base + "/token"),
        URI.create(base + "/register"), URI.create(base + "/revoke"), List.of("read", "offline_access"),
        List.of("S256"), authMethods, true, metadataDocuments, false);
  }

  @Test
  void theMetadataDocumentIsTheClientWhereTheServerTakesItPublicClientsAndTheRedirect() {
    URI anyPort = URI.create("http://localhost:0/callback");
    assertThat(OAuthClient.acceptsMetadataDocument(as(List.of("none", "client_secret_basic"), true), anyPort))
        .isTrue();
    assertThat(OAuthClient.acceptsMetadataDocument(as(List.of("none"), true),
        URI.create("http://127.0.0.1:7471/callback"))).as("the person's host and port").isTrue();
    assertThat(OAuthClient.acceptsMetadataDocument(as(List.of("none"), false), anyPort)).as("not advertised")
        .isFalse();
    assertThat(OAuthClient.acceptsMetadataDocument(as(List.of(), true), anyPort))
        .as("no methods listed: RFC 8414's default, client_secret_basic").isFalse();
    assertThat(OAuthClient.acceptsMetadataDocument(as(List.of("client_secret_basic", "private_key_jwt"), true),
        anyPort)).as("no public clients").isFalse();
    assertThat(OAuthClient.acceptsMetadataDocument(as(List.of("none"), true),
        URI.create("http://localhost:50123/oauth/done"))).as("a path the document doesn't list").isFalse();
  }

  @Test
  void byItsMetadataDocumentGateRookSignsInAsTheDocumentsUrlWithoutASecret() throws Exception {
    OAuthClient.Client c = OAuthClient.metadataDocument(URI.create("http://localhost:50123/callback"));
    assertThat(c.id()).isEqualTo(OAuthClient.METADATA_DOCUMENT).isEqualTo("https://gaterook.com/oauth/gaterook-client.json");
    assertThat(c.secret()).isNull();
    assertThat(c.authMethod()).isEqualTo("none");
    assertThat(c.registered()).as("nothing registered, nothing to reuse").isFalse();

    HttpUrl url = HttpUrl.get(OAuthClient.authorizationUrl(as(List.of("none"), true), c, Pkce.create(), "st-1", null,
        "https://mcp.example.com/mcp").toString());
    assertThat(url.queryParameter("client_id")).isEqualTo(OAuthClient.METADATA_DOCUMENT);
    assertThat(url.queryParameter("redirect_uri")).as("the listener's own port").isEqualTo(
        "http://localhost:50123/callback");

    server.enqueue(json(200, "{\"access_token\":\"" + AT + "\",\"token_type\":\"Bearer\",\"refresh_token\":\"" + RT
        + "\"}"));
    server.enqueue(json(200, "{\"access_token\":\"" + AT2 + "\",\"token_type\":\"Bearer\"}"));
    OAuthClient.exchange(http, URI.create(base + "/token"), c, "code-1", "verifier-1", "https://mcp.example.com/mcp",
        null, NOW);
    OAuthClient.refresh(http, URI.create(base + "/token"), c, "rt", "https://mcp.example.com/mcp", null, NOW);
    for (String grant : List.of("authorization_code", "refresh_token")) {
      RecordedRequest r = server.takeRequest();
      assertThat(r.getHeaders().get("Authorization")).as("a public client").isNull();
      assertThat(OAuthHttp.query(r.getBody().utf8())).containsEntry("grant_type", grant)
          .containsEntry("client_id", OAuthClient.METADATA_DOCUMENT).doesNotContainKey("client_secret");
    }
    assertThat(server.getRequestCount()).as("no registration request").isEqualTo(2);
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
    server.enqueue(json(200, "{\"access_token\":\"" + AT + "\",\"token_type\":\"bearer\",\"expires_in\":\"3600\","
        + "\"refresh_token\":\"" + RT + "\",\"scope\":\"read\"}"));
    OAuthClient.Client c = new OAuthClient.Client("c-1", null, "none", true, "http://localhost:5/callback");
    OAuthClient.Tokens t = OAuthClient.exchange(http, URI.create(base + "/token"), c, "code-1", "verifier-1",
        "https://mcp.example.com/mcp", null, NOW);
    assertThat(t.accessToken()).isEqualTo(AT);
    assertThat(t.refreshToken()).isEqualTo(RT);
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
    server.enqueue(json(200, "{\"access_token\":\"" + AT + "\",\"token_type\":\"Bearer\"}"));
    OAuthClient.Client c = OAuthClient.preRegistered(as(List.of("client_secret_basic")), "id:1", "s&cret",
        URI.create("http://127.0.0.1:7471/callback"));
    OAuthClient.Tokens t = OAuthClient.refresh(http, URI.create(base + "/token"), c, "rt-old", "res", null, NOW);
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
    server.enqueue(json(200, "{\"access_token\":\"" + AT + "\"}"));
    OAuthClient.Client c = OAuthClient.preRegistered(as(List.of("client_secret_post")), "id", "sec",
        URI.create("http://127.0.0.1:7471/callback"));
    OAuthClient.refresh(http, URI.create(base + "/token"), c, "rt", "res", null, NOW);
    RecordedRequest r = server.takeRequest();
    assertThat(r.getHeaders().get("Authorization")).isNull();
    assertThat(OAuthHttp.query(r.getBody().utf8())).containsEntry("client_id", "id").containsEntry("client_secret",
        "sec");
  }

  @Test
  void aRefusedGrantKeepsItsErrorCodeAndOtherTokenTypesAreRefused() {
    server.enqueue(json(400, "{\"error\":\"invalid_grant\",\"error_description\":\"Refresh token revoked\"}"));
    OAuthClient.Client c = new OAuthClient.Client("c", null, "none", true, "http://localhost:5/callback");
    assertThatThrownBy(() -> OAuthClient.refresh(http, URI.create(base + "/token"), c, "rt", "res", null, NOW))
        .isInstanceOfSatisfying(OAuthHttp.Failure.class, e -> {
          assertThat(e.error()).isEqualTo("invalid_grant");
          assertThat(e.getMessage()).contains("Refresh token revoked").doesNotContain("rt\"");
        });
    server.enqueue(json(200, "{\"access_token\":\"" + AT + "\",\"token_type\":\"DPoP\"}"));
    assertThatThrownBy(() -> OAuthClient.exchange(http, URI.create(base + "/token"), c, "x", "v", "r", null, NOW))
        .hasMessageContaining("DPoP");
  }

  @Test
  void aTokenThatIsntShapedLikeACredentialIsRefusedWithoutBeingNamed() {
    // Every token GateRook is handed is masked in every connection's results: an ordinary word as a token would be too.
    OAuthClient.Client c = new OAuthClient.Client("c", null, "none", true, "http://localhost:5/callback");
    for (String token : List.of("results", "fifteen-chars-1", "a token with spaces in it", "tab\\tinside-the-token-1",
        "not-ascii-\\u00e9-in-the-token", "x".repeat(OAuthClient.MAX_TOKEN_LENGTH + 1))) {
      server.enqueue(json(200, "{\"access_token\":\"" + token + "\",\"token_type\":\"Bearer\"}"));
      assertThatThrownBy(() -> OAuthClient.exchange(http, URI.create(base + "/token"), c, "x", "v", "r", null, NOW))
          .as(token.length() > 40 ? "too long" : token).isInstanceOf(OAuthHttp.Failure.class)
          .hasMessageContaining("an access token that isn't shaped like one").hasMessageNotContaining(token);
    }
    server.enqueue(json(200, "{\"access_token\":\"" + AT + "\",\"refresh_token\":\"refresh\"}"));
    assertThatThrownBy(() -> OAuthClient.refresh(http, URI.create(base + "/token"), c, RT, "r", null, NOW))
        .isInstanceOf(OAuthHttp.Failure.class).hasMessageContaining("a refresh token that isn't shaped like one");

    // The shortest and the longest that are taken.
    server.enqueue(json(200, "{\"access_token\":\"" + "k".repeat(OAuthClient.MIN_TOKEN_LENGTH) + "\"}"));
    assertDoesNotThrow(() -> OAuthClient.exchange(http, URI.create(base + "/token"), c, "x", "v", "r", null, NOW));
    server.enqueue(json(200, "{\"access_token\":\"" + "k".repeat(OAuthClient.MAX_TOKEN_LENGTH) + "\"}"));
    assertDoesNotThrow(() -> OAuthClient.exchange(http, URI.create(base + "/token"), c, "x", "v", "r", null, NOW));
  }

  /** An authorization server like GitHub's: registered apps only, the device grant, errors answered with HTTP 200. */
  private Discovery.Server forge() {
    return new Discovery.Server(base + "/login/oauth", URI.create(base + "/login/oauth/authorize"),
        URI.create(base + "/login/oauth/access_token"), null, null, List.of("offline_access"), List.of("S256"),
        List.of(), true, false, false, URI.create(base + "/login/device/code"),
        List.of("authorization_code", "refresh_token", Discovery.DEVICE_GRANT));
  }

  private OAuthClient.DeviceCode device(String userCode) {
    return new OAuthClient.DeviceCode("dc-1", userCode, URI.create(base + "/login/device"), null,
        NOW.plusSeconds(900), Duration.ofSeconds(5));
  }

  @Test
  void gateRooksOwnAppIsAPublicClientWithoutARedirect() {
    OAuthClient.Client c = OAuthClient.ownApp("Ov23liGateRookTest");
    assertThat(c.id()).isEqualTo("Ov23liGateRookTest");
    assertThat(c.secret()).isNull();
    assertThat(c.authMethod()).isEqualTo("none");
    assertThat(c.registered()).as("nothing GateRook registered at sign-in, nothing to reuse").isFalse();
    assertThat(c.redirectUri()).isNull();
  }

  @Test
  void aDeviceCodeIsAskedForWithTheClientIdScopesAndResourceOnly() throws Exception {
    server.enqueue(json(200, "{\"device_code\":\"dc-1\",\"user_code\":\"WDJB-MJHT\",\"verification_uri\":\"" + base
        + "/login/device\",\"expires_in\":899,\"interval\":5}"));
    OAuthClient.DeviceCode d = OAuthClient.deviceAuthorization(http, forge(), OAuthClient.ownApp("app-1"),
        "repo read:org offline_access", "https://api.githubcopilot.com/mcp/", NOW);

    assertThat(d.userCode()).isEqualTo("WDJB-MJHT");
    assertThat(d.verificationUri()).hasToString(base + "/login/device");
    assertThat(d.verificationUriComplete()).isNull();
    assertThat(d.expiresAt()).isEqualTo(NOW.plusSeconds(899));
    assertThat(d.interval()).isEqualTo(Duration.ofSeconds(5));
    assertThat(d.toString()).doesNotContain("dc-1");
    RecordedRequest r = server.takeRequest();
    assertThat(r.getUrl().encodedPath()).isEqualTo("/login/device/code");
    assertThat(r.getHeaders().get("Authorization")).isNull();
    assertThat(OAuthHttp.query(r.getBody().utf8())).containsOnly(Map.entry("client_id", "app-1"),
        Map.entry("scope", "repo read:org offline_access"), Map.entry("resource", "https://api.githubcopilot.com/mcp/"));
  }

  @Test
  void aDeviceAnswerIsKeptWithinBoundsAndOnTheServersHost() {
    server.enqueue(json(200, "{\"device_code\":\"dc-1\",\"user_code\":\"WDJB-MJHT\",\"verification_uri\":"
        + "\"https://elsewhere.example/device\",\"expires_in\":900}"));
    assertThatThrownBy(() -> OAuthClient.deviceAuthorization(http, forge(), OAuthClient.ownApp("app-1"), null, null,
        NOW)).isInstanceOf(OAuthHttp.Failure.class).hasMessageContaining("another host (elsewhere.example)");

    server.enqueue(json(200, "{\"device_code\":\"dc-1\",\"user_code\":\"<script>\\u0007\",\"verification_uri\":\""
        + base + "/login/device\"}"));
    assertThatThrownBy(() -> OAuthClient.deviceAuthorization(http, forge(), OAuthClient.ownApp("app-1"), null, null,
        NOW)).hasMessageContaining("a code GateRook can't show");

    // A page with the code in it, and numbers outside what GateRook keeps.
    server.enqueue(json(200, "{\"device_code\":\"dc-1\",\"user_code\":\"ABCD-EFGH\",\"verification_uri\":\"" + base
        + "/login/device\",\"verification_uri_complete\":\"" + base + "/login/device?user_code=ABCD-EFGH\","
        + "\"expires_in\":86400,\"interval\":\"0\"}"));
    OAuthClient.DeviceCode d = assertDoesNotThrow(() -> OAuthClient.deviceAuthorization(http, forge(),
        OAuthClient.ownApp("app-1"), null, null, NOW));
    assertThat(d.verificationUriComplete()).hasToString(base + "/login/device?user_code=ABCD-EFGH");
    assertThat(d.expiresAt()).isEqualTo(NOW.plus(OAuthClient.MAX_DEVICE_LIFE));
    assertThat(d.interval()).as("the RFC's default when it says nothing usable").isEqualTo(Duration.ofSeconds(5));
  }

  @Test
  void aRefusedDeviceRequestAnsweredWithHttp200KeepsItsErrorCode() {
    server.enqueue(json(200, "{\"error\":\"device_flow_disabled\",\"error_description\":\"Device Flow must be "
        + "explicitly enabled for this App\"}"));
    assertThatThrownBy(() -> OAuthClient.deviceAuthorization(http, forge(), OAuthClient.ownApp("app-1"), null, null,
        NOW)).isInstanceOfSatisfying(OAuthHttp.Failure.class, e -> {
          assertThat(e.error()).isEqualTo("device_flow_disabled");
          assertThat(e.getMessage()).contains("Device Flow must be explicitly enabled");
        });
  }

  @Test
  void pollingWaitsSlowsDownAndReadsTheTokensWithoutASecret() throws Exception {
    URI token = URI.create(base + "/login/oauth/access_token");
    OAuthClient.Client c = OAuthClient.ownApp("app-1");
    server.enqueue(json(200, "{\"error\":\"authorization_pending\",\"error_description\":\"The authorization request "
        + "is still pending.\"}"));
    server.enqueue(json(200, "{\"error\":\"slow_down\",\"interval\":10}"));
    server.enqueue(json(400, "{\"error\":\"slow_down\"}"));
    server.enqueue(json(400, "{\"error\":\"authorization_pending\"}"));
    server.enqueue(json(200, "{\"access_token\":\"" + GHO + "\",\"expires_in\":28800,\"refresh_token\":\"" + GHR + "\","
        + "\"refresh_token_expires_in\":15897600,\"token_type\":\"bearer\",\"scope\":\"repo,read:org\"}"));

    Duration five = Duration.ofSeconds(5);
    OAuthClient.DevicePoll p = OAuthClient.pollDevice(http, token, c, device("WDJB-MJHT"), five, "res", NOW);
    assertThat(p.pending()).isTrue();
    assertThat(p.interval()).isEqualTo(five);
    p = OAuthClient.pollDevice(http, token, c, device("WDJB-MJHT"), p.interval(), "res", NOW);
    assertThat(p.interval()).as("GitHub names the new interval").isEqualTo(Duration.ofSeconds(10));
    p = OAuthClient.pollDevice(http, token, c, device("WDJB-MJHT"), p.interval(), "res", NOW);
    assertThat(p.interval()).as("the RFC: five seconds more, also with HTTP 400").isEqualTo(Duration.ofSeconds(15));
    p = OAuthClient.pollDevice(http, token, c, device("WDJB-MJHT"), p.interval(), "res", NOW);
    assertThat(p.pending()).isTrue();
    p = OAuthClient.pollDevice(http, token, c, device("WDJB-MJHT"), p.interval(), "res", NOW);

    assertThat(p.pending()).isFalse();
    assertThat(p.tokens().accessToken()).isEqualTo(GHO);
    assertThat(p.tokens().refreshToken()).isEqualTo(GHR);
    assertThat(p.tokens().expiresAt()).isEqualTo(NOW.plusSeconds(28800));
    for (int i = 0; i < 5; i++) {
      RecordedRequest r = server.takeRequest();
      assertThat(r.getHeaders().get("Authorization")).isNull();
      assertThat(OAuthHttp.query(r.getBody().utf8())).containsOnly(Map.entry("grant_type", Discovery.DEVICE_GRANT),
          Map.entry("device_code", "dc-1"), Map.entry("resource", "res"), Map.entry("client_id", "app-1"));
    }
  }

  @Test
  void pollingEndsWhenAccessIsntAllowedOrTheCodeExpires() {
    URI token = URI.create(base + "/login/oauth/access_token");
    OAuthClient.Client c = OAuthClient.ownApp("app-1");
    server.enqueue(json(200, "{\"error\":\"access_denied\"}"));
    assertThatThrownBy(() -> OAuthClient.pollDevice(http, token, c, device("A"), Duration.ofSeconds(5), null, NOW))
        .isInstanceOfSatisfying(OAuthHttp.Failure.class, e -> {
          assertThat(e.error()).isEqualTo("access_denied");
          assertThat(e.getMessage()).isEqualTo("Access wasn't allowed on 127.0.0.1, so the sign-in ended.");
        });
    for (String expired : List.of("expired_token", "token_expired")) {
      server.enqueue(json(200, "{\"error\":\"" + expired + "\"}"));
      assertThatThrownBy(() -> OAuthClient.pollDevice(http, token, c, device("A"), Duration.ofSeconds(5), null, NOW))
          .isInstanceOfSatisfying(OAuthHttp.Failure.class, e -> assertThat(e.error()).isEqualTo("expired_token"));
    }
    server.enqueue(json(200, "{\"error\":\"incorrect_client_credentials\",\"error_description\":\"The client_id "
        + "and/or client_secret passed are incorrect.\"}"));
    assertThatThrownBy(() -> OAuthClient.pollDevice(http, token, c, device("A"), Duration.ofSeconds(5), null, NOW))
        .isInstanceOfSatisfying(OAuthHttp.Failure.class, e -> {
          assertThat(e.error()).isEqualTo("incorrect_client_credentials");
          assertThat(e.getMessage()).contains("are incorrect");
        });
  }

  @Test
  void aRefusedRenewalAnsweredWithHttp200KeepsItsErrorCode() {
    // GitHub refuses a refresh token it no longer takes with HTTP 200.
    server.enqueue(json(200, "{\"error\":\"bad_refresh_token\",\"error_description\":\"The refresh token passed is "
        + "incorrect or expired.\"}"));
    assertThatThrownBy(() -> OAuthClient.refresh(http, URI.create(base + "/token"), OAuthClient.ownApp("app-1"),
        "ghr_old", "res", null, NOW)).isInstanceOfSatisfying(OAuthHttp.Failure.class, e -> {
          assertThat(e.error()).isEqualTo("bad_refresh_token");
          assertThat(e.getMessage()).contains("incorrect or expired").doesNotContain("ghr_old");
        });
  }

  private static MockResponse json(int code, String body) {
    return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build();
  }
}
