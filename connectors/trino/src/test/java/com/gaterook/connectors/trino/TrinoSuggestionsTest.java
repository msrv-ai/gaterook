package com.gaterook.connectors.trino;

import static com.gaterook.connectors.trino.FakeTrino.column;
import static com.gaterook.connectors.trino.FakeTrino.rows;
import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.Suggestions;
import com.gaterook.connector.api.Suggestions.Option;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Help for the Trino / Presto form: the engine named by the coordinator's version, read without credentials from a
 * coordinator on this computer, and signed in, the catalogs and the schemas of the default catalog, against the fake
 * coordinator.
 */
class TrinoSuggestionsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private FakeTrino trino;
  private TrinoConnector connector;
  private final TestSecrets secrets = new TestSecrets().with("/auth/password", "test-only-trino-password");

  @BeforeEach
  void start() throws Exception {
    trino = new FakeTrino();
    connector = new TrinoConnector();
    trino.on("SHOW CATALOGS", rows("[" + column("Catalog", "varchar") + "]", "[\"hive\"]", "[\"system\"]"))
        .on("SHOW SCHEMAS FROM \"hive\"", rows("[" + column("Schema", "varchar") + "]", "[\"default\"]",
            "[\"information_schema\"]", "[\"sales\"]"));
  }

  @AfterEach
  void stop() {
    connector.close();
    trino.close();
  }

  private ConnectionConfig draft(String json) {
    return new ConnectionConfig("draft-trino", "draft", "trino", Env.DEV, 1, JSON.readTree(json),
        Set.of("/auth/password"), null);
  }

  private String coordinator(String more) {
    return "{\"url\": \"" + trino.url() + "\", \"auth\": {\"mode\": \"none\", \"user\": \"gaterook_reader\"}"
        + more + "}";
  }

  private static List<String> values(Suggestions s, String pointer) {
    return s.fields().get(pointer).options().stream().map(Option::value).toList();
  }

  @Test
  void theVersionTellsTrinoFromPresto() {
    assertThat(TrinoSuggestions.engine("484")).isEqualTo(TrinoSettings.Engine.TRINO);
    assertThat(TrinoSuggestions.engine("476-e.1")).isEqualTo(TrinoSettings.Engine.TRINO);
    assertThat(TrinoSuggestions.engine("414-amzn-0")).isEqualTo(TrinoSettings.Engine.TRINO);
    assertThat(TrinoSuggestions.engine("351")).isEqualTo(TrinoSettings.Engine.TRINO);
    // prestodb numbers its releases 0.x; PrestoSQL up to 350 kept the X-Presto headers.
    assertThat(TrinoSuggestions.engine("0.289")).isEqualTo(TrinoSettings.Engine.PRESTO);
    assertThat(TrinoSuggestions.engine("0.287-amzn-0")).isEqualTo(TrinoSettings.Engine.PRESTO);
    assertThat(TrinoSuggestions.engine("350")).isEqualTo(TrinoSettings.Engine.PRESTO);
    assertThat(TrinoSuggestions.engine("dev")).isNull();
    assertThat(TrinoSuggestions.engine("")).isNull();
    assertThat(TrinoSuggestions.engine(null)).isNull();
  }

  // ── This computer ───────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void aCoordinatorOnThisComputerNamesItsEngineWithoutCredentials() throws Exception {
    Suggestions s = connector.suggest(draft("{\"url\": \"" + trino.url() + "\", \"engine\": \"presto\", \"auth\": "
        + "{\"mode\": \"basic\"}}"), secrets, TestCtx.of(), Suggestions.Scope.LOCAL);

    Suggestions.Field engine = s.fields().get("/engine");
    assertThat(engine.value()).isEqualTo("trino");
    assertThat(engine.source()).isEqualTo("the coordinator");
    assertThat(engine.options()).singleElement().satisfies(o -> assertThat(o.detail()).isEqualTo("Trino 484"));
    assertThat(s.notes()).contains("The coordinator at 127.0.0.1:" + trino.port() + " is Trino 484: choose Trino "
        + "under Engine.");
    assertThat(trino.to("GET /v1/info")).singleElement().satisfies(r -> {
      assertThat(r.getHeaders().get("Authorization")).isNull();
      assertThat(r.getHeaders().get("X-Trino-User")).isNull();
    });
    assertThat(trino.statements).isEmpty();
  }

  @Test
  void aCoordinatorElsewhereIsNotAskedForLocalSuggestions() throws Exception {
    Suggestions s = connector.suggest(draft("{\"url\": \"https://trino.example.com:8443\", \"auth\": {\"mode\": "
        + "\"basic\"}}"), secrets, TestCtx.of(), Suggestions.Scope.LOCAL);

    assertThat(s.fields()).doesNotContainKey("/engine");
    assertThat(trino.requests).isEmpty();
  }

  // ── Signed in ───────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void signedInItListsTheCatalogsAndFillsInTheOnlyDataCatalog() throws Exception {
    Suggestions s = connector.suggest(draft(coordinator("")), secrets, TestCtx.of(), Suggestions.Scope.SERVICE);

    assertThat(s.fields().get("/engine").value()).isEqualTo("trino");
    assertThat(values(s, "/catalog")).containsExactly("hive", "system");
    assertThat(s.fields().get("/catalog").value()).isEqualTo("hive");
    assertThat(s.fields().get("/catalog").options().get(1).detail()).isEqualTo("Trino's own metadata");
    assertThat(values(s, "/catalogs")).containsExactly("hive", "system");
    assertThat(s.fields().get("/catalogs").value()).isNull();
    assertThat(values(s, "/schema")).containsExactly("default", "sales");
    assertThat(s.notes()).containsExactly("Signed in to Trino 484 at 127.0.0.1:" + trino.port() + " as "
        + "gaterook_reader: 2 catalogs.");
    assertThat(trino.statements).containsExactly("SHOW CATALOGS", "SHOW SCHEMAS FROM \"hive\"");
    assertThat(trino.to("POST /v1/statement")).allSatisfy(r -> assertThat(r.getHeaders().get("X-Trino-User"))
        .isEqualTo("gaterook_reader"));
  }

  @Test
  void aPrestoCoordinatorIsAskedInItsOwnProtocol() throws Exception {
    trino.version = "0.289";

    Suggestions s = connector.suggest(draft(coordinator("")), secrets, TestCtx.of(), Suggestions.Scope.SERVICE);

    assertThat(s.fields().get("/engine").value()).isEqualTo("presto");
    assertThat(s.notes()).contains("The coordinator at 127.0.0.1:" + trino.port() + " is Presto 0.289: choose "
        + "Presto under Engine.");
    assertThat(trino.to("POST /v1/statement")).allSatisfy(r -> {
      assertThat(r.getHeaders().get("X-Presto-User")).isEqualTo("gaterook_reader");
      assertThat(r.getHeaders().get("X-Trino-User")).isNull();
    });
  }

  @Test
  void aDefaultSchemaWithoutItsCatalogStillGetsTheCatalogsOffered() throws Exception {
    // x-suggest: the form asks while Default catalog is empty, which the schema requires with a default schema.
    Suggestions s = connector.suggest(draft(coordinator(", \"schema\": \"sales\"")), secrets, TestCtx.of(),
        Suggestions.Scope.SERVICE);

    assertThat(s.notes()).contains("A default schema needs a default catalog.");
    assertThat(s.fields().get("/catalog").value()).isEqualTo("hive");
  }

  @Test
  void aCatalogTheUserDoesntSeeIsNamed() throws Exception {
    Suggestions s = connector.suggest(draft(coordinator(", \"catalog\": \"iceberg\"")), secrets, TestCtx.of(),
        Suggestions.Scope.SERVICE);

    assertThat(s.fields().get("/catalog").value()).as("the typed catalog stays the person's").isNull();
    assertThat(s.notes()).contains("The user sees no catalog iceberg: choose one it sees.");
    assertThat(s.fields()).doesNotContainKey("/schema");
  }
}
