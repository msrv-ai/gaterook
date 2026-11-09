package ai.msrv.gaterook.core.mcpservers.internal.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Discovery as the servers measured on 2026-11-01 behave: a 401 that names its resource metadata (Linear, Notion,
 * Sentry), one that doesn't but serves it at the path-inserted well-known URI (Zapier), a resource identifier that is a
 * prefix of the URL (Slack, Asana) or differs by a trailing slash (Vercel), no resource metadata at all (Atlassian v1,
 * the 2025-03-26 revision), an issuer with a path (Atlassian v2, Stripe), and the refusals a client owes the person.
 */
class DiscoveryTest {

  private final Map<String, MockResponse> answers = new ConcurrentHashMap<>();
  private final List<RecordedRequest> seen = new CopyOnWriteArrayList<>();
  private MockWebServer server;
  private OAuthHttp http;
  private String base;

  @BeforeEach
  void start() throws Exception {
    server = new MockWebServer();
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        seen.add(request);
        MockResponse r = answers.get(request.getMethod() + " " + request.getUrl().encodedPath());
        return r != null ? r : new MockResponse.Builder().code(404).build();
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    base = "http://127.0.0.1:" + server.getPort();
    http = new OAuthHttp(new OkHttpClient.Builder().followRedirects(false).build(), Duration.ofSeconds(5),
        "GateRook/test");
  }

  @AfterEach
  void stop() {
    server.close();
  }

  @Test
  void a401NamingItsMetadataLeadsToTheAuthorizationServer() throws Exception {
    unauthorized("/mcp", "Bearer realm=\"OAuth\", resource_metadata=\"" + base
        + "/.well-known/oauth-protected-resource/mcp\", scope=\"read write\"");
    json("/.well-known/oauth-protected-resource/mcp", "{\"resource\":\"" + base + "/mcp\",\"authorization_servers\":[\""
        + base + "\"],\"scopes_supported\":[\"read\",\"write\"]}");
    json("/.well-known/oauth-authorization-server", authServer(base, true));

    Discovery.Result d = Discovery.discover(http, URI.create(base + "/mcp"));

    assertThat(d.kind()).isEqualTo(Discovery.Kind.OAUTH);
    assertThat(d.resource()).isEqualTo(base + "/mcp");
    assertThat(d.scope()).isEqualTo("read write");
    assertThat(d.server().issuer()).isEqualTo(base);
    assertThat(d.server().token()).hasToString(base + "/token");
    assertThat(d.server().dynamicRegistration()).isTrue();
    assertThat(d.server().issParameter()).isTrue();
    assertThat(d.server().clientMetadataDocuments()).as("not advertised").isFalse();
    RecordedRequest probe = seen.getFirst();
    assertThat(probe.getMethod()).isEqualTo("POST");
    assertThat(probe.getBody().utf8()).contains("\"method\":\"initialize\"");
    assertThat(probe.getHeaders().get("Authorization")).isNull();
  }

  @Test
  void anAuthorizationServerThatTakesClientMetadataDocumentsSaysSoBesideRegistration() throws Exception {
    // Linear, Notion, Sentry and Atlassian v2 among others (2026-11-01): both, and public clients.
    unauthorized("/mcp", "Bearer resource_metadata=\"" + base + "/prm\"");
    json("/prm", "{\"resource\":\"" + base + "/mcp\",\"authorization_servers\":[\"" + base + "\"]}");
    json("/.well-known/oauth-authorization-server", authServer(base, true).replaceFirst("}$",
        ",\"client_id_metadata_document_supported\":true,\"token_endpoint_auth_methods_supported\":[\"none\"]}"));

    Discovery.Server s = Discovery.discover(http, URI.create(base + "/mcp")).server();

    assertThat(s.clientMetadataDocuments()).isTrue();
    assertThat(s.authMethods()).containsExactly("none");
    assertThat(s.dynamicRegistration()).isTrue();
  }

