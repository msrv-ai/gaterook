package ai.msrv.gaterook.connectors.databricks;

import static ai.msrv.gaterook.connectors.databricks.FakeDatabricks.param;
import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.Suggestions.Option;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/**
 * Help for the Databricks form: the workspace profiles of {@code ~/.databrickscfg}, and signed in, the SQL warehouses
 * (the only running one filled in), the catalogs and the schemas, against the fake workspace.
 */
class DatabricksSuggestionsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String PROFILE_TOKEN = "dapi-cnry-profile-token-0042";

  @TempDir
  Path home;

  private FakeDatabricks dbx;
  private DatabricksConnector databricks;
  private final TestSecrets secrets = new TestSecrets().with("/auth/token", FakeDatabricks.PAT)
      .with("/auth/clientSecret", FakeDatabricks.CLIENT_SECRET);

  @BeforeEach
  void start() throws Exception {
    dbx = new FakeDatabricks();
    databricks = new DatabricksConnector(new TestCtx.MovingClock(Instant.parse("2026-11-12T09:00:00Z")),
        dbx.clientTls);
  }

  @AfterEach
  void stop() {
    databricks.close();
    dbx.close();
  }

  private static ConnectionConfig draft(String json) {
    return new ConnectionConfig("draft-dbx", "draft", "databricks", Env.DEV, 1, JSON.readTree(json),
        Set.of("/auth/token", "/auth/clientSecret"), null);
  }

  private static List<String> values(Suggestions s, String pointer) {
    return s.fields().get(pointer).options().stream().map(Option::value).toList();
  }

  // ── This computer ───────────────────────────────────────────────────────────────────────────────────────────

  private DatabricksConnector withProfiles() throws Exception {
    Files.writeString(home.resolve(".databrickscfg"), """
        ; Written by the Databricks CLI.
        [DEFAULT]
        host  = https://dbc-a1b2c3d4-e5f6.cloud.databricks.com/
        token = %s

        [prod]
        host          = adb-1234567890123456.7.azuredatabricks.net
        client_id     = 6f0e2c9a-1b3d-4e5f-8a7b-9c0d1e2f3a4b
        client_secret = dose-cnry-profile-secret
        http_path     = /sql/1.0/warehouses/9f8e7d6c5b4a3210

        [account]
        host       = https://accounts.cloud.databricks.com
        account_id = 00000000-0000-0000-0000-000000000000
        """.formatted(PROFILE_TOKEN));
    return databricks.machine(new DatabricksSuggestions.Machine(Map.<String, String>of()::get, home));
  }

  @Test
  void theDefaultProfileFillsInTheWorkspace() throws Exception {
    Suggestions s = withProfiles().suggest(draft("{\"auth\": {\"method\": \"oauth\"}}"), secrets, TestCtx.of(),
        Suggestions.Scope.LOCAL);

    Suggestions.Field host = s.fields().get("/host");
    assertThat(host.value()).isEqualTo("https://dbc-a1b2c3d4-e5f6.cloud.databricks.com");
    assertThat(host.source()).isEqualTo("~/.databrickscfg");
    // An account console isn't a workspace; a host without a scheme is one with https.
    assertThat(host.options()).extracting(Option::value).containsExactly(
        "https://dbc-a1b2c3d4-e5f6.cloud.databricks.com", "https://adb-1234567890123456.7.azuredatabricks.net");
    assertThat(s.notes()).containsExactly("Found 2 workspace profiles in ~/.databrickscfg: the form uses DEFAULT, "
        + "which signs in with a personal access token.");
    String all = JSON.writeValueAsString(s);
    assertThat(all).doesNotContain(PROFILE_TOKEN).doesNotContain("dose-cnry-profile-secret");
    assertThat(dbx.requests).isEmpty();
  }

  @Test
  void theProfileOfTheTypedWorkspaceGivesItsWarehouseAndServicePrincipal() throws Exception {
    Suggestions s = withProfiles().suggest(draft("{\"host\": \"https://ADB-1234567890123456.7.azuredatabricks.net/\", "
        + "\"auth\": {\"method\": \"oauth\"}}"), secrets, TestCtx.of(), Suggestions.Scope.LOCAL);

    assertThat(s.fields().get("/warehouseId").value()).isEqualTo("9f8e7d6c5b4a3210");
    assertThat(s.fields().get("/warehouseId").source()).isEqualTo("profile prod");
    assertThat(s.fields().get("/auth/clientId").value()).isEqualTo(FakeDatabricks.CLIENT_ID);
  }

  @Test
  void aProfileNamedByTheEnvironmentIsTheDefault() throws Exception {
    withProfiles();
    Suggestions s = databricks.machine(new DatabricksSuggestions.Machine(Map.of("DATABRICKS_CONFIG_PROFILE",
        "prod")::get, home)).suggest(draft("{}"), secrets, TestCtx.of(), Suggestions.Scope.LOCAL);

    assertThat(s.fields().get("/host").value()).isEqualTo("https://adb-1234567890123456.7.azuredatabricks.net");
    assertThat(s.fields().get("/warehouseId").value()).isEqualTo("9f8e7d6c5b4a3210");
  }

  // ── Signed in ───────────────────────────────────────────────────────────────────────────────────────────────

  private void workspace(String warehouses) {
    dbx.on("GET /api/2.0/preview/scim/v2/Me", "{\"userName\": \"reader@example.com\"}")
        .on("GET /api/2.0/sql/warehouses", warehouses)
        .on("GET /api/2.1/unity-catalog/catalogs", "{\"catalogs\": [{\"name\": \"main\", \"catalog_type\": "
            + "\"MANAGED_CATALOG\"}, {\"name\": \"system\", \"catalog_type\": \"SYSTEM_CATALOG\"}, {\"name\": "
            + "\"audit_2026\", \"catalog_type\": \"MANAGED_CATALOG\"}]}")
        .on("GET /api/2.1/unity-catalog/current-metastore-assignment", "{\"metastore_id\": \"m-1\", "
            + "\"default_catalog_name\": \"main\"}")
        .on("GET /api/2.1/unity-catalog/schemas", r -> FakeDatabricks.json(200, "{\"schemas\": [{\"name\": "
            + "\"default\"}, {\"name\": \"sales\"}, {\"name\": \"information_schema\"}], \"catalog\": \""
            + param(r, "catalog_name") + "\"}"));
  }

  @Test
  void signedInItFillsInTheOnlyRunningWarehouseAndListsCatalogsAndSchemas() throws Exception {
    workspace("{\"warehouses\": [{\"id\": \"1a2b3c4d5e6f7a8b\", \"name\": \"Starter\", \"state\": \"RUNNING\", "
        + "\"cluster_size\": \"Small\", \"enable_serverless_compute\": true}, {\"id\": \"9f8e7d6c5b4a3210\", "
        + "\"name\": \"ETL\", \"state\": \"STOPPED\", \"cluster_size\": \"Large\"}]}");

    Suggestions s = databricks.suggest(draft("{\"host\": \"" + dbx.host() + "\", \"auth\": {\"method\": "
        + "\"token\"}}"), secrets, TestCtx.of(), Suggestions.Scope.SERVICE);

    Suggestions.Field warehouse = s.fields().get("/warehouseId");
    assertThat(warehouse.value()).isEqualTo("1a2b3c4d5e6f7a8b");
    assertThat(warehouse.options()).extracting(Option::label).containsExactly("Starter · 1a2b3c4d5e6f7a8b",
        "ETL · 9f8e7d6c5b4a3210");
    assertThat(warehouse.options()).extracting(Option::detail).containsExactly("running, Small, serverless",
        "stopped, Large");
    assertThat(values(s, "/catalog")).containsExactly("main", "system", "audit_2026");
    assertThat(s.fields().get("/catalog").value()).as("no list: empty is the workspace's default").isNull();
    assertThat(s.fields().get("/catalogs").options().getFirst().detail()).isEqualTo("managed catalog");
    assertThat(values(s, "/schema")).containsExactly("default", "sales");
    assertThat(values(s, "/schemas")).containsExactly("main.default", "main.sales");
    assertThat(s.notes()).containsExactly("Signed in to 127.0.0.1 as reader@example.com: 2 SQL warehouses, 3 "
        + "catalogs.");
    assertThat(param(dbx.to("GET /api/2.1/unity-catalog/schemas").getFirst(), "catalog_name")).isEqualTo("main");
    assertThat(dbx.to("POST /api/2.0/sql/statements")).as("no warehouse is started").isEmpty();
    assertThat(dbx.requests).allSatisfy(r -> assertThat(r.getHeaders().get("Authorization"))
        .isEqualTo("Bearer " + FakeDatabricks.PAT));
  }

  @Test
  void aListWithoutItsDefaultGetsOneFilledIn() throws Exception {
    workspace("{\"warehouses\": [{\"id\": \"1a2b3c4d5e6f7a8b\", \"name\": \"Starter\", \"state\": \"STOPPED\"}]}");

    Suggestions s = databricks.suggest(draft("{\"host\": \"" + dbx.host() + "\", \"warehouseId\": "
        + "\"0000000000000000\", \"catalogs\": [\"audit*\"], \"schemas\": [\"sales\"], \"auth\": {\"method\": "
        + "\"oauth\", \"clientId\": \"" + FakeDatabricks.CLIENT_ID + "\"}}"), secrets, TestCtx.of(),
        Suggestions.Scope.SERVICE);

    // The only warehouse is filled in although it is stopped; the typed one doesn't exist.
    assertThat(s.fields().get("/warehouseId").value()).isEqualTo("1a2b3c4d5e6f7a8b");
    assertThat(s.notes()).contains("The identity may not use the SQL warehouse 0000000000000000, or it doesn't "
        + "exist: choose one of the warehouses it may use.");
    // The workspace's default isn't on the list: its only catalog is, and its only schema.
    assertThat(s.fields().get("/catalog").value()).isEqualTo("audit_2026");
    assertThat(s.fields().get("/schema").value()).isEqualTo("sales");
    assertThat(param(dbx.to("GET /api/2.1/unity-catalog/schemas").getFirst(), "catalog_name"))
        .isEqualTo("audit_2026");
    assertThat(dbx.to("POST /oidc/v1/token")).hasSize(1);

    // The token a suggestion got for a draft isn't kept: drafts are many, each with an id of its own.
    databricks.suggest(draft("{\"host\": \"" + dbx.host() + "\", \"auth\": {\"method\": \"oauth\", \"clientId\": \""
        + FakeDatabricks.CLIENT_ID + "\"}}"), secrets, TestCtx.of(), Suggestions.Scope.SERVICE);
    assertThat(dbx.to("POST /oidc/v1/token")).hasSize(2);
  }

  @Test
  void aRefusedSignInIsANote() throws Exception {
    dbx.on("GET /api/2.0/preview/scim/v2/Me", r -> FakeDatabricks.json(401, "{\"error_code\": \"UNAUTHENTICATED\", "
        + "\"message\": \"Invalid access token.\"}"));

    Suggestions s = databricks.suggest(draft("{\"host\": \"" + dbx.host() + "\", \"auth\": {\"method\": "
        + "\"token\"}}"), secrets, TestCtx.of(), Suggestions.Scope.SERVICE);

    assertThat(s.fields()).isEmpty();
    assertThat(s.notes()).singleElement().satisfies(n -> assertThat(n).contains("Invalid access token"));
  }
}
