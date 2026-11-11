package ai.msrv.gaterook.core.mcpservers.internal.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Microsoft identity platform's four exceptions (ARCHITECTURE.md §7.6), each held to Microsoft's own sign-in hosts:
 * S256 without its advertisement, no {@code resource} parameter with scopes that name the server, a public client
 * without a secret, and a loopback redirect on any port (the last in {@code McpServerMicrosoftSignInTest}). Microsoft's
 * answers are those of 2026-11-11: Azure DevOps' remote MCP server names its organization's tenant, whose v2.0 metadata
 * lists no PKCE methods, no {@code none} and no registration. Requests to Microsoft's and the server's real URLs reach
 * a local server here, so discovery sees the hosts it would see.
 */
class MicrosoftIdentityPlatformTest {

  private static final String TENANT = "72f988bf-86f1-41af-91ab-2d7cd011db47";
  private static final String ISSUER = "https://login.microsoftonline.com/" + TENANT + "/v2.0";
  private static final URI DEVOPS = URI.create("https://mcp.dev.azure.com/dnceng-public");
  private static final String SCOPE = "https://mcp.dev.azure.com/.default offline_access";
  private static final Instant NOW = Instant.parse("2026-11-11T16:00:00Z");
  private static final String AT = "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiJ9.e30.c2lnbmF0dXJl";
  private static final String RT = "1.AQ4Av4j5cvGGr0GRqy180BHbR0bQyhGFOoRCuTbkdfS1N-0OAPo.AgABAAEAAAD";

  /** What each host answers, by {@code <host> <method> <path>}; anything else is a 404. */
  private final Map<String, MockResponse> answers = new ConcurrentHashMap<>();
  private final List<RecordedRequest> seen = new CopyOnWriteArrayList<>();
  private MockWebServer server;
  private OAuthHttp http;

  @BeforeEach
  void start() throws Exception {
    server = new MockWebServer();
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        seen.add(request);
        MockResponse r = answers.get(request.getHeaders().get("X-Test-Host") + " " + request.getMethod() + " "
            + request.getUrl().encodedPath());
        return r != null ? r : new MockResponse.Builder().code(404).build();
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    // Every request goes to the local server, with the host it was for in a header: discovery reads Microsoft's URLs.
    OkHttpClient routed = new OkHttpClient.Builder().followRedirects(false).addInterceptor(chain -> {
      Request r = chain.request();
      HttpUrl to = r.url().newBuilder().scheme("http").host("127.0.0.1").port(server.getPort()).build();
      return chain.proceed(r.newBuilder().url(to).header("X-Test-Host", r.url().host()).build());
    }).build();
    http = new OAuthHttp(routed, Duration.ofSeconds(5), "GateRook/test");
  }

  @AfterEach
  void stop() {
    server.close();
  }

  // ── Microsoft's hosts ───────────────────────────────────────────────────────────────────────────────────────

  @Test
  void itsIssuersAreV2EndpointsOnMicrosoftsSignInHosts() {
    for (String issuer : List.of(ISSUER, ISSUER + "/", "https://login.microsoftonline.us/" + TENANT + "/v2.0",
        "https://login.partner.microsoftonline.cn/" + TENANT + "/v2.0",
        "https://login.microsoftonline.com/contoso.onmicrosoft.com/v2.0",
        "https://login.microsoftonline.com/organizations/v2.0", "https://LOGIN.MicrosoftOnline.com/" + TENANT
            + "/v2.0")) {
      assertThat(MicrosoftIdentityPlatform.issuer(issuer)).as(issuer).isTrue();
    }
  }

