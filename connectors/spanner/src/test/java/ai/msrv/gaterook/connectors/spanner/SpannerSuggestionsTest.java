package ai.msrv.gaterook.connectors.spanner;

import static ai.msrv.gaterook.connectors.spanner.FakeSpanner.json;
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
 * Help for the Spanner form: the project from gcloud or a pasted key, and once the draft signs in the project's
 * instances, the instance's databases with their dialects and the database's roles, without opening a session.
 */
class SpannerSuggestionsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String INSTANCES = "GET /v1/projects/acme-prod/instances";
  private static final String DATABASES = "GET /v1/projects/acme-prod/instances/orders-prod/databases";

  @TempDir
  Path home;

  private FakeSpanner google;
  private SpannerConnector spanner;

  @BeforeEach
  void start() throws IOException {
    google = new FakeSpanner();
    Path gcloud = Files.createDirectories(home.resolve(".config/gcloud/configurations"));
    Files.writeString(home.resolve(".config/gcloud/active_config"), "default\n");
    Files.writeString(gcloud.resolve("config_default"), "[core]\nproject = acme-prod\n");
    Path adc = Files.writeString(home.resolve("adc.json"), "{\"type\":\"authorized_user\"}");
    CloudLocal local = new CloudLocal(new CloudEndpoints(null, null, adc, null, null, null),
        Map.<String, String>of()::get, home);
    spanner = new SpannerConnector(google.cloud, local);
    google.onExact(INSTANCES, r -> json(200, """
        {"instances":[
          {"name":"projects/acme-prod/instances/orders-prod","displayName":"Orders prod",
           "config":"projects/acme-prod/instanceConfigs/regional-europe-west1","state":"READY"},
          {"name":"projects/acme-prod/instances/analytics","displayName":"analytics",
           "config":"projects/acme-prod/instanceConfigs/nam3","state":"CREATING"}]}"""));
    google.onExact(DATABASES, r -> json(200, """
        {"databases":[
          {"name":"%s","state":"READY","databaseDialect":"POSTGRESQL"},
          {"name":"projects/acme-prod/instances/orders-prod/databases/billing","state":"READY_OPTIMIZING",
           "databaseDialect":"GOOGLE_STANDARD_SQL"}]}""".formatted(FakeSpanner.DATABASE)));
    google.on("GET /databaseRoles", r -> json(200, """
        {"databaseRoles":[{"name":"%1$s/databaseRoles/public"},{"name":"%1$s/databaseRoles/spanner_info_reader"},
          {"name":"%1$s/databaseRoles/agent_reader"}]}""".formatted(FakeSpanner.DATABASE)));
  }

  @AfterEach
  void stop() {
    spanner.close();
    google.close();
  }

  private static ConnectionConfig draft(String instance, String database) {
    return new ConnectionConfig("draft-sp", "draft", "spanner", Env.DEV, 0, JSON.readTree("""
        {"project":"acme-prod","instance":"%s","database":"%s","credentials":{"source":"adc"}}"""
        .formatted(instance, database)), Set.of(GoogleLocal.KEY_FIELD), null);
  }

  @Test
  void gcloudsProjectFillsInTheProject() {
    Suggestions s = spanner.suggest(new ConnectionConfig("draft-sp", "draft", "spanner", Env.DEV, 0,
        JSON.readTree("{\"credentials\":{\"source\":\"adc\"}}"), Set.of(), null), new TestSecrets(), TestCtx.of(),
        Suggestions.Scope.LOCAL);

    assertThat(s.fields().get("/project").value()).isEqualTo("acme-prod");
    assertThat(s.fields()).containsOnlyKeys("/project");
    assertThat(google.requests).isEmpty();
  }

  @Test
  void signedInItListsTheInstancesTheDatabasesAndTheRoles() {
    TestCtx ctx = TestCtx.of();

    Suggestions s = spanner.suggest(draft("orders-prod", "orders"), new TestSecrets(), ctx,
        Suggestions.Scope.SERVICE);

    Suggestions.Field instances = s.fields().get("/instance");
    assertThat(instances.source()).isEqualTo("acme-prod");
    assertThat(instances.options()).extracting(Option::value).containsExactly("orders-prod", "analytics");
    assertThat(instances.options().get(0).label()).isEqualTo("Orders prod");
    assertThat(instances.options().get(0).detail()).isEqualTo("regional-europe-west1");
    assertThat(instances.options().get(1).label()).isNull();
    assertThat(instances.options().get(1).detail()).isEqualTo("nam3, creating");
    Suggestions.Field databases = s.fields().get("/database");
    assertThat(databases.source()).isEqualTo("orders-prod");
    assertThat(databases.options()).extracting(Option::value).containsExactly("orders", "billing");
    assertThat(databases.options()).extracting(Option::detail).containsExactly("PostgreSQL", "GoogleSQL");
    Suggestions.Field roles = s.fields().get("/advanced/databaseRole");
    assertThat(roles.options()).extracting(Option::value).containsExactly("agent_reader", "spanner_info_reader");
    assertThat(roles.options().get(1).detail()).isEqualTo("system role");
    assertThat(s.notes()).containsExactly("Found orders-prod/orders, a PostgreSQL-dialect database.");
    // Lists only: no session, no query.
    assertThat(google.requests).noneMatch(r -> r.getUrl().encodedPath().contains("/sessions"));
    assertThat(google.requests).filteredOn(r -> r.getUrl().encodedPath().startsWith("/v1/"))
        .allSatisfy(r -> {
          assertThat(r.getMethod()).isEqualTo("GET");
          assertThat(r.getHeaders().get("Authorization")).isEqualTo("Bearer " + FakeSpanner.USER_TOKEN);
        });
    assertThat(ctx.taints).contains(FakeSpanner.USER_TOKEN);
    assertThat(google.cloud.issued()).isEmpty();
  }

  @Test
  void anInstanceThatIsntThereIsNamedWithTheOnesThatAre() {
    Suggestions s = spanner.suggest(draft("orders-prd", "orders"), new TestSecrets(), TestCtx.of(),
        Suggestions.Scope.SERVICE);

    assertThat(s.notes()).containsExactly("acme-prod has no instance orders-prd: choose one of its 2 instances.");
    assertThat(s.fields().get("/instance").options()).hasSize(2);
    assertThat(s.fields()).doesNotContainKeys("/database", "/advanced/databaseRole");
    assertThat(google.requests).noneMatch(r -> r.getUrl().encodedPath().contains("/databases"));
  }

  @Test
  void aDatabaseThatIsntThereIsNamedWithTheOnesThatAre() {
    Suggestions s = spanner.suggest(draft("orders-prod", "ordrs"), new TestSecrets(), TestCtx.of(),
        Suggestions.Scope.SERVICE);

    assertThat(s.notes()).containsExactly("orders-prod has no database ordrs: choose one of its 2 databases.");
    assertThat(s.fields()).doesNotContainKey("/advanced/databaseRole");
  }

  @Test
  void anAccountThatMayNotListInstancesStillGetsTheDatabases() {
    google.onExact(INSTANCES, r -> json(403, "{\"error\":{\"code\":403,\"message\":\"Permission "
        + "'spanner.instances.list' denied\",\"status\":\"PERMISSION_DENIED\"}}"));
    google.on("GET /databaseRoles", r -> json(403, "{\"error\":{\"code\":403,\"status\":\"PERMISSION_DENIED\"}}"));

    Suggestions s = spanner.suggest(draft("orders-prod", "orders"), new TestSecrets(), TestCtx.of(),
        Suggestions.Scope.SERVICE);

    assertThat(s.notes().getFirst()).startsWith("The credentials can't list the instances of acme-prod")
        .contains("roles/spanner.viewer");
    assertThat(s.fields()).doesNotContainKeys("/instance", "/advanced/databaseRole");
    assertThat(s.fields().get("/database").options()).hasSize(2);
  }
}
