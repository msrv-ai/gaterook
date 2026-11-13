package com.gaterook.connectors.gcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.Suggestions;
import com.gaterook.connector.api.Suggestions.Option;
import com.gaterook.connectors.cloudauth.CloudEndpoints;
import com.gaterook.connectors.cloudauth.CloudLocal;
import com.gaterook.connectors.cloudauth.GoogleLocal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Help for the GCP form: the resource scope's project from gcloud or a pasted key, a note without a Google sign-in, and
 * once the draft signs in the projects inside the scopes for Metrics projects, found in Cloud Asset.
 */
class GcpSuggestionsTest {

  private static final String FOLDER_SEARCH = "/v1/folders/123456:searchAllResources";

  @TempDir
  Path home;

  private FakeGoogle google;
  private GcpConnector connector;

  @BeforeEach
  void start() throws IOException {
    google = new FakeGoogle();
  }

  @AfterEach
  void stop() {
    if (connector != null) {
      connector.close();
    }
    google.close();
  }

  /** gcloud on this computer works in {@code example-dev}; {@code signedIn}: application default credentials exist. */
  private GcpConnector connector(boolean signedIn) throws IOException {
    Path gcloud = Files.createDirectories(home.resolve(".config/gcloud/configurations"));
    Files.writeString(home.resolve(".config/gcloud/active_config"), "default\n");
    Files.writeString(gcloud.resolve("config_default"), "[core]\naccount = dev@example.test\nproject = example-dev\n");
    Path adc = home.resolve("adc.json");
    if (signedIn) {
      Files.writeString(adc, "{\"type\":\"authorized_user\"}");
    }
    CloudLocal local = new CloudLocal(new CloudEndpoints(null, null, adc, null, null, null),
        Map.<String, String>of()::get, home);
    connector = new GcpConnector(google.cloud, Clock.systemUTC(), local);
    return connector;
  }

  private static ConnectionConfig draft(String json) {
    return Configs.of("draft-gcp", Env.DEV, json, GoogleLocal.KEY_FIELD);
  }

  @Test
  void gcloudsProjectIsTheScopeOfANewConnection() throws Exception {
    Suggestions s = connector(true).suggest(draft("{\"credentials\":{\"source\":\"adc\"}}"), new TestSecrets(),
        TestCtx.of(), Suggestions.Scope.LOCAL);

    Suggestions.Field scopes = s.fields().get("/scopes");
    assertThat(scopes.value()).isEqualTo("projects/example-dev");
    assertThat(scopes.source()).isEqualTo("your gcloud configuration");
    assertThat(scopes.options()).extracting(Option::value).containsExactly("projects/example-dev");
    assertThat(s.notes()).isEmpty();
    // Local suggestions read files only.
    assertThat(google.requests).isEmpty();
  }

  @Test
  void withoutASignInTheFormSaysHowToGetOne() throws Exception {
    Suggestions s = connector(false).suggest(draft("{\"credentials\":{\"source\":\"adc\"}}"), new TestSecrets(),
        TestCtx.of(), Suggestions.Scope.LOCAL);

    assertThat(s.notes()).containsExactly(GoogleLocal.NO_SIGN_IN);
    assertThat(s.notes().getFirst()).contains("gcloud auth application-default login", "service account key");
    // gcloud's project is still offered.
    assertThat(s.fields().get("/scopes").value()).isEqualTo("projects/example-dev");
  }

  @Test
  void aPastedKeyNamesItsOwnProjectAndGcloudsIsAnAlternative() throws Exception {
    String key = "{\"type\":\"service_account\",\"project_id\":\"key-project\",\"private_key\":\"-----BEGIN PRIVATE "
        + "KEY-----\\nnot-a-real-key\\n-----END PRIVATE KEY-----\\n\",\"client_email\":\"gw@key-project.iam"
        + ".gserviceaccount.com\"}";

    Suggestions s = connector(false).suggest(draft("{\"credentials\":{\"source\":\"serviceAccountKey\","
        + "\"impersonate\":\"gw-reader@acme-stage.iam.gserviceaccount.com\"}}"),
        new TestSecrets().with(GoogleLocal.KEY_FIELD, key), TestCtx.of(), Suggestions.Scope.LOCAL);

    Suggestions.Field scopes = s.fields().get("/scopes");
    assertThat(scopes.value()).isEqualTo("projects/key-project");
    assertThat(scopes.source()).isEqualTo("the service account key");
    assertThat(scopes.options()).extracting(Option::value)
        .containsExactly("projects/key-project", "projects/example-dev", "projects/acme-stage");
    assertThat(scopes.options().get(2).detail()).isEqualTo("project of gw-reader@acme-stage.iam.gserviceaccount.com");
    // A key is a sign-in: no note about gcloud, and nothing of the key but its project in the answer.
    assertThat(s.notes()).isEmpty();
    assertThat(s.toString()).doesNotContain("not-a-real-key");
    assertThat(google.requests).isEmpty();
  }