  @Test
  void lookalikesOtherHostsAndOtherEndpointsGetNoException() {
    for (String issuer : List.of(
        "https://login.microsoftonline.com.attacker.example/" + TENANT + "/v2.0",
        "https://attacker.example/login.microsoftonline.com/" + TENANT + "/v2.0",
        "https://login.microsoftonline.com@attacker.example/" + TENANT + "/v2.0",
        "https://login-microsoftonline.com/" + TENANT + "/v2.0",
        "https://login.microsoftonline.co/" + TENANT + "/v2.0",
        "https://login.microsoftonIine.com/" + TENANT + "/v2.0",
        "https://evil.login.microsoftonline.com/" + TENANT + "/v2.0",
        "https://login.microsoftonline.com./" + TENANT + "/v2.0",
        "http://login.microsoftonline.com/" + TENANT + "/v2.0",
        "https://login.microsoftonline.com:8443/" + TENANT + "/v2.0",
        "https://someone@login.microsoftonline.com/" + TENANT + "/v2.0",
        "https://login.microsoftonline.com/" + TENANT + "/v2.0?x=1",
        "https://login.microsoftonline.com/" + TENANT + "/v2.0/extra",
        "https://login.microsoftonline.com/v2.0",
        "https://login.microsoftonline.com/a%2Fb/v2.0",
        // Entra's v1 endpoints take the resource parameter and need none of this.
        "https://login.microsoftonline.com/" + TENANT + "/", "https://sts.windows.net/" + TENANT + "/",
        // Aliases that serve metadata naming login.microsoftonline.com, and Germany's closed cloud.
        "https://login.windows.net/" + TENANT + "/v2.0", "https://login.microsoft.com/" + TENANT + "/v2.0",
        "https://login.chinacloudapi.cn/" + TENANT + "/v2.0", "https://login.microsoftonline.de/" + TENANT + "/v2.0",
        // Azure AD B2C and Entra External ID sign in elsewhere, by their own rules.
        "https://contoso.b2clogin.com/" + TENANT + "/v2.0", "https://contoso.ciamlogin.com/" + TENANT + "/v2.0",
        "not a url", "")) {
      assertThat(MicrosoftIdentityPlatform.issuer(issuer)).as(issuer).isFalse();
    }
    assertThat(MicrosoftIdentityPlatform.issuer(null)).isFalse();
  }

  @Test
  void itServesOnlyWithBothEndpointsOnTheIssuersHost() {
    URI authorize = URI.create("https://login.microsoftonline.com/" + TENANT + "/oauth2/v2.0/authorize");
    URI token = URI.create("https://login.microsoftonline.com/" + TENANT + "/oauth2/v2.0/token");
    assertThat(MicrosoftIdentityPlatform.serves(ISSUER, authorize, token)).isTrue();
    assertThat(MicrosoftIdentityPlatform.renews(ISSUER, token)).isTrue();

    URI elsewhere = URI.create("https://attacker.example/" + TENANT + "/oauth2/v2.0/token");
    URI otherCloud = URI.create("https://login.microsoftonline.us/" + TENANT + "/oauth2/v2.0/token");
    URI plain = URI.create("http://login.microsoftonline.com/" + TENANT + "/oauth2/v2.0/token");
    for (URI t : List.of(elsewhere, otherCloud, plain)) {
      assertThat(MicrosoftIdentityPlatform.serves(ISSUER, authorize, t)).as(t.toString()).isFalse();
      assertThat(MicrosoftIdentityPlatform.renews(ISSUER, t)).as(t.toString()).isFalse();
    }
    assertThat(MicrosoftIdentityPlatform.serves(ISSUER, URI.create("https://attacker.example/authorize"), token))
        .isFalse();
    assertThat(MicrosoftIdentityPlatform.serves("https://attacker.example/" + TENANT + "/v2.0", authorize, token))
        .isFalse();
  }

  // ── PKCE ────────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void azureDevOpsAtMicrosoftIsAnOAuthServerWithoutResourceParameter() throws Exception {
    devOps("login.microsoftonline.com", entraMetadata("login.microsoftonline.com"));

    Discovery.Result d = Discovery.discover(http, DEVOPS);

