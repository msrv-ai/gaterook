package ai.msrv.gaterook.connectors.bigquery;

import static ai.msrv.gaterook.connectors.bigquery.FakeBigQuery.json;
import static ai.msrv.gaterook.connectors.bigquery.FakeBigQuery.param;
import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.Suggestions.Option;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import ai.msrv.gaterook.connectors.cloudauth.CloudLocal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/**
 * Help for the BigQuery form: the project from gcloud or a pasted key, and once the draft signs in the project's
 * datasets, their locations and the projects BigQuery lists, with the same credentials as Test connection.
 */
class BigQuerySuggestionsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String SA = "gw-bigquery@acme-analytics.iam.gserviceaccount.com";

  @TempDir
  Path home;

  private FakeBigQuery google;
  private BigQueryConnector bigquery;

  @BeforeEach
  void start() throws IOException {
    google = new FakeBigQuery();
    Path gcloud = Files.createDirectories(home.resolve(".config/gcloud/configurations"));
    Files.writeString(home.resolve(".config/gcloud/active_config"), "default\n");
    Files.writeString(gcloud.resolve("config_default"), "[core]\nproject = acme-dev\n");
    Path adc = Files.writeString(home.resolve("adc.json"), "{\"type\":\"authorized_user\"}");
    CloudLocal local = new CloudLocal(new CloudEndpoints(null, null, adc, null, null, null),
        Map.<String, String>of()::get, home);
    bigquery = new BigQueryConnector(google.cloud, local);
  }

  @AfterEach
  void stop() {
    bigquery.close();
    google.close();
  }

  private static ConnectionConfig draft(String json) {
    return new ConnectionConfig("draft-bq", "draft", "bigquery", Env.DEV, 0, JSON.readTree(json),
        Set.of(GoogleLocal.KEY_FIELD), null);
  }

  @Test
  void gcloudsProjectFillsInTheProject() {
    Suggestions s = bigquery.suggest(draft("{\"credentials\":{\"source\":\"adc\"}}"), new TestSecrets(),
        TestCtx.of(), Suggestions.Scope.LOCAL);

    Suggestions.Field project = s.fields().get("/project");
    assertThat(project.value()).isEqualTo("acme-dev");
    assertThat(project.source()).isEqualTo("your gcloud configuration");
    assertThat(s.notes()).isEmpty();
    assertThat(google.requests).isEmpty();
  }

  @Test
  void aPastedKeyFillsInItsProject() {
    Suggestions s = bigquery.suggest(draft("{\"credentials\":{\"source\":\"serviceAccountKey\"}}"),
        new TestSecrets().with(GoogleLocal.KEY_FIELD, "{\"type\":\"service_account\",\"project_id\":"
            + "\"acme-analytics\"}"), TestCtx.of(), Suggestions.Scope.LOCAL);

    assertThat(s.fields().get("/project").value()).isEqualTo("acme-analytics");
    assertThat(s.fields().get("/project").options()).extracting(Option::value)
        .containsExactly("acme-analytics", "acme-dev");
    assertThat(google.requests).isEmpty();
  }

  @Test
  void signedInItOffersTheDatasetsTheirLocationsAndTheProjects() {
    google.on("GET /projects/acme-analytics/datasets", """
        {"datasets":[
          {"datasetReference":{"projectId":"acme-analytics","datasetId":"sales"},"location":"EU",
           "friendlyName":"Sales"},
          {"datasetReference":{"projectId":"acme-analytics","datasetId":"events"},"location":"EU"},
          {"datasetReference":{"projectId":"acme-analytics","datasetId":"us_mirror"},"location":"US"}]}""");
    google.on("GET /projects", """
        {"projects":[{"projectReference":{"projectId":"acme-analytics"},"friendlyName":"Acme analytics"},
          {"projectReference":{"projectId":"acme-prod"},"friendlyName":"acme-prod"}]}""");
    TestCtx ctx = TestCtx.of();
    ConnectionConfig cfg = draft("{\"project\":\"acme-analytics\",\"credentials\":{\"source\":\"adc\","
        + "\"impersonate\":\"" + SA + "\"}}");

    Suggestions s = bigquery.suggest(cfg, new TestSecrets(), ctx, Suggestions.Scope.SERVICE);

    Suggestions.Field datasets = s.fields().get("/datasets");
    assertThat(datasets.source()).isEqualTo("acme-analytics");
    assertThat(datasets.complete()).isTrue();
    assertThat(datasets.value()).isNull();
    assertThat(datasets.options()).extracting(Option::value).containsExactly("sales", "events", "us_mirror");
    assertThat(datasets.options().getFirst().label()).isEqualTo("Sales");
    assertThat(datasets.options().getFirst().detail()).isEqualTo("EU");
    Suggestions.Field location = s.fields().get("/location");
    assertThat(location.value()).as("BigQuery chooses from the tables while Location is empty").isNull();
    assertThat(location.options()).extracting(Option::value).containsExactly("EU", "US");
    assertThat(location.options()).extracting(Option::detail).containsExactly("2 datasets", "1 dataset");
    // gcloud's project first, then the projects BigQuery lists; the impersonated account's project is one of them.
    assertThat(s.fields().get("/project").options()).extracting(Option::value)
        .containsExactly("acme-dev", "acme-analytics", "acme-prod");
    assertThat(s.notes()).containsExactly("3 datasets in acme-analytics: choose the ones agents may use, or leave "
        + "Datasets empty for all of them.");
    assertThat(google.to("GET /projects/acme-analytics/datasets")).singleElement().satisfies(r -> {
      assertThat(param(r, "maxResults")).isEqualTo("200");
      assertThat(r.getHeaders().get("Authorization")).isEqualTo("Bearer ya29.impersonated-cnry");
    });
    assertThat(ctx.taints).contains("ya29.impersonated-cnry");
    assertThat(google.cloud.issued()).as("a suggestion's credentials go once it answered").isEmpty();
  }

  @Test
  void anAccountThatMayNotListDatasetsIsToldWhatToGrant() {
    google.on("GET /projects/acme-analytics/datasets", r -> json(403, "{\"error\":{\"code\":403,\"message\":"
        + "\"Access Denied: Project acme-analytics\",\"errors\":[{\"reason\":\"accessDenied\"}]}}"));

    Suggestions s = bigquery.suggest(draft("{\"project\":\"acme-analytics\",\"credentials\":{\"source\":\"adc\"}}"),
        new TestSecrets(), TestCtx.of(), Suggestions.Scope.SERVICE);

    assertThat(s.notes()).singleElement().satisfies(n -> assertThat(n).contains("Access Denied",
        "roles/bigquery.dataViewer"));
    assertThat(s.fields()).doesNotContainKeys("/datasets", "/location");
    assertThat(google.to("GET /projects")).as("nothing more is asked").hasSize(1);
  }
}