  @Test
  void somethingElseThanAKeyFileIsANote() throws Exception {
    Suggestions s = connector(true).suggest(draft("{\"credentials\":{\"source\":\"serviceAccountKey\"}}"),
        new TestSecrets().with(GoogleLocal.KEY_FIELD, "AIzaSyD-not-a-key-file"), TestCtx.of(),
        Suggestions.Scope.LOCAL);

    assertThat(s.notes()).containsExactly(GoogleLocal.NOT_A_KEY);
    // gcloud's project is offered, not filled in: the key decides.
    assertThat(s.fields().get("/scopes").value()).isNull();
    assertThat(s.fields().get("/scopes").options()).extracting(Option::value).containsExactly("projects/example-dev");
  }

  @Test
  void signedInItOffersTheProjectsInsideTheScopesForMetrics() throws Exception {
    google.on(FOLDER_SEARCH, r -> FakeGoogle.json(200, """
        {"results":[
          {"name":"//cloudresourcemanager.googleapis.com/projects/222",
           "assetType":"cloudresourcemanager.googleapis.com/Project","project":"projects/222",
           "displayName":"Acme prod","state":"ACTIVE","additionalAttributes":{"projectId":"acme-prod"}},
          {"name":"//cloudresourcemanager.googleapis.com/projects/333","project":"projects/333","displayName":"Old",
           "state":"DELETE_REQUESTED","additionalAttributes":{"projectId":"acme-old"}}]}"""));
    google.on("/v1/projects/123456789012:searchAllResources", r -> FakeGoogle.json(200, """
        {"results":[{"name":"//cloudresourcemanager.googleapis.com/projects/123456789012",
          "project":"projects/123456789012","displayName":"acme-data","state":"ACTIVE",
          "additionalAttributes":{"projectId":"acme-data"}}]}"""));
    TestCtx ctx = TestCtx.of();
    ConnectionConfig cfg = draft("""
        {"scopes":["projects/acme-stage","folders/123456","projects/123456789012"],
         "credentials":{"source":"adc","impersonate":"%s"}}""".formatted(FakeGoogle.SA));

    Suggestions s = connector(true).suggest(cfg, new TestSecrets(), ctx, Suggestions.Scope.SERVICE);

    Suggestions.Field metrics = s.fields().get("/metricsProjects");
    assertThat(metrics.source()).isEqualTo("the connection's scopes");
    assertThat(metrics.value()).isNull();
    assertThat(metrics.complete()).isTrue();
    assertThat(metrics.options()).extracting(Option::value).containsExactly("acme-stage", "acme-prod", "acme-data");
    assertThat(metrics.options().get(1).label()).isEqualTo("Acme prod");
    assertThat(metrics.options().get(1).detail()).isEqualTo("in folders/123456");
    assertThat(metrics.options().get(2).label()).isNull();
    assertThat(s.notes()).containsExactly("3 projects in the connection's scopes: add those gcp_metrics may read under "
        + "Metrics projects.");
    // Cloud Asset was asked for projects only, with the impersonated token, which went to the call's taint.
    assertThat(google.to(FOLDER_SEARCH)).singleElement().satisfies(r -> {
      assertThat(FakeGoogle.param(r, "assetTypes")).isEqualTo(GcpSuggestions.PROJECT_TYPE);
      assertThat(FakeGoogle.param(r, "readMask")).contains("additionalAttributes");
      assertThat(r.getHeaders().get("Authorization")).isEqualTo("Bearer " + google.token);
    });
    assertThat(google.to("/v1/projects/acme-stage")).as("a project scope needs no lookup").isEmpty();
    assertThat(ctx.taints).contains(google.token);
    assertThat(google.cloud.issued()).as("a suggestion's credentials go once it answered").isEmpty();
  }

  @Test
  void aFolderOnlyConnectionIsToldToChooseMetricsProjects() throws Exception {
    google.on(FOLDER_SEARCH, r -> FakeGoogle.json(200, """
        {"results":[{"project":"projects/222","displayName":"acme-prod","state":"ACTIVE",
          "additionalAttributes":{"projectId":"acme-prod"}}],"nextPageToken":"more"}"""));

    Suggestions s = connector(true).suggest(draft("{\"scopes\":[\"folders/123456\"],\"credentials\":"
        + "{\"source\":\"adc\"}}"), new TestSecrets(), TestCtx.of(), Suggestions.Scope.SERVICE);

    assertThat(s.fields().get("/metricsProjects").complete()).isFalse();
    assertThat(s.notes()).contains("gcp_metrics has no project to read yet: choose metrics projects among the 1+ "
        + "projects in the scopes.");
  }

  @Test
  void aScopeTheAccountCantSearchIsANoteNotAFailure() throws Exception {
    google.on(FOLDER_SEARCH, r -> FakeGoogle.json(403, "{\"error\":{\"code\":403,\"message\":\"The caller does not "
        + "have permission\",\"status\":\"PERMISSION_DENIED\"}}"));

    Suggestions s = connector(true).suggest(draft("{\"scopes\":[\"projects/acme-stage\",\"folders/123456\"],"
        + "\"credentials\":{\"source\":\"adc\"}}"), new TestSecrets(), TestCtx.of(), Suggestions.Scope.SERVICE);

    assertThat(s.notes()).anyMatch(n -> n.startsWith("Can't list the projects in folders/123456: ")
        && n.contains("roles/cloudasset.viewer"));
    assertThat(s.fields().get("/metricsProjects").options()).extracting(Option::value).containsExactly("acme-stage");
    assertThat(s.fields().get("/metricsProjects").complete()).isFalse();
  }
}
