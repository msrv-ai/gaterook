package ai.msrv.gaterook.connectors.gcp;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.TestResult;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class GcpProjectsTest {

  private static final String SEARCH = "/v3/projects:search";
  private FakeGoogle google;
  private GcpConnector connector;

  @BeforeEach
  void start() throws Exception {
    google = new FakeGoogle();
    google.on(SEARCH, request -> FakeGoogle.json(200, """
        {"projects":[{"projectId":"example-stage","displayName":"Example stage","state":"ACTIVE"}]}
        """));
    connector = new GcpConnector(google.cloud);
  }

  @AfterEach
  void close() {
    connector.close();
    google.close();
  }

  @Test
  void discoversBeforeScopeSelectionWithTheDraftCredentials() {
    ConnectionConfig draft = draft("""
        {"source":"adc","impersonate":"%s","quotaProject":"example-billing"}
        """.formatted(FakeGoogle.SA));
    TestCtx context = TestCtx.of();

    TestResult result = connector.discoverProjects(draft, new TestSecrets(), context);

    assertThat(result.ok()).isTrue();
    assertThat(result.details().path("projects").toString()).isEqualTo("""
        [{"projectId":"example-stage","displayName":"Example stage"}]""");
    assertThat(result.details().path("discoveryComplete").asBoolean()).isTrue();
    assertThat(google.api()).singleElement().satisfies(request -> {
      assertThat(request.getUrl().encodedPath()).isEqualTo(SEARCH);
      assertThat(request.getMethod()).isEqualTo("GET");
      assertThat(request.getHeaders().get("Authorization")).isEqualTo("Bearer " + google.token);
      assertThat(request.getHeaders().get("x-goog-user-project")).isEqualTo("example-billing");
      assertThat(FakeGoogle.param(request, "query")).isEqualTo("state:ACTIVE");
    });
    assertThat(context.taints).contains(google.token);
    assertThat(google.cloud.issued()).as("draft discovery tokens are evicted").isEmpty();
    assertThat(draft.config().has("backend")).isFalse();
    assertThat(draft.config().has("scopes")).isFalse();
  }

  @Test
  void forwardsTheServiceAccountSecretWithoutRequiringScopes() throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    String pem = "-----BEGIN PRIVATE KEY-----\n"
        + Base64.getMimeEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
        + "\n-----END PRIVATE KEY-----\n";
    ObjectNode key = TestCtx.JSON.createObjectNode().put("type", "service_account")
        .put("project_id", "example-stage").put("private_key_id", "test-key").put("private_key", pem)
        .put("client_email", FakeGoogle.SA).put("client_id", "1234567890")
        .put("token_uri", "https://oauth2.googleapis.com/token");

    TestResult result = connector.discoverProjects(draft("{\"source\":\"serviceAccountKey\"}"),
        new TestSecrets().with("/credentials/serviceAccountKey", key.toString()), TestCtx.of());

    assertThat(result.ok()).isTrue();
    assertThat(google.to(SEARCH)).singleElement().satisfies(request ->
        assertThat(request.getHeaders().get("Authorization")).isEqualTo("Bearer ya29.user-sign-in"));
    assertThat(google.to("/token")).singleElement().satisfies(request ->
        assertThat(request.getBody().utf8()).contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer"));
    assertThat(google.cloud.issued()).isEmpty();
  }

  @Test
  void reportsDiscoveryPermissionFailureWithManualEntryFallback() {
    google.on(SEARCH, request -> FakeGoogle.json(403, "{\"error\":{\"message\":\"Permission denied\"}}"));

    TestResult result = connector.discoverProjects(draft("{\"source\":\"adc\"}"), new TestSecrets(), TestCtx.of());

    assertThat(result.ok()).isFalse();
    assertThat(result.error().message()).contains("resourcemanager.projects.get", "enter a project manually");
    assertThat(result.details()).isNull();
    assertThat(google.api()).hasSize(1);
    assertThat(google.cloud.issued()).isEmpty();
  }

  private static ConnectionConfig draft(String credentials) {
    return Configs.of("draft-projects", Env.DEV, "{\"credentials\":" + credentials + "}",
        "/credentials/serviceAccountKey");
  }
}
