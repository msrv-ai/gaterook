package ai.msrv.gaterook.connectors.snowflake;

import static ai.msrv.gaterook.connectors.snowflake.FakeSnowflake.Col;
import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.Suggestions.Option;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Help for the Snowflake form: the Snowflake CLI's connections on this computer, and signed in, the user's defaults
 * filled in, the roles, warehouses, databases and schemas, against the fake SQL API.
 */
class SnowflakeSuggestionsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String PASSWORD = "cnry-snowflake-password-77";

  @TempDir
  Path home;

  private FakeSnowflake fake;
  private SnowflakeConnector snowflake;
  private final TestSecrets secrets = new TestSecrets().with(SnowflakeSettings.PRIVATE_KEY,
      FakeSnowflake.privateKeyPem());

  @BeforeEach
  void start() {
    fake = new FakeSnowflake();
    snowflake = new SnowflakeConnector(fake.base());
  }

  @AfterEach
  void stop() {
    snowflake.close();
    fake.close();
  }

  private static ConnectionConfig draft(String json) {
    return new ConnectionConfig("draft-sf", "draft", "snowflake", Env.DEV, 1, JSON.readTree(json),
        Set.of(SnowflakeSettings.PRIVATE_KEY, SnowflakeSettings.TOKEN), null);
  }

  private static List<String> values(Suggestions s, String pointer) {
    return s.fields().get(pointer).options().stream().map(Option::value).toList();
  }

  // ── This computer ───────────────────────────────────────────────────────────────────────────────────────────

  private SnowflakeConnector withCliFiles() throws IOException {
    Path dir = Files.createDirectories(home.resolve("sf"));
    Files.writeString(dir.resolve("connections.toml"), """
        [dev]
        account = "myorg-dev_account"
        user = "jdoe"
        password = "%s"
        warehouse = "COMPUTE_WH"
        role = "ANALYST"
        database = "ANALYTICS"
        schema = "PUBLIC"

        [prod]
        account = "xy12345.eu-central-1.privatelink.snowflakecomputing.com"
        user = "svc_reader"
        authenticator = "SNOWFLAKE_JWT"
        private_key_file = "~/keys/reader.p8"
        """.formatted(PASSWORD));
    Files.writeString(dir.resolve("config.toml"), """
        default_connection_name = "dev"

        [connections.sso]
        account = "myorg-sso"
        user = "jane@example.com"
        authenticator = "externalbrowser"
        """);
    return snowflake.machine(new SnowflakeSuggestions.Machine(Map.of("SNOWFLAKE_HOME",
        dir.toString())::get, home));
  }

  @Test
  void theDefaultConnectionOfTheSnowflakeCliFillsInTheForm() throws Exception {
    Suggestions s = withCliFiles().suggest(draft("{\"authentication\": {\"method\": \"keyPair\"}}"), secrets,
        TestCtx.of(), Suggestions.Scope.LOCAL);

    Suggestions.Field account = s.fields().get("/account");
    assertThat(account.value()).isEqualTo("myorg-dev_account");
    assertThat(account.source()).isEqualTo("~/sf/connections.toml");
    // The private link host comes as its account identifier, the private link as its setting.
    assertThat(account.options()).extracting(Option::value).containsExactly("myorg-dev_account",
        "xy12345.eu-central-1", "myorg-sso");
    assertThat(account.options().getFirst().detail()).isEqualTo("connection dev (default)");
    assertThat(s.fields().get("/user").value()).isEqualTo("jdoe");
    assertThat(s.fields().get("/warehouse").value()).isEqualTo("COMPUTE_WH");
    assertThat(s.fields().get("/role").value()).isEqualTo("ANALYST");
    assertThat(s.fields().get("/database").value()).isEqualTo("ANALYTICS");
    assertThat(s.fields().get("/schema").value()).isEqualTo("PUBLIC");
    assertThat(s.fields()).doesNotContainKey("/authentication/method");
    assertThat(s.notes()).containsExactly("Found 3 Snowflake connections in ~/sf/connections.toml: the form uses "
        + "dev.");
    assertThat(JSON.writeValueAsString(s)).doesNotContain(PASSWORD);
    assertThat(fake.requests).isEmpty();
  }

  @Test
  void theConnectionOfTheTypedAccountGivesItsUserAndSignIn() throws Exception {
    SnowflakeConnector c = withCliFiles();

    Suggestions prod = c.suggest(draft("{\"account\": \"XY12345.eu-central-1\"}"), secrets, TestCtx.of(),
        Suggestions.Scope.LOCAL);
    Suggestions sso = c.suggest(draft("{\"account\": \"myorg-sso\"}"), secrets, TestCtx.of(),
        Suggestions.Scope.LOCAL);
    Suggestions unknown = c.suggest(draft("{\"account\": \"other-account\"}"), secrets, TestCtx.of(),
        Suggestions.Scope.LOCAL);

    assertThat(prod.fields().get("/user").value()).isEqualTo("svc_reader");
    assertThat(prod.fields().get("/privateLink").value()).isEqualTo("true");
    assertThat(prod.fields().get("/authentication/method").value()).isEqualTo("keyPair");
    assertThat(prod.fields()).doesNotContainKey("/warehouse");
    assertThat(prod.notes()).contains("Connection prod signs in with the key in ~/keys/reader.p8: paste that private "
        + "key under Private key.");
    assertThat(sso.notes()).contains("Connection sso signs in with externalbrowser: GateRook signs in with a key pair "
        + "or a programmatic access token of the same user.");
    assertThat(unknown.fields().get("/user").value()).isNull();
    assertThat(values(unknown, "/user")).containsExactly("jdoe", "svc_reader", "jane@example.com");
  }

  @Test
  void withoutTheCliFilesThereIsNothingToSuggest() {
    Suggestions s = snowflake.machine(new SnowflakeSuggestions.Machine(Map.of("SNOWFLAKE_HOME",
        home.resolve("none").toString())::get, home)).suggest(draft("{}"), secrets, TestCtx.of(),
        Suggestions.Scope.LOCAL);

    assertThat(s.isEmpty()).isTrue();
  }

  // ── Signed in ───────────────────────────────────────────────────────────────────────────────────────────────

  private void account() {
    fake.on(SnowflakeSuggestions.DEFAULTS_SQL, b -> FakeSnowflake.result(List.of(Col.of("U", "TEXT"),
        Col.of("R", "TEXT"), Col.of("W", "TEXT"), Col.of("D", "TEXT"), Col.of("S", "TEXT"), Col.of("A", "TEXT")),
        List.of(List.of("GW_READER", "ANALYST_RO", "COMPUTE_WH", "ANALYTICS", "PUBLIC",
            "[\"PUBLIC\",\"ANALYST_RO\",\"Mixed Role\"]"))))
        .on("SHOW WAREHOUSES", b -> FakeSnowflake.result(List.of(Col.of("name", "TEXT"), Col.of("state", "TEXT"),
            Col.of("type", "TEXT"), Col.of("size", "TEXT")), List.of(List.of("COMPUTE_WH", "SUSPENDED", "STANDARD",
            "X-Small"), List.of("ETL_WH", "STARTED", "STANDARD", "Large"))))
        .on("SHOW TERSE DATABASES", b -> FakeSnowflake.result(List.of(Col.of("created_on", "TEXT"),
            Col.of("name", "TEXT"), Col.of("kind", "TEXT")), List.of(List.of("2026-01-01", "ANALYTICS", "STANDARD"),
            List.of("2026-01-01", "SNOWFLAKE", "APPLICATION"))))
        .on("SHOW TERSE SCHEMAS IN DATABASE", b -> FakeSnowflake.result(List.of(Col.of("created_on", "TEXT"),
            Col.of("name", "TEXT")), List.of(List.of("2026-01-01", "INFORMATION_SCHEMA"), List.of("2026-01-01",
            "PUBLIC"), List.of("2026-01-01", "STAGING"))));
  }

  @Test
  void signedInItFillsInTheUsersDefaultsAndListsWhatTheRoleMayUse() throws Exception {
    account();

    Suggestions s = snowflake.suggest(draft("{\"account\": \"myorg-myaccount\", \"user\": \"gw_reader\", "
        + "\"authentication\": {\"method\": \"keyPair\"}}"), secrets, TestCtx.of(), Suggestions.Scope.SERVICE);

    assertThat(s.fields().get("/role").value()).isEqualTo("ANALYST_RO");
    assertThat(values(s, "/role")).containsExactly("PUBLIC", "ANALYST_RO", "\"Mixed Role\"");
    assertThat(s.fields().get("/warehouse").value()).isEqualTo("COMPUTE_WH");
    assertThat(s.fields().get("/warehouse").options()).extracting(Option::detail)
        .containsExactly("x-small, suspended", "large, started");
    assertThat(s.fields().get("/database").value()).isEqualTo("ANALYTICS");
    assertThat(s.fields().get("/database").options()).extracting(Option::detail).containsExactly(null,
        "application");
    assertThat(s.fields().get("/schema").value()).isEqualTo("PUBLIC");
    assertThat(values(s, "/schema")).containsExactly("PUBLIC", "STAGING");
    assertThat(values(s, "/tables")).containsExactly("ANALYTICS.PUBLIC.*", "ANALYTICS.STAGING.*");
    assertThat(s.notes()).containsExactly("Signed in as GW_READER with role ANALYST_RO: 2 warehouses, 2 databases it "
        + "may use.");
    // The user's own session: no warehouse or database of the form, so nothing has to run on a warehouse.
    for (JsonNode body : fake.statementsSent()) {
      assertThat(body.has("warehouse")).isFalse();
      assertThat(body.has("database")).isFalse();
      assertThat(body.has("role")).isFalse();
    }
    assertThat(fake.sqlSent()).contains("SHOW TERSE SCHEMAS IN DATABASE \"ANALYTICS\"");
    assertThat(fake.problems).isEmpty();
  }

  @Test
  void aRoleTheUserMayNotUseIsNamedAndTheRolesItMayUseAreOffered() throws Exception {
    account();
    fake.on(SnowflakeSuggestions.DEFAULTS_SQL, b -> b.path("role").asString("").equals("WRONG")
        ? FakeSnowflake.failure(422, "390189", "08004", "Role 'WRONG' specified in the connect string is not granted "
            + "to this user.")
        : FakeSnowflake.result(List.of(Col.of("U", "TEXT"), Col.of("R", "TEXT"), Col.of("W", "TEXT"),
            Col.of("D", "TEXT"), Col.of("S", "TEXT"), Col.of("A", "TEXT")), List.of(List.of("GW_READER", "PUBLIC",
            "COMPUTE_WH", "ANALYTICS", "PUBLIC", "[\"PUBLIC\",\"ANALYST_RO\"]"))));

    Suggestions s = snowflake.suggest(draft("{\"account\": \"myorg-myaccount\", \"user\": \"gw_reader\", "
        + "\"role\": \"wrong\", \"warehouse\": \"missing_wh\", \"authentication\": {\"method\": \"keyPair\"}}"),
        secrets, TestCtx.of(), Suggestions.Scope.SERVICE);

    assertThat(s.fields().get("/role").value()).as("the typed role stays the person's").isNull();
    assertThat(values(s, "/role")).containsExactly("PUBLIC", "ANALYST_RO");
    assertThat(s.notes()).contains("The user GW_READER may not use the role WRONG: choose one of its 2 roles.",
        "The role may not use the warehouse MISSING_WH, or it doesn't exist: choose one of the warehouses it may "
            + "use.");
    assertThat(fake.statementsSent().getFirst().path("role").asString()).isEqualTo("WRONG");
    assertThat(fake.statementsSent().get(1).has("role")).isFalse();
  }

  @Test
  void aSignInSnowflakeRefusesIsANote() throws Exception {
    fake.token("the-right-token");

    Suggestions s = snowflake.suggest(draft("{\"account\": \"myorg-myaccount\", \"user\": \"gw_reader\", "
        + "\"authentication\": {\"method\": \"keyPair\"}}"), secrets, TestCtx.of(), Suggestions.Scope.SERVICE);

    assertThat(s.fields()).isEmpty();
    assertThat(s.notes()).singleElement().satisfies(n -> assertThat(n).startsWith("Snowflake refused the sign-in of "
        + "gw_reader"));
  }
}
