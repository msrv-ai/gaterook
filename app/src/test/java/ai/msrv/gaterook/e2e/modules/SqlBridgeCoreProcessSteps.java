package ai.msrv.gaterook.e2e.modules;

import static ai.msrv.gaterook.e2e.modules.SqlDialectFixture.arguments;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.msrv.gaterook.e2e.ConnectorDelivery;
import ai.msrv.gaterook.e2e.TestServices;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * The bridge with a real module (docs/connector-delivery.md): this release still compiles a SQL dialect in, and its
 * module is published. For a person with a connection of the dialect the core installs the module ahead, with the
 * check for updates, and goes on serving the connection itself; the card of the type says nothing of a download.
 *
 * <p>{@code BridgeToModuleCoreProcessIT} follows a module that was installed ahead into the release after, with a
 * fixture; {@link SqlModuleCoreProcessSteps} runs these modules in a core that doesn't compile the dialect in.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class SqlBridgeCoreProcessSteps {

  private final SqlDialectFixture dialect;
  private BuiltModule module;

  SqlBridgeCoreProcessSteps(SqlDialectFixture dialect) {
    this.dialect = dialect;
  }

  @BeforeAll
  void start(@TempDir Path home) throws Exception {
    assumeTrue(TestServices.enabled(), "The bridge tests need the Compose test services");
    assumeTrue(ConnectorDelivery.has(dialect.type()) && ConnectorDelivery.bridged(),
        "This core doesn't compile " + dialect.type() + " in: there is nothing to bridge");
    Path built = BuiltModule.packageOf(dialect.moduleId());
    assumeTrue(built != null, "Build modules/" + dialect.moduleId() + " first");
    module = BuiltModule.start(home, built);
  }

  @AfterAll
  void stop() {
    if (module != null) {
      module.close();
    }
  }

  @Test
  void theModuleIsInstalledAheadWhileTheCoreServesTheDialectItself() throws Exception {
    module.api.json(module.api.admin("POST", "/api/connections", dialect.connection("db-dev").toString()), 201);
    module.api.endpoint("db", "db-dev");

    assertThat(module.api.card(dialect.type()).path("module").isNull())
        .as("compiled into this build: its card offers no download").isTrue();
    assertThat(module.api.module(dialect.moduleId()).path("installed").asBoolean()).isFalse();
    assertThat(module.channel.downloads()).isZero();

    // The check for updates, which runs by itself soon after the start and every few hours.
    module.api.json(module.api.admin("POST", "/api/modules/refresh", null), 200);
    JsonNode installed = module.api.awaitModule(dialect.moduleId(), "ready", Duration.ofSeconds(120));
    assertThat(installed.path("installed").asBoolean()).isTrue();
    assertThat(module.channel.downloads()).isEqualTo(1);

    JsonNode rows = module.api.call(2, "sql_query", arguments("db-dev", dialect.selectFortyTwo()));
    assertThat(rows.at("/result/isError").asBoolean(true)).as(rows.toString()).isFalse();
    assertThat(rows.at("/result/structuredContent").toString()).contains("42");
    assertThat(module.api.card(dialect.type()).path("module").isNull()).as("still the build's own type").isTrue();
    assertThat(module.api.modes("db", "db-dev")).containsEntry("sql_query", "READ_ONLY").hasSize(4);

    // Asked again, nothing more is fetched.
    module.api.json(module.api.admin("POST", "/api/modules/refresh", null), 200);
    assertThat(module.channel.downloads()).isEqualTo(1);
  }
}