    assertThat(d.kind()).isEqualTo(Discovery.Kind.OAUTH);
    assertThat(d.server().issuer()).isEqualTo(ISSUER);
    assertThat(d.server().microsoftIdentityPlatform()).isTrue();
    assertThat(d.server().challengeMethods()).as("as Microsoft publishes it").isEmpty();
    assertThat(d.server().dynamicRegistration()).isFalse();
    assertThat(d.server().clientMetadataDocuments()).isFalse();
    assertThat(d.metadata().resource()).isEqualTo("https://mcp.dev.azure.com/dnceng-public");
    assertThat(d.metadata().scopes()).containsExactly("https://mcp.dev.azure.com/.default");
    assertThat(d.resource()).as("the scopes name the audience instead").isNull();
  }

  @Test
  void theSovereignCloudsAreMicrosoftsToo() throws Exception {
    for (String host : List.of("login.microsoftonline.us", "login.partner.microsoftonline.cn")) {
      devOps(host, entraMetadata(host));
      Discovery.Result d = Discovery.discover(http, DEVOPS);
      assertThat(d.server().microsoftIdentityPlatform()).as(host).isTrue();
      assertThat(d.resource()).as(host).isNull();
    }
  }

  @Test
  void aLookalikeWithMicrosoftsMetadataStillNeedsS256AndKeepsTheResource() throws Exception {
    for (String host : List.of("login.microsoftonline.com.attacker.example", "login-microsoftonline.com",
        "evil.login.microsoftonline.com", "login.windows.net")) {
      devOps(host, entraMetadata(host));
      assertThatThrownBy(() -> Discovery.discover(http, DEVOPS)).as(host).isInstanceOf(OAuthHttp.Failure.class)
          .hasMessageContaining("doesn't say it supports PKCE with S256");

      // The same server with S256 advertised is an ordinary authorization server: the resource goes as usual.
      devOps(host, entraMetadata(host).replaceFirst("}$", ",\"code_challenge_methods_supported\":[\"S256\"]}"));
      Discovery.Result d = Discovery.discover(http, DEVOPS);
      assertThat(d.server().microsoftIdentityPlatform()).as(host).isFalse();
      assertThat(d.resource()).as(host).isEqualTo("https://mcp.dev.azure.com/dnceng-public");
    }
  }

  @Test
  void atMicrosoftS256IsTrustedOnlyWhereNoMethodsAreListedAndBothEndpointsAreItsOwn() throws Exception {
    devOps("login.microsoftonline.com", entraMetadata("login.microsoftonline.com")
        .replaceFirst("}$", ",\"code_challenge_methods_supported\":[\"plain\"]}"));
    assertThatThrownBy(() -> Discovery.discover(http, DEVOPS)).isInstanceOf(OAuthHttp.Failure.class)
        .hasMessageContaining("doesn't say it supports PKCE with S256");

    devOps("login.microsoftonline.com", entraMetadata("login.microsoftonline.com").replace(
        "\"token_endpoint\":\"https://login.microsoftonline.com/", "\"token_endpoint\":\"https://tokens.attacker.example/"));
    assertThatThrownBy(() -> Discovery.discover(http, DEVOPS)).as("tokens on another host")
        .isInstanceOf(OAuthHttp.Failure.class).hasMessageContaining("doesn't say it supports PKCE with S256");
  }

  // ── No resource: the scopes name the server ─────────────────────────────────────────────────────────────────

  @Test
  void scopesOfTheServerItselfNameTheTokensAudience() {
    assertThat(MicrosoftIdentityPlatform.audienceProblem(SCOPE, DEVOPS)).isNull();
    assertThat(MicrosoftIdentityPlatform.audienceProblem("https://mcp.dev.azure.com/dnceng-public/.default", DEVOPS))
        .as("the server's own URL").isNull();
    assertThat(MicrosoftIdentityPlatform.audienceProblem("openid profile email OFFLINE_ACCESS "
        + "https://mcp.dev.azure.com/vso.code", DEVOPS)).as("OpenID Connect's scopes name no resource").isNull();
  }

  @Test
  void scopesOfAnotherApiAreRefusedSoTheServerGetsNoTokenForIt() {
    for (String scope : List.of("https://graph.microsoft.com/.default", "User.Read offline_access",
        "https://mcp.dev.azure.com/.default Mail.Read", "2a72489c-aab2-4b65-b93a-a91edccf33b8/.default",
        "api://2a72489c-aab2-4b65-b93a-a91edccf33b8/access", ".default",
        "https://mcp.dev.azure.com.attacker.example/.default", "https://mcp.dev.azure.com/other-org/.default",
        "http://mcp.dev.azure.com/.default", "https://mcp.dev.azure.com:8443/.default",
        "https://someone@mcp.dev.azure.com/.default", "https://mcp.dev.azure.com?x/.default")) {
      assertThat(MicrosoftIdentityPlatform.audienceProblem(scope, DEVOPS)).as(scope)
          .contains("names another API").contains("https://mcp.dev.azure.com/.default");
    }
  }

  @Test
  void aLookalikeServerCantAskForAzureDevOpsTokens() {
    // A server that names Microsoft's sign-in and Azure DevOps' scope would otherwise receive the person's token.
    for (String url : List.of("https://mcp.dev.azure.com.attacker.example/dnceng-public",
        "https://attacker.example/mcp.dev.azure.com", "https://dev.azure.com/dnceng-public")) {
      assertThat(MicrosoftIdentityPlatform.audienceProblem(SCOPE, URI.create(url))).as(url)
          .contains("names another API");
    }
  }

  @Test
  void entrasSignInForAnyTenantIsRefusedWithWhatToDoInstead() throws Exception {
    // The root URL and an organization Azure DevOps doesn't know name the organizations endpoint, whose metadata
    // names the issuer {tenantid}.
    String organizations = "https://login.microsoftonline.com/organizations/v2.0";
    answers.put("mcp.dev.azure.com POST /", new MockResponse.Builder().code(401).addHeader("WWW-Authenticate",
        "Bearer resource_metadata=\"https://mcp.dev.azure.com/.well-known/oauth-protected-resource/\"").build());
    answers.put("mcp.dev.azure.com GET /.well-known/oauth-protected-resource/", json("{\"resource\":"
        + "\"https://mcp.dev.azure.com\",\"authorization_servers\":[\"" + organizations + "\"],\"scopes_supported\":"
        + "[\"https://mcp.dev.azure.com/.default\"]}"));
    answers.put("login.microsoftonline.com GET /organizations/v2.0/.well-known/openid-configuration",
        json(entraMetadata("login.microsoftonline.com").replace("/" + TENANT + "/v2.0\"", "/{tenantid}/v2.0\"")
            .replace(TENANT, "organizations")));

    assertThatThrownBy(() -> Discovery.discover(http, URI.create("https://mcp.dev.azure.com/")))
        .isInstanceOf(OAuthHttp.Failure.class).hasMessageContaining("sign-in for any tenant")
        .hasMessageContaining("https://mcp.dev.azure.com/<organization>");
  }

  @Test
  void scopesThatNameNoResourceAtAllAreRefusedToo() {
    // Microsoft issues a token for Microsoft Graph then.
    for (String scope : new String[] {null, "", "openid offline_access"}) {
      assertThat(MicrosoftIdentityPlatform.audienceProblem(scope, DEVOPS)).as(String.valueOf(scope))
          .contains("no scope names mcp.dev.azure.com").contains("https://mcp.dev.azure.com/.default");
    }
  }

  @Test
  void atMicrosoftTheAuthorizationRequestCarriesTheScopesButNoResource() throws Exception {
    devOps("login.microsoftonline.com", entraMetadata("login.microsoftonline.com"));
    Discovery.Result d = Discovery.discover(http, DEVOPS);
    OAuthClient.Client app = OAuthClient.preRegistered(d.server(), "11111111-2222-3333-4444-555555555555", null,
        URI.create("http://localhost:50123/callback"));

    HttpUrl url = HttpUrl.get(OAuthClient.authorizationUrl(d.server(), app, Pkce.create(), "st-1", SCOPE,
        d.resource()).toString());

    assertThat(url.host()).isEqualTo("login.microsoftonline.com");
    assertThat(url.queryParameterNames()).doesNotContain("resource");
    assertThat(url.queryParameter("scope")).isEqualTo(SCOPE);
    assertThat(url.queryParameter("code_challenge_method")).isEqualTo("S256");
    assertThat(url.queryParameter("redirect_uri")).isEqualTo("http://localhost:50123/callback");
  }

  @Test
  void atMicrosoftTokenRequestsCarryTheScopesWhichARenewalNeedsToo() throws Exception {
    devOps("login.microsoftonline.com", entraMetadata("login.microsoftonline.com"));
    Discovery.Result d = Discovery.discover(http, DEVOPS);
    OAuthClient.Client app = OAuthClient.preRegistered(d.server(), "11111111-2222-3333-4444-555555555555", null,
        URI.create("http://localhost:50123/callback"));
    String path = "/" + TENANT + "/oauth2/v2.0/token";
    answers.put("login.microsoftonline.com POST " + path, json("{\"token_type\":\"Bearer\",\"scope\":"
        + "\"https://mcp.dev.azure.com/Ado.Mcp.Tools\",\"expires_in\":4384,\"ext_expires_in\":4384,\"access_token\":\""
        + AT + "\",\"refresh_token\":\"" + RT + "\"}"));

    OAuthClient.Tokens tokens = OAuthClient.exchange(http, d.server().token(), app, "code-1", "verifier-1",
        d.resource(), OAuthClient.audienceScope(d.server(), SCOPE), NOW);
    SignInBundle bundle = SignInBundle.signedIn(Discovery.canonical(DEVOPS), d.resource(), d.server(), app, SCOPE,
        tokens, NOW);
    OAuthClient.refresh(http, bundle.tokenEndpoint(), bundle.client(), bundle.refreshToken(), bundle.resource(),
        bundle.renewalScope(), NOW);

    List<RecordedRequest> tokenRequests = seen.stream().filter(r -> r.getUrl().encodedPath().equals(path)).toList();
    assertThat(tokenRequests).hasSize(2);
    for (RecordedRequest r : tokenRequests) {
      Map<String, String> form = OAuthHttp.query(r.getBody().utf8());
      assertThat(form).as(form.get("grant_type")).doesNotContainKey("resource").doesNotContainKey("client_secret")
          .containsEntry("scope", SCOPE).containsEntry("client_id", "11111111-2222-3333-4444-555555555555");
      assertThat(r.getHeaders().get("Authorization")).as("a public client").isNull();
    }
    assertThat(OAuthHttp.query(tokenRequests.getLast().getBody().utf8())).containsEntry("grant_type",
        "refresh_token");
  }

  @Test
  void elsewhereTokenRequestsCarryTheResourceAndRenewalsNoScope() {
    Discovery.Server other = new Discovery.Server("https://login.microsoftonline.com.attacker.example/" + TENANT
        + "/v2.0", URI.create("https://login.microsoftonline.com.attacker.example/authorize"),
        URI.create("https://login.microsoftonline.com.attacker.example/token"), null, null, List.of("offline_access"),
        List.of("S256"), List.of("client_secret_basic"), false, false, false);
    assertThat(OAuthClient.audienceScope(other, SCOPE)).isNull();
    SignInBundle b = SignInBundle.signedIn(Discovery.canonical(DEVOPS), "https://mcp.dev.azure.com/dnceng-public",
        other, OAuthClient.preRegistered(other, "cid", null, URI.create("http://127.0.0.1:7471/callback")), SCOPE,
        new OAuthClient.Tokens(AT, NOW.plusSeconds(3600), RT, null), NOW);
    assertThat(b.renewalScope()).isNull();
    assertThat(b.resource()).isEqualTo("https://mcp.dev.azure.com/dnceng-public");
  }

  @Test
  void aMicrosoftSignInWithoutResourceSurvivesItsJson() throws Exception {
    devOps("login.microsoftonline.com", entraMetadata("login.microsoftonline.com"));
    Discovery.Result d = Discovery.discover(http, DEVOPS);
    OAuthClient.Client app = OAuthClient.preRegistered(d.server(), "cid", null,
        URI.create("http://localhost:50123/callback"));
    SignInBundle b = SignInBundle.signedIn(Discovery.canonical(DEVOPS), d.resource(), d.server(), app, SCOPE,
        new OAuthClient.Tokens(AT, NOW.plusSeconds(3600), RT, null), NOW);

    SignInBundle back = SignInBundle.fromJson(new String(b.toJson()));

    assertThat(back).isEqualTo(b);
    assertThat(back.resource()).isNull();
    assertThat(back.renewalScope()).isEqualTo(SCOPE);
    assertThat(new String(b.toJson())).doesNotContain("\"resource\"");
  }

  // ── Public clients ──────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void atMicrosoftThePersonsAppIsAPublicClientElsewhereOnlyWhereNoneIsListed() throws Exception {
    devOps("login.microsoftonline.com", entraMetadata("login.microsoftonline.com"));
    Discovery.Server entra = Discovery.discover(http, DEVOPS).server();
    assertThat(entra.authMethods()).doesNotContain("none");
    assertThat(OAuthClient.publicClients(entra)).isTrue();
    assertThat(OAuthClient.preRegistered(entra, "cid", null, URI.create("http://localhost:1/callback"))
        .authMethod()).isEqualTo("none");

    Discovery.Server lookalike = new Discovery.Server("https://login-microsoftonline.com/" + TENANT + "/v2.0",
        URI.create("https://login-microsoftonline.com/authorize"), URI.create("https://login-microsoftonline.com/token"),
        null, null, List.of(), List.of("S256"), entra.authMethods(), false, false, false);
    assertThat(OAuthClient.publicClients(lookalike)).isFalse();
    Discovery.Server open = new Discovery.Server("https://auth.example.com", URI.create("https://auth.example.com/a"),
        URI.create("https://auth.example.com/t"), null, null, List.of(), List.of("S256"), List.of("none"), false, false,
        false);
    assertThat(OAuthClient.publicClients(open)).isTrue();
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * Azure DevOps' remote server for {@code dnceng-public} as it answered on 2026-11-11, with the organization's tenant
   * at {@code host}, which serves {@code metadata} at its OpenID Connect Discovery URI only.
   */
  private void devOps(String host, String metadata) {
    answers.clear();
    String issuer = "https://" + host + "/" + TENANT + "/v2.0";
    answers.put("mcp.dev.azure.com POST /dnceng-public", new MockResponse.Builder().code(401).addHeader(
        "WWW-Authenticate", "Bearer resource_metadata=\"https://mcp.dev.azure.com/.well-known/oauth-protected-resource"
            + "/dnceng-public\"").build());
    answers.put("mcp.dev.azure.com GET /.well-known/oauth-protected-resource/dnceng-public", json("{\"resource\":"
        + "\"https://mcp.dev.azure.com/dnceng-public\",\"authorization_servers\":[\"" + issuer + "\"],"
        + "\"bearer_methods_supported\":[\"header\"],\"scopes_supported\":[\"https://mcp.dev.azure.com/.default\"]}"));
    answers.put(host + " GET /" + TENANT + "/v2.0/.well-known/openid-configuration", json(metadata));
  }

  /** The v2.0 metadata of a tenant at {@code host}, the keys GateRook reads as Microsoft published them. */
  private static String entraMetadata(String host) {
    String base = "https://" + host + "/" + TENANT;
    return "{\"token_endpoint\":\"" + base + "/oauth2/v2.0/token\",\"token_endpoint_auth_methods_supported\":"
        + "[\"client_secret_post\",\"private_key_jwt\",\"client_secret_basic\",\"self_signed_tls_client_auth\"],"
        + "\"jwks_uri\":\"" + base + "/discovery/v2.0/keys\",\"response_modes_supported\":[\"query\",\"fragment\","
        + "\"form_post\"],\"subject_types_supported\":[\"pairwise\"],\"id_token_signing_alg_values_supported\":"
        + "[\"RS256\"],\"response_types_supported\":[\"code\",\"id_token\",\"code id_token\",\"id_token token\"],"
        + "\"scopes_supported\":[\"openid\",\"profile\",\"email\",\"offline_access\"],\"issuer\":\"" + base + "/v2.0\","
        + "\"request_uri_parameter_supported\":false,\"userinfo_endpoint\":\"https://graph.microsoft.com/oidc/userinfo\","
        + "\"authorization_endpoint\":\"" + base + "/oauth2/v2.0/authorize\",\"device_authorization_endpoint\":\""
        + base + "/oauth2/v2.0/devicecode\",\"http_logout_supported\":true,\"frontchannel_logout_supported\":true,"
        + "\"end_session_endpoint\":\"" + base + "/oauth2/v2.0/logout\",\"claims_supported\":[\"sub\",\"iss\"],"
        + "\"kerberos_endpoint\":\"" + base + "/kerberos\",\"tenant_region_scope\":\"WW\",\"cloud_instance_name\":"
        + "\"microsoftonline.com\",\"cloud_graph_host_name\":\"graph.windows.net\",\"msgraph_host\":\"graph.microsoft.com\","
        + "\"rbac_url\":\"https://pas.windows.net\"}";
  }

  private static MockResponse json(String body) {
    return new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build();
  }
}
