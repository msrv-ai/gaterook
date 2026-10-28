package ai.msrv.gaterook.connectors.logs;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;

class GcpProjectsTest {

  private static final String SA = "reader@example-prod.iam.gserviceaccount.com";
  private final MockWebServer server = new MockWebServer();
  private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  private volatile Function<RecordedRequest, MockResponse> projects = request -> json(200, "{}");
  private CloudCredentials cloud;
  private LogsConnector connector;
  @TempDir Path dir;

  @BeforeEach
  void start() throws Exception {
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        requests.add(request);
        String path = request.getUrl().encodedPath();
        if (path.equals("/token")) {
          return json(200, """
              {"access_token":"ya29.project-source","expires_in":3600,"token_type":"Bearer"}
              """);
        }
        if (path.endsWith(":generateAccessToken")) {
          return json(200, "{\"accessToken\":\"ya29.project-impersonated\",\"expireTime\":\""
              + Instant.now().plusSeconds(900) + "\"}");
        }
        return projects.apply(request);
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    Path adc = Files.writeString(dir.resolve("adc.json"), """
        {"type":"authorized_user","client_id":"test.apps.googleusercontent.com",
         "client_secret":"test-client-secret","refresh_token":"test-refresh-token"}
        """);
    cloud = new CloudCredentials(new CloudEndpoints(server.url("/token").uri(),
        server.url("/") + "v1/projects/-/serviceAccounts/%s:generateAccessToken", adc, null, null, null,
        Map.of("gcp.cloudresourcemanager", server.url("/").uri())), Clock.systemUTC());
    connector = new LogsConnector(cloud);
  }

  @AfterEach
  void stop() throws Exception {
    connector.close();
    cloud.close();
    server.close();
  }

  @Test
  void dedicatedGoogleConnectorDiscoversBeforeAProjectIsSelected() {
    projects = request -> json(200, "{\"projects\":[{\"projectId\":\"example-prod\",\"displayName\":\"Example production\"}]}");
    ConnectionConfig legacy = draft("{\"source\":\"adc\"}");
    ConnectionConfig modern = new ConnectionConfig(legacy.id(), legacy.name(), "gcp-logging", legacy.env(),
        legacy.version(), legacy.config(), legacy.secretFields(), legacy.metadata());
    try (var dedicated = new BackendConnector("gcp-logging", cloud)) {
      TestResult result = dedicated.discoverProjects(modern, new TestSecrets(), TestCtx.of());
      assertThat(result.ok()).isTrue();
      assertThat(result.details().at("/projects/0/projectId").asString()).isEqualTo("example-prod");
      assertThat(projectRequests()).hasSize(1);
    }
  }

  @Test
  void discoversBeforeResourceSelectionWithTheSelectedImpersonatedCredentials() {
    projects = request -> request.getUrl().queryParameter("pageToken") == null ? json(200, """
        {"projects":[{"projectId":"example-stage","displayName":"Example stage","state":"ACTIVE"},
                     {"projectId":"example-deleted","state":"DELETE_REQUESTED"}],"nextPageToken":"next +/&"}
        """) : json(200, """
        {"projects":[{"projectId":"example-prod","displayName":"Example production","state":"ACTIVE"},
                     {"projectId":"example-stage","displayName":"Duplicate"}]}
        """);
    TestCtx ctx = TestCtx.of();
    TestResult result = connector.discoverProjects(draft("""
        {"source":"adc","impersonate":"%s","quotaProject":"example-billing"}
        """.formatted(SA)), new TestSecrets(), ctx);

    assertThat(result.ok()).isTrue();
    assertThat(result.details().path("projects").toString()).isEqualTo("""
        [{"projectId":"example-prod","displayName":"Example production"},{"projectId":"example-stage","displayName":"Example stage"}]""");
    assertThat(result.details().path("discoveryComplete").asBoolean()).isTrue();
    assertThat(projectRequests()).hasSize(2).allSatisfy(request -> {
      assertThat(request.getMethod()).isEqualTo("GET");
      assertThat(request.getHeaders().get("Authorization")).isEqualTo("Bearer ya29.project-impersonated");
      assertThat(request.getHeaders().get("x-goog-user-project")).isEqualTo("example-billing");
      assertThat(request.getUrl().queryParameter("query")).isEqualTo("state:ACTIVE");
    });
    assertThat(projectRequests().get(1).getUrl().queryParameter("pageToken")).isEqualTo("next +/&");
    RecordedRequest impersonation = requests.stream()
        .filter(request -> request.getUrl().encodedPath().endsWith(":generateAccessToken")).findFirst().orElseThrow();
    assertThat(TestCtx.JSON.readTree(impersonation.getBody().utf8()).path("scope").toString())
        .isEqualTo("[\"" + GcpLoggingBackend.PLATFORM_READ_ONLY + "\"]");
    assertThat(ctx.taints).contains("ya29.project-impersonated");
    assertThat(requests).noneSatisfy(request -> assertThat(request.getUrl().encodedPath()).startsWith("/v2/"));
  }

