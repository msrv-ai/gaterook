package com.gaterook.connectors.firebase;

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
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Help for the Firestore form: the project from gcloud or a pasted key, and once the draft signs in the project's
 * databases in Native mode and the database's top-level collections, with the same credentials as Test connection.
 */
class FirestoreSuggestionsTest {

  private static final String DATABASES = "/v1/projects/acme-stage/databases";
  private static final String COLLECTIONS = DATABASES + "/(default)/documents:listCollectionIds";

  @TempDir
  Path home;

  private FakeGoogle google;
  private FirestoreConnector firestore;

  @BeforeEach
  void start() throws IOException {
    google = new FakeGoogle();
    Path gcloud = Files.createDirectories(home.resolve(".config/gcloud/configurations"));
    Files.writeString(home.resolve(".config/gcloud/active_config"), "default\n");
    Files.writeString(gcloud.resolve("config_default"), "[core]\nproject = acme-stage\n");
    Path adc = Files.writeString(home.resolve("adc.json"), "{\"type\":\"authorized_user\"}");
    CloudLocal local = new CloudLocal(new CloudEndpoints(null, null, adc, null, null, null),
        Map.<String, String>of()::get, home);
    firestore = new FirestoreConnector(google.cloud, local);
    google.on(DATABASES, r -> FakeGoogle.json(200, """
        {"databases":[
          {"name":"projects/acme-stage/databases/(default)","locationId":"eur3","type":"FIRESTORE_NATIVE"},
          {"name":"projects/acme-stage/databases/audit-db","locationId":"europe-west1","type":"FIRESTORE_NATIVE"},
          {"name":"projects/acme-stage/databases/legacy","locationId":"nam5","type":"DATASTORE_MODE"}]}"""));
    google.on(COLLECTIONS, r -> FakeGoogle.json(200, "{\"collectionIds\": [\"orders\", \"users\", \"a*b\"]}"));
  }

  @AfterEach
  void stop() {
    firestore.close();
    google.close();
  }

  private static ConnectionConfig draft(String json) {
    return new ConnectionConfig("draft-fs", "draft", "firestore", Env.DEV, 0, TestCtx.JSON.readTree(json),
        Set.of(GoogleLocal.KEY_FIELD), null);
  }

  @Test
  void gcloudsProjectOrAPastedKeysFillsInTheProject() throws Exception {
    Suggestions adc = firestore.suggest(draft("{\"credentials\": {\"source\": \"adc\"}}"), new TestSecrets(),
        TestCtx.of(), Suggestions.Scope.LOCAL);
    assertThat(adc.fields().get("/project").value()).isEqualTo("acme-stage");
    assertThat(adc.fields().get("/project").source()).isEqualTo("your gcloud configuration");

    Suggestions key = firestore.suggest(draft("{\"credentials\": {\"source\": \"serviceAccountKey\"}}"),
        new TestSecrets().with(GoogleLocal.KEY_FIELD, FakeGoogle.serviceAccountKey()), TestCtx.of(),
        Suggestions.Scope.LOCAL);
    assertThat(key.fields().get("/project").value()).isEqualTo("acme-stage");
    assertThat(key.fields().get("/project").source()).isEqualTo("the service account key");
    assertThat(key.toString()).doesNotContain("PRIVATE KEY");
    assertThat(google.requests).isEmpty();
  }

  @Test
  void signedInItListsTheDatabasesAndTheTopLevelCollections() {
    TestCtx ctx = TestCtx.of();
    ConnectionConfig cfg = draft("{\"project\": \"acme-stage\", \"credentials\": {\"source\": \"adc\", "
        + "\"impersonate\": \"" + FakeGoogle.SA + "\"}}");

    Suggestions s = firestore.suggest(cfg, new TestSecrets(), ctx, Suggestions.Scope.SERVICE);

    Suggestions.Field databases = s.fields().get("/database");
    assertThat(databases.source()).isEqualTo("acme-stage");
    assertThat(databases.value()).isNull();
    // A database in Datastore mode isn't one the Firestore API can read.
    assertThat(databases.options()).extracting(Option::value).containsExactly("(default)", "audit-db");
    assertThat(databases.options()).extracting(Option::detail).containsExactly("eur3", "europe-west1");
    Suggestions.Field collections = s.fields().get("/collections");
    assertThat(collections.source()).isEqualTo("acme-stage/(default)");
    assertThat(collections.complete()).isTrue();
    // An ID that would be a pattern on the list isn't offered.
    assertThat(collections.options()).extracting(Option::value).containsExactly("orders", "users");
    assertThat(s.notes()).containsExactly("2 top-level collections in acme-stage/(default): choose the ones agents "
        + "may use, or leave Collections empty for all of them.");
    assertThat(google.to(COLLECTIONS)).singleElement().satisfies(r -> {
      assertThat(FakeGoogle.body(r).path("pageSize").asInt()).isEqualTo(200);
      assertThat(r.getHeaders().get("Authorization")).isEqualTo("Bearer " + google.impersonatedToken);
    });
    assertThat(google.to(DATABASES)).filteredOn(r -> r.getMethod().equals("GET")).singleElement()
        .satisfies(r -> assertThat(r.getUrl().encodedPath()).isEqualTo(DATABASES));
    assertThat(ctx.taints).contains(google.impersonatedToken);
    assertThat(google.cloud.issued()).as("a suggestion's credentials go once it answered").isEmpty();
  }

  @Test
  void withoutADefaultDatabaseTheOnlyOneIsFilledIn() {
    google.on(DATABASES, r -> FakeGoogle.json(200, "{\"databases\": [{\"name\": \"projects/acme-stage/databases/"
        + "audit-db\", \"locationId\": \"europe-west1\", \"type\": \"FIRESTORE_NATIVE\"}]}"));

    Suggestions s = firestore.suggest(draft("{\"project\": \"acme-stage\", \"database\": \"(default)\", "
        + "\"credentials\": {\"source\": \"adc\"}}"), new TestSecrets(), TestCtx.of(), Suggestions.Scope.SERVICE);

    assertThat(s.fields().get("/database").value()).isEqualTo("audit-db");
    assertThat(s.notes()).containsExactly("acme-stage has no (default) database: its database is audit-db.");
    assertThat(google.to(COLLECTIONS)).isEmpty();
  }

  @Test
  void aDatabaseInDatastoreModeIsNamedAsSuch() {
    Suggestions s = firestore.suggest(draft("{\"project\": \"acme-stage\", \"database\": \"legacy\", "
        + "\"credentials\": {\"source\": \"adc\"}}"), new TestSecrets(), TestCtx.of(), Suggestions.Scope.SERVICE);

    assertThat(s.notes()).containsExactly("legacy is a database in Datastore mode, which the Firestore API can't read:"
        + " choose one in Native mode.");
    assertThat(s.fields().get("/database").value()).isNull();
    assertThat(s.fields()).doesNotContainKey("/collections");
  }

  @Test
  void anAccountThatMayNotListDatabasesStillGetsTheCollections() {
    google.on(DATABASES, r -> FakeGoogle.json(403, "{\"error\": {\"code\": 403, \"message\": \"Missing or "
        + "insufficient permissions.\", \"status\": \"PERMISSION_DENIED\"}}"));

    Suggestions s = firestore.suggest(draft("{\"project\": \"acme-stage\", \"credentials\": {\"source\": \"adc\"}}"),
        new TestSecrets(), TestCtx.of(), Suggestions.Scope.SERVICE);

    assertThat(s.fields()).doesNotContainKey("/database");
    assertThat(s.fields().get("/collections").options()).hasSize(2);
  }
}
