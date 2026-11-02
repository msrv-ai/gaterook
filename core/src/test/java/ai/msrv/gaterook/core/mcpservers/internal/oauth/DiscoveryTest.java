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
