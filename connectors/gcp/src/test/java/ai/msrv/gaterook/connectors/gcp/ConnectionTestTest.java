package ai.msrv.gaterook.connectors.gcp;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Test connection (report 09 §5): token, scopes, inventory, metrics, permissions — each step shown with its latency;
 * what the account could change or read shown as hints, a warning on prod; Google's refusals turned into the fix.
 */
class ConnectionTestTest {

  private FakeGoogle google;
  private GcpConnector connector;
  private volatile String permissions = "[\"cloudasset.assets.searchAllResources\",\"monitoring.timeSeries.list\","
      + "\"run.services.get\",\"compute.instances.get\",\"cloudsql.instances.get\",\"container.clusters.get\"]";

  @BeforeEach
  void start() throws Exception {
    google = new FakeGoogle();
    google.on("/v3/projects/acme-stage", r -> r.getUrl().encodedPath().endsWith(":testIamPermissions")
        ? FakeGoogle.json(200, "{\"permissions\":" + permissions + "}")
        : FakeGoogle.json(200, "{\"name\":\"projects/123456789012\",\"projectId\":\"acme-stage\",\"state\":\"ACTIVE\","
            + "\"displayName\":\"Acme stage\"}"));
    google.on("/v3/folders/123456", r -> FakeGoogle.json(403, "{\"error\":{\"code\":403,\"message\":\"The caller "
        + "does not have permission\",\"status\":\"PERMISSION_DENIED\"}}"));
    google.on("/v1/projects/acme-stage:searchAllResources", r -> FakeGoogle.json(200, "{\"results\":[{\"name\":"
        + "\"//run.googleapis.com/projects/acme-stage/locations/europe-west1/services/api\"}]}"));
    google.on("/v3/projects/acme-stage/metricDescriptors", r -> FakeGoogle.json(200, "{\"metricDescriptors\":[]}"));
    connector = new GcpConnector(google.cloud, Clock.systemUTC());
  }

  @AfterEach
  void stop() {
    connector.close();
    google.close();
  }

  @Test
  void everyStepIsShownAndTheProjectNumberIsRemembered() {
    TestCtx ctx = TestCtx.of();

    TestResult r = connector.test(Configs.stage(), new TestSecrets(), ctx);

    assertThat(r.ok()).as(String.valueOf(r.error())).isTrue();
    assertThat(r.identity()).isEqualTo(FakeGoogle.SA);
    assertThat(r.serverVersion()).isEqualTo("Cloud Asset API v1 · Cloud Monitoring API v3");
    assertThat(r.checks()).extracting(TestResult.Check::name).containsExactly("token", "scope projects/acme-stage",
        "scope folders/123456", "inventory", "metrics", "permissions on projects/acme-stage",
        "permissions on folders/123456");
    assertThat(r.checks().get(0).detail()).isEqualTo("Impersonates " + FakeGoogle.SA + " for 15 min");
    assertThat(r.checks().get(1).detail()).isEqualTo("acme-stage (123456789012) ACTIVE");
    assertThat(r.checks().get(2).ok()).as("a least-privilege account can't read a folder").isFalse();
    assertThat(r.permissions().level()).isEqualTo(TestResult.Level.READ);
    assertThat(r.permissions().write()).isEqualTo("None");
    assertThat(r.hints()).extracting(TestResult.Hint::code).contains("gcp.scope-unreadable")
        .doesNotContain("gcp.can-write", "gcp.long-lived-credentials");
    assertThat(r.metadataUpdate().at("/projects/acme-stage").asString()).isEqualTo("123456789012");
    assertThat(r.metadataUpdate().path("serverVersion").asString()).isEqualTo("GCP · projects/acme-stage +1");
    assertThat(ctx.taints).contains(google.token);

    RecordedRequest probe = google.to("/v3/projects/acme-stage:testIamPermissions").getFirst();
    assertThat(probe.getMethod()).isEqualTo("POST");
    JsonNode body = TestCtx.JSON.readTree(probe.getBody().utf8());
    assertThat(body.path("permissions")).hasSize(ConnectionTest.READ.size() + ConnectionTest.WRITE.size());
    assertThat(google.api()).as("only reads and the permission probe reach Google")
        .allSatisfy(q -> assertThat(q.getMethod().equals("GET")
            || q.getUrl().encodedPath().endsWith(":testIamPermissions")).isTrue());
  }