  @Test
  void anAuthorizationServerForRegisteredAppsOnlyMayOfferTheDeviceGrant() throws Exception {
    // GitHub (2026-11-09): api.githubcopilot.com/mcp/ names github.com/login/oauth, which has neither registration nor
    // metadata documents, lists no authentication methods, and takes the device authorization grant.
    unauthorized("/mcp/", "Bearer error=\"invalid_request\", error_description=\"No access token was provided in this "
        + "request\", resource_metadata=\"" + base + "/.well-known/oauth-protected-resource/mcp/\"");
    json("/.well-known/oauth-protected-resource/mcp/", "{\"resource\":\"" + base + "/mcp/\",\"authorization_servers\":"
        + "[\"" + base + "/login/oauth\"],\"scopes_supported\":[\"repo\",\"read:org\"],\"bearer_methods_supported\":"
        + "[\"header\"],\"resource_name\":\"GitHub MCP Server\"}");
    json("/.well-known/oauth-authorization-server/login/oauth", "{\"issuer\":\"" + base + "/login/oauth\","
        + "\"authorization_endpoint\":\"" + base + "/login/oauth/authorize\",\"token_endpoint\":\"" + base
        + "/login/oauth/access_token\",\"response_types_supported\":[\"code\"],\"grant_types_supported\":"
        + "[\"authorization_code\",\"refresh_token\",\"urn:ietf:params:oauth:grant-type:device_code\"],"
        + "\"code_challenge_methods_supported\":[\"S256\"],\"device_authorization_endpoint\":\"" + base
        + "/login/device/code\",\"authorization_response_iss_parameter_supported\":true,"
        + "\"scopes_supported\":[\"offline_access\"]}");

    Discovery.Result d = Discovery.discover(http, URI.create(base + "/mcp/"));

    assertThat(d.kind()).isEqualTo(Discovery.Kind.OAUTH);
    assertThat(d.metadata().scopes()).containsExactly("repo", "read:org");
    Discovery.Server s = d.server();
    assertThat(s.issuer()).isEqualTo(base + "/login/oauth");
    assertThat(s.dynamicRegistration()).isFalse();
    assertThat(s.clientMetadataDocuments()).isFalse();
    assertThat(s.authMethods()).isEmpty();
    assertThat(s.deviceAuthorization()).hasToString(base + "/login/device/code");
    assertThat(s.deviceGrant()).isTrue();
  }

  @Test
  void aDeviceEndpointIsUsedOnlyWhereTheGrantIsListedAndTheUrlPassesTheRules() throws Exception {
    unauthorized("/mcp", "Bearer resource_metadata=\"" + base + "/prm\"");
    json("/prm", "{\"resource\":\"" + base + "/mcp\",\"authorization_servers\":[\"" + base + "\"]}");
    String withDevice = authServer(base, false).replaceFirst("}$", ",\"device_authorization_endpoint\":\"" + base
        + "/device\"}");

    json("/.well-known/oauth-authorization-server", withDevice);
    assertThat(Discovery.discover(http, URI.create(base + "/mcp")).server().deviceGrant())
        .as("no grants listed: the endpoint says it").isTrue();

    json("/.well-known/oauth-authorization-server", withDevice.replaceFirst("}$",
        ",\"grant_types_supported\":[\"authorization_code\",\"refresh_token\"]}"));
    assertThat(Discovery.discover(http, URI.create(base + "/mcp")).server().deviceGrant())
        .as("grants listed without it").isFalse();

    json("/.well-known/oauth-authorization-server", authServer(base, false).replaceFirst("}$",
        ",\"device_authorization_endpoint\":\"http://device.example.com/code\"}"));
    Discovery.Server plain = Discovery.discover(http, URI.create(base + "/mcp")).server();
    assertThat(plain.deviceAuthorization()).as("plain HTTP elsewhere: left out, the rest still signs in").isNull();
    assertThat(plain.deviceGrant()).isFalse();
  }