  @Test
  void serviceAccountKeyComesFromTheSecretResolver() throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    String pem = "-----BEGIN PRIVATE KEY-----\n"
        + Base64.getMimeEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
        + "\n-----END PRIVATE KEY-----\n";
    ObjectNode key = TestCtx.JSON.createObjectNode().put("type", "service_account")
        .put("project_id", "example-prod").put("private_key_id", "test-key").put("private_key", pem)
        .put("client_email", SA).put("client_id", "1234567890")
        .put("token_uri", "https://oauth2.googleapis.com/token");

    TestResult result = connector.discoverProjects(draft("{\"source\":\"serviceAccountKey\"}"),
        new TestSecrets().with("/credentials/serviceAccountKey", key.toString()), TestCtx.of());

    assertThat(result.ok()).isTrue();
    assertThat(result.details().path("projects").isEmpty()).isTrue();
    assertThat(projectRequests()).hasSize(1);
    assertThat(projectRequests().getFirst().getHeaders().get("Authorization")).isEqualTo("Bearer ya29.project-source");
    assertThat(requests.getFirst().getBody().utf8()).contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer");

    key.put("client_email", "another-reader@example-stage.iam.gserviceaccount.com");
    TestResult changed = connector.discoverProjects(draft("{\"source\":\"serviceAccountKey\"}"),
        new TestSecrets().with("/credentials/serviceAccountKey", key.toString()), TestCtx.of());
    assertThat(changed.ok()).isTrue();
    assertThat(requests.stream().filter(request -> request.getUrl().encodedPath().equals("/token")).toList())
        .as("an edited draft key must obtain its own token even when its secret version is unchanged")
        .hasSize(2);
  }

  @Test
  void stopsRepeatedAndEndlessPaginationWithoutClaimingACompleteList() {
    projects = request -> json(200, "{\"projects\":[{\"projectId\":\"example-prod\"}],\"nextPageToken\":\"repeat\"}");
    TestResult repeated = connector.discoverProjects(draft("{\"source\":\"adc\"}"), new TestSecrets(), TestCtx.of());
    assertThat(repeated.ok()).isTrue();
    assertThat(repeated.details().path("discoveryComplete").asBoolean()).isFalse();
    assertThat(projectRequests()).hasSize(2);
    requests.clear();
    projects = request -> json(200, "{\"nextPageToken\":\"page-" + requests.size() + "\"}");
    TestResult endless = connector.discoverProjects(draft("{\"source\":\"adc\"}"), new TestSecrets(), TestCtx.of());
    assertThat(endless.ok()).isTrue();
    assertThat(endless.details().path("discoveryComplete").asBoolean()).isFalse();
    assertThat(projectRequests()).hasSize(GcpProjects.MAX_PAGES);
  }

  @Test
  void boundsProjectCountEvenWhenTheServerIgnoresPageSize() {
    ObjectNode body = TestCtx.JSON.createObjectNode();
    var choices = body.putArray("projects");
    for (int i = 0; i <= GcpProjects.MAX_PROJECTS; i++) choices.addObject().put("projectId", "example-" + i);
    projects = request -> json(200, body.toString());
    TestResult result = connector.discoverProjects(draft("{\"source\":\"adc\"}"), new TestSecrets(), TestCtx.of());
    assertThat(result.ok()).isTrue();
    assertThat(result.details().path("projects").size()).isEqualTo(GcpProjects.MAX_PROJECTS);
    assertThat(result.details().path("discoveryComplete").asBoolean()).isFalse();
  }

  @Test
  void failureExplainsManualEntryAndDoesNotRequireLoggingPermissions() {
    projects = request -> json(403, "{\"error\":{\"message\":\"Permission denied\"}}");
    TestResult result = connector.discoverProjects(draft("{\"source\":\"adc\"}"), new TestSecrets(), TestCtx.of());
    assertThat(result.ok()).isFalse();
    assertThat(result.error().message()).contains("resourcemanager.projects.get", "enter a project manually")
        .doesNotContain("roles/logging.viewer");
    assertThat(result.details()).isNull();
  }

  @Test
  void rejectsOtherBackendsAndHonorsTheDeadline() {
    TestResult other = connector.discoverProjects(Configs.loki("https://logs.example.test"), new TestSecrets(), TestCtx.of());
    assertThat(other.ok()).isFalse();
    assertThat(other.error().code()).isEqualTo("discovery-unavailable");
    assertThat(requests).isEmpty();
    TestResult expired = connector.discoverProjects(draft("{\"source\":\"adc\"}"), new TestSecrets(),
        TestCtx.of().timeout(Duration.ZERO));
    assertThat(expired.ok()).isFalse();
    assertThat(projectRequests()).isEmpty();
  }

  private ConnectionConfig draft(String credentials) {
    return Configs.of("draft-projects", Env.DEV, "{\"backend\":\"gcp-logging\",\"credentials\":" + credentials + "}",
        "/credentials/serviceAccountKey");
  }

  private List<RecordedRequest> projectRequests() {
    return requests.stream().filter(request -> request.getUrl().encodedPath().equals("/v3/projects:search")).toList();
  }

  private static MockResponse json(int code, String body) {
    return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build();
  }
}
