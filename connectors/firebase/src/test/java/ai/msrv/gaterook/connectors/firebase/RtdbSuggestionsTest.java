package ai.msrv.gaterook.connectors.firebase;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.Suggestions.Option;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import ai.msrv.gaterook.connectors.cloudauth.CloudLocal;
import ai.msrv.gaterook.connectors.cloudauth.GoogleLocal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Help for the Realtime Database form: the default database URL of gcloud's project, offered and never filled in, and
 * once the draft signs in the project's databases from the management API and the database's top-level keys.
 */
class RtdbSuggestionsTest {

  private static final String URL = "https://acme-stage-default-rtdb.firebaseio.com";
  private static final String INSTANCES = "/v1beta/projects/acme-stage/locations/-/instances";

  @TempDir
  Path home;

  private FakeGoogle google;
  private RtdbConnector rtdb;

  @BeforeEach
  void start() throws IOException {
    google = new FakeGoogle();
    Path gcloud = Files.createDirectories(home.resolve(".config/gcloud/configurations"));
    Files.writeString(home.resolve(".config/gcloud/active_config"), "default\n");
    Files.writeString(gcloud.resolve("config_default"), "[core]\nproject = acme-stage\n");
    Path adc = Files.writeString(home.resolve("adc.json"), "{\"type\":\"authorized_user\"}");
    CloudLocal local = new CloudLocal(new CloudEndpoints(null, null, adc, null, null, null),
        Map.<String, String>of()::get, home);
    rtdb = new RtdbConnector(google.cloud, local);
    google.on(INSTANCES, r -> FakeGoogle.json(200, """
        {"instances":[
          {"name":"projects/123/locations/us-central1/instances/acme-stage-default-rtdb","project":"projects/123",
           "databaseUrl":"https://acme-stage-default-rtdb.firebaseio.com","type":"DEFAULT_DATABASE","state":"ACTIVE"},
          {"name":"projects/123/locations/europe-west1/instances/acme-eu","project":"projects/123",
           "databaseUrl":"https://acme-eu.europe-west1.firebasedatabase.app","type":"USER_DATABASE","state":"ACTIVE"},
          {"name":"projects/123/locations/us-central1/instances/old","databaseUrl":"https://old.firebaseio.com",
           "type":"USER_DATABASE","state":"DISABLED"},
          {"name":"projects/123/locations/us-central1/instances/odd","databaseUrl":"https://odd.example.com",
           "state":"ACTIVE"}]}"""));
    google.on("/.json", r -> FakeGoogle.json(200, "{\"users\": true, \"public-feed\": true, \"a*\": true}"));
  }

  @AfterEach
  void stop() {
    rtdb.close();
    google.close();
  }

  private static ConnectionConfig draft(String json) {
    return new ConnectionConfig("draft-rtdb", "draft", "firebase-rtdb", Env.DEV, 0, TestCtx.JSON.readTree(json),
        Set.of(GoogleLocal.KEY_FIELD), null);
  }

  @Test
  void theDefaultDatabaseOfGcloudsProjectIsOfferedNotFilledIn() {
    Suggestions s = rtdb.suggest(draft("{\"credentials\": {\"source\": \"adc\"}}"), new TestSecrets(), TestCtx.of(),
        Suggestions.Scope.LOCAL);

    Suggestions.Field url = s.fields().get("/databaseUrl");
    assertThat(url.value()).isNull();
    assertThat(url.source()).isEqualTo("your gcloud configuration");
    assertThat(url.options()).singleElement().satisfies(o -> {
      assertThat(o.value()).isEqualTo(URL);
      assertThat(o.detail()).isEqualTo("default database of acme-stage, if it has one");
    });
    assertThat(RtdbSettings.database(URL)).isNotNull();
    assertThat(google.requests).isEmpty();
  }

  @Test
  void signedInWithAKeyItListsTheProjectsDatabasesAndTheTopLevelPaths() throws Exception {
    TestCtx ctx = TestCtx.of();
    ConnectionConfig cfg = draft("{\"databaseUrl\": \"" + URL + "\", \"credentials\": {\"source\": "
        + "\"serviceAccountKey\"}}");

    Suggestions s = rtdb.suggest(cfg, new TestSecrets().with(GoogleLocal.KEY_FIELD, FakeGoogle.serviceAccountKey()),
        ctx, Suggestions.Scope.SERVICE);

    Suggestions.Field url = s.fields().get("/databaseUrl");
    assertThat(url.source()).isEqualTo("acme-stage");
    // Only active databases on the hosts Google runs for them.
    assertThat(url.options()).extracting(Option::value)
        .containsExactly(URL, "https://acme-eu.europe-west1.firebasedatabase.app");
    assertThat(url.options()).extracting(Option::detail).containsExactly("us-central1, default", "europe-west1");
    Suggestions.Field paths = s.fields().get("/paths");
    assertThat(paths.source()).isEqualTo("the database");
    assertThat(paths.options()).extracting(Option::value).containsExactly("users", "public-feed");
    assertThat(s.notes()).containsExactly("2 top-level paths in acme-stage-default-rtdb.firebaseio.com: choose the "
        + "ones agents may use, or leave Paths empty for the whole database.");
    // The management API gets a read-only Firebase token; the database the connection's own scopes.
    assertThat(google.to(INSTANCES)).singleElement().satisfies(r ->
        assertThat(r.getHeaders().get("Authorization")).isEqualTo("Bearer " + google.keyToken));
    assertThat(google.to("/token")).extracting(r -> FakeGoogle.assertion(r).path("scope").asString())
        .containsExactlyInAnyOrder(RtdbSuggestions.FIREBASE_READONLY, String.join(" ", RtdbApi.SCOPES));
    RecordedRequest root = google.to("/.json").getFirst();
    assertThat(root.getUrl().queryParameter("shallow")).isEqualTo("true");
    assertThat(ctx.taints).contains(google.keyToken);
  }

  @Test
  void aUrlThatIsntOneOfTheProjectsDatabasesIsANote() {
    Suggestions s = rtdb.suggest(draft("{\"databaseUrl\": \"https://acme-typo.firebaseio.com\", \"credentials\": "
        + "{\"source\": \"adc\"}}"), new TestSecrets(), TestCtx.of(), Suggestions.Scope.SERVICE);

    assertThat(s.notes()).contains("https://acme-typo.firebaseio.com isn't a database of acme-stage: choose one of "
        + "its 2 databases.");
    assertThat(s.fields().get("/databaseUrl").options()).hasSize(2);
  }

  @Test
  void anAccountThatMayNotListDatabasesStillGetsThePaths() {
    google.on(INSTANCES, r -> FakeGoogle.json(403, "{\"error\": {\"code\": 403, \"message\": \"The caller does not "
        + "have permission\", \"status\": \"PERMISSION_DENIED\"}}"));

    Suggestions s = rtdb.suggest(draft("{\"databaseUrl\": \"" + URL + "\", \"credentials\": {\"source\": \"adc\"}}"),
        new TestSecrets(), TestCtx.of(), Suggestions.Scope.SERVICE);

    assertThat(s.notes()).contains("The credentials can't list the Realtime Databases of acme-stage "
        + "(firebasedatabase.instances.list, in roles/firebasedatabase.viewer).");
    // The default database URL guessed on this computer stays offered.
    assertThat(s.fields().get("/databaseUrl").options()).extracting(Option::value).containsExactly(URL);
    assertThat(s.fields().get("/paths").options()).hasSize(2);
  }
}