  @Test
  void anAccountThatCanChangeOrReadSecretsGetsAWarningOnProd() {
    permissions = "[\"cloudasset.assets.searchAllResources\",\"compute.instances.stop\","
        + "\"secretmanager.versions.access\"]";
    google.on("/v3/projects/acme-prod", r -> r.getUrl().encodedPath().endsWith(":testIamPermissions")
        ? FakeGoogle.json(200, "{\"permissions\":" + permissions + "}")
        : FakeGoogle.json(200, "{\"name\":\"projects/42\",\"projectId\":\"acme-prod\",\"state\":\"ACTIVE\"}"));
    google.on("/v1/projects/acme-prod:searchAllResources", r -> FakeGoogle.json(200, "{\"results\":[]}"));
    google.on("/v3/projects/acme-prod/metricDescriptors", r -> FakeGoogle.json(403, "{\"error\":{\"code\":403,"
        + "\"message\":\"Permission monitoring.metricDescriptors.list denied\",\"status\":\"PERMISSION_DENIED\"}}"));
    ConnectionConfig prod = Configs.of("gcp-prod", Env.PROD, """
        {"scopes":["projects/acme-prod"],"credentials":{"source":"adc"}}""");

    TestResult r = connector.test(prod, new TestSecrets(), TestCtx.of().env(Env.PROD));

    assertThat(r.ok()).isTrue();
    assertThat(r.permissions().level()).isEqualTo(TestResult.Level.WRITE);
    assertThat(r.permissions().admin()).containsExactly("compute.instances.stop", "secretmanager.versions.access");
    assertThat(hint(r, "gcp.can-write").severity()).isEqualTo(TestResult.Severity.WARNING);
    assertThat(hint(r, "gcp.can-write").text()).contains("compute.instances.stop");
    assertThat(hint(r, "gcp.reads-secrets").severity()).isEqualTo(TestResult.Severity.WARNING);
    assertThat(hint(r, "gcp.metrics-denied").text()).contains("roles/monitoring.viewer on acme-prod");
    assertThat(hint(r, "gcp.long-lived-credentials").field()).isEqualTo("/credentials/impersonate");
  }

  @Test
  void aDisabledApiFailsWithTheCommandThatEnablesIt() {
    google.on("/v1/projects/acme-stage:searchAllResources", r -> FakeGoogle.json(403, """
        {"error":{"code":403,"message":"Cloud Asset API has not been used in project 987","status":"PERMISSION_DENIED",
         "details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"SERVICE_DISABLED",
         "metadata":{"consumer":"projects/987","service":"cloudasset.googleapis.com"}}]}}"""));

    TestResult r = connector.test(Configs.stage(), new TestSecrets(), TestCtx.of());

    assertThat(r.ok()).isFalse();
    assertThat(r.error().code()).isEqualTo("gcp.refused");
    assertThat(r.error().message()).contains("gcloud services enable cloudasset.googleapis.com --project=987");
    assertThat(r.checks()).extracting(TestResult.Check::name).contains("token", "inventory");
  }

  @Test
  void withoutASignInTheTestSaysHowToSignIn() {
    try (CloudCredentials none = new CloudCredentials(new CloudEndpoints(null, null,
        Path.of("target", "no-such-adc.json").toAbsolutePath(), null, null, null), Clock.systemUTC());
        GcpConnector c = new GcpConnector(none, Clock.systemUTC())) {
      TestResult r = c.test(Configs.stage(), new TestSecrets(), TestCtx.of());

      assertThat(r.ok()).isFalse();
      assertThat(r.error().code()).isEqualTo("gcp.unreachable");
      assertThat(r.error().message()).contains("gcloud auth application-default login");
      HealthResult h = c.health(Configs.stage(), new TestSecrets(), TestCtx.of());
      assertThat(h.state()).isEqualTo(HealthResult.State.UNREACHABLE);
    }
  }

  @Test
  void healthIsATokenAndOneInventoryRead() {
    HealthResult h = connector.health(Configs.stage(), new TestSecrets(), TestCtx.of());

    assertThat(h.state()).isIn(HealthResult.State.HEALTHY, HealthResult.State.DEGRADED);
    assertThat(google.api()).extracting(q -> q.getUrl().encodedPath())
        .containsExactly("/v1/projects/acme-stage:searchAllResources");
  }

  @Test
  void aBrokenConfigIsTheTestsAnswer() {
    TestResult r = connector.test(Configs.of("x", Env.DEV, "{\"scopes\":[],\"credentials\":{\"source\":\"adc\"}}"),
        new TestSecrets(), TestCtx.of());

    assertThat(r.error().code()).isEqualTo("gcp.config");
    assertThat(List.of(r.error().message())).allMatch(m -> m.contains("Scopes"));
  }

  private static TestResult.Hint hint(TestResult r, String code) {
    return r.hints().stream().filter(h -> h.code().equals(code)).findFirst().orElseThrow(() -> new AssertionError(
        "no hint " + code + " in " + r.hints()));
  }
}