  @Test
  void withoutResourceMetadataInThe401ThePathInsertedWellKnownUriIsTried() throws Exception {
    unauthorized("/api/mcp", "Bearer realm=\"zapier\"");
    // Slack and Asana: the resource is the origin, a prefix of the URL.
    json("/.well-known/oauth-protected-resource/api/mcp", "{\"resource\":\"" + base + "/\",\"authorization_servers\":"
        + "[\"" + base + "/tenant\"]}");
    json("/.well-known/oauth-authorization-server/tenant", authServer(base + "/tenant", false));

    Discovery.Result d = Discovery.discover(http, URI.create(base + "/api/mcp"));

    assertThat(d.kind()).isEqualTo(Discovery.Kind.OAUTH);
    assertThat(d.resource()).isEqualTo(base + "/");
    assertThat(d.server().issuer()).isEqualTo(base + "/tenant");
    assertThat(d.server().dynamicRegistration()).isFalse();
  }

  @Test
  void anIssuerWithAPathFallsBackToOpenIdDiscovery() throws Exception {
    unauthorized("/mcp", "Bearer resource_metadata=\"" + base + "/prm\"");
    json("/prm", "{\"resource\":\"" + base + "/mcp/\",\"authorization_servers\":[\"" + base + "/realms/a\"]}");
    json("/realms/a/.well-known/openid-configuration", authServer(base + "/realms/a", false));

    Discovery.Result d = Discovery.discover(http, URI.create(base + "/mcp"));

    assertThat(d.server().issuer()).isEqualTo(base + "/realms/a");
    assertThat(seen).extracting(r -> r.getUrl().encodedPath()).containsSubsequence(
        "/.well-known/oauth-authorization-server/realms/a", "/.well-known/openid-configuration/realms/a",
        "/realms/a/.well-known/openid-configuration");
  }

  @Test
  void aServerOfThe20250326RevisionIsItsOwnAuthorizationServer() throws Exception {
    unauthorized("/v1/mcp", "Bearer realm=\"OAuth\"");
    json("/.well-known/oauth-authorization-server", authServer(base, true));

    Discovery.Result d = Discovery.discover(http, URI.create(base + "/v1/mcp"));

    assertThat(d.kind()).isEqualTo(Discovery.Kind.OAUTH);
    assertThat(d.metadata()).isNull();
    assertThat(d.resource()).isEqualTo(base + "/v1/mcp");
    assertThat(d.server().defaults()).isFalse();
  }

  @Test
  void withoutAnyMetadataTheOldDefaultEndpointsAreUsed() throws Exception {
    unauthorized("/mcp", "Bearer");
    Discovery.Result d = Discovery.discover(http, URI.create(base + "/mcp"));
    assertThat(d.server().defaults()).isTrue();
    assertThat(d.server().authorization()).hasToString(base + "/authorize");
    assertThat(d.server().registration()).hasToString(base + "/register");
  }

  @Test
  void metadataForAnotherResourceIsRefused() {
    unauthorized("/mcp", "Bearer resource_metadata=\"" + base + "/prm\"");
    json("/prm", "{\"resource\":\"https://elsewhere.example/mcp\",\"authorization_servers\":[\"" + base + "\"]}");
    assertThatThrownBy(() -> Discovery.discover(http, URI.create(base + "/mcp")))
        .isInstanceOf(OAuthHttp.Failure.class).hasMessageContaining("another resource");
  }

  @Test
  void metadataNamingAnotherIssuerIsRefused() {
    unauthorized("/mcp", "Bearer resource_metadata=\"" + base + "/prm\"");
    json("/prm", "{\"resource\":\"" + base + "/mcp\",\"authorization_servers\":[\"" + base + "\"]}");
    json("/.well-known/oauth-authorization-server", authServer("https://evil.example", true));
    assertThatThrownBy(() -> Discovery.discover(http, URI.create(base + "/mcp")))
        .isInstanceOf(OAuthHttp.Failure.class).hasMessageContaining("another issuer");
  }

  @Test
  void anAuthorizationServerThatDoesntSayS256IsRefused() {
    unauthorized("/mcp", "Bearer resource_metadata=\"" + base + "/prm\"");
    json("/prm", "{\"resource\":\"" + base + "/mcp\",\"authorization_servers\":[\"" + base + "\"]}");
    json("/.well-known/oauth-authorization-server", "{\"issuer\":\"" + base + "\",\"authorization_endpoint\":\"" + base
        + "/authorize\",\"token_endpoint\":\"" + base + "/token\"}");
    assertThatThrownBy(() -> Discovery.discover(http, URI.create(base + "/mcp")))
        .isInstanceOf(OAuthHttp.Failure.class).hasMessageContaining("S256");
  }

  @Test
  void aServerThatAnswersIsNoneOrOptionalAndItsSessionIsEnded() throws Exception {
    answers.put("POST /open", new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
        .addHeader("Mcp-Session-Id", "s-1").body("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}").build());
    answers.put("DELETE /open", new MockResponse.Builder().code(204).build());
    assertThat(Discovery.discover(http, URI.create(base + "/open")).kind()).isEqualTo(Discovery.Kind.NONE);
    assertThat(seen).anySatisfy(r -> {
      assertThat(r.getMethod()).isEqualTo("DELETE");
      assertThat(r.getHeaders().get("Mcp-Session-Id")).isEqualTo("s-1");
    });

    json("/.well-known/oauth-protected-resource/open", "{\"resource\":\"" + base + "/open\",\"authorization_servers\":"
        + "[\"" + base + "\"]}");
    json("/.well-known/oauth-authorization-server", authServer(base, true));
    assertThat(Discovery.discover(http, URI.create(base + "/open")).kind()).isEqualTo(Discovery.Kind.OPTIONAL);
  }

  @Test
  void anUnknownAnswerIsReportedNotThrown() throws Exception {
    answers.put("POST /sse", new MockResponse.Builder().code(405).build());
    Discovery.Result d = Discovery.discover(http, URI.create(base + "/sse"));
    assertThat(d.kind()).isEqualTo(Discovery.Kind.UNKNOWN);
    assertThat(d.message()).contains("HTTP 405").contains("/mcp");
  }

  @Test
  void resourcesMatchByOriginAndPathPrefix() {
    URI url = URI.create("https://mcp.example.com/v2/mcp");
    assertThat(Discovery.resourceAllowed(url, "https://mcp.example.com")).isTrue();
    assertThat(Discovery.resourceAllowed(url, "https://mcp.example.com/v2/mcp/")).isTrue();
    assertThat(Discovery.resourceAllowed(url, "https://MCP.example.com:443/v2")).isTrue();
    assertThat(Discovery.resourceAllowed(url, "https://mcp.example.com/v2/mc")).isFalse();
    assertThat(Discovery.resourceAllowed(url, "https://mcp.example.com/v1")).isFalse();
    assertThat(Discovery.resourceAllowed(url, "http://mcp.example.com/v2/mcp")).isFalse();
    assertThat(Discovery.resourceAllowed(url, "https://mcp.example.com.evil/v2/mcp")).isFalse();
    assertThat(Discovery.canonical(URI.create("HTTPS://Mcp.Example.com:443/mcp"))).isEqualTo(
        "https://mcp.example.com/mcp");
  }

  private void unauthorized(String path, String challenge) {
    answers.put("POST " + path, new MockResponse.Builder().code(401).addHeader("WWW-Authenticate", challenge)
        .body("{\"error\":\"invalid_token\"}").build());
  }

  private void json(String path, String body) {
    answers.put("GET " + path, new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
        .body(body).build());
  }

  private static String authServer(String issuer, boolean registration) {
    return "{\"issuer\":\"" + issuer + "\",\"authorization_endpoint\":\"" + issuer + "/authorize\",\"token_endpoint\":\""
        + issuer + "/token\"," + (registration ? "\"registration_endpoint\":\"" + issuer + "/register\"," : "")
        + "\"response_types_supported\":[\"code\"],\"code_challenge_methods_supported\":[\"S256\"],"
        + "\"authorization_response_iss_parameter_supported\":true}";
  }
}
