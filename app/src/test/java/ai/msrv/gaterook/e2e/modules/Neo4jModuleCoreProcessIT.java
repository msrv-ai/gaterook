package ai.msrv.gaterook.e2e.modules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.msrv.gaterook.e2e.TestServices;
import ai.msrv.gaterook.e2e.platform.CoreProcess;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Neo4j connector as the connector module a release downloads (docs/connector-delivery.md): the package of
 * {@code modules/neo4j}, installed by the assembled core when the type's form is asked for, and run in a module host
 * against Neo4j 5 of the Compose services (profile {@code neo4j}; {@code -Pservices,neo4j-it}). A write waits for
 * approval and runs the approved Cypher; a read then finds what it wrote. See {@link BuiltModule} for the channel and
 * for {@code -Dgateway.e2e.officialLists=true}.
 */
@Tag("neo4j-it")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Neo4jModuleCoreProcessIT {

  private static final String LABEL = "GateRookModuleIt";
  private static BuiltModule module;

  @TempDir
  static Path home;

  @BeforeAll
  static void start() throws Exception {
    assumeTrue(TestServices.enabled(), "The Neo4j module test needs the Compose test services");
    assumeTrue(Files.isReadable(TestServices.file("run/neo4j-certs/ca.crt")),
        "Start the neo4j profile: scripts/test-services.sh up neo4j");
    Path built = BuiltModule.packageOf("neo4j");
    assumeTrue(built != null, "Build modules/neo4j first: mvn -pl modules/neo4j -am package");
    module = BuiltModule.start(home, built);
  }

  @AfterAll
  static void stop() {
    if (module != null) {
      module.close();
    }
  }

  @Test
  @Order(1)
  void neo4jIsACardThatDownloadsAndItsFormInstallsTheModule() throws Exception {
    JsonNode card = module.api.card("neo4j");
    assertThat(card.path("name").asString()).isEqualTo("Neo4j");
    assertThat(card.path("kind").asString()).isEqualTo("built-in");
    assertThat(card.path("tools").asInt()).isEqualTo(3);
    assertThat(card.at("/module/installed").asBoolean()).isFalse();
    assertThat(card.at("/module/sizeBytes").asLong()).isEqualTo(module.jar.length);

    JsonNode form = module.api.json(module.api.admin("GET", "/api/connector-types/neo4j/form", null), 200);
    assertThat(form.at("/schema/properties/uri").isObject()).as(form.toString()).isTrue();
    assertThat(module.api.module(module.manifest.id()).path("state").asString()).isEqualTo("ready");
  }

  @Test
  @Order(2)
  void aConnectionIsTestedWrittenWithApprovalAndReadThroughTheModulesHost() throws Exception {
    ObjectNode config = CoreProcess.JSON.createObjectNode()
        .put("uri", "bolt://127.0.0.1:" + TestServices.port("GW_NEO4J_PORT")).put("database", "neo4j");
    config.putObject("authentication").put("method", "password").put("user", "neo4j");
    ObjectNode connection = CoreProcess.JSON.createObjectNode().put("name", "graph-dev").put("type", "neo4j")
        .put("env", "dev");
    connection.set("config", config);
    connection.putObject("secrets").putObject("/authentication/password").put("source", "local")
        .put("value", TestServices.value("GW_NEO4J_PASSWORD"));
    module.api.json(module.api.admin("POST", "/api/connections", connection.toString()), 201);

    JsonNode tested = module.api.json(module.api.admin("POST", "/api/connections/graph-dev/test", null), 200);
    assertThat(tested.path("ok").asBoolean()).as(tested.toString()).isTrue();

    module.api.endpoint("graph", "graph-dev");
    assertThat(module.api.modes("graph", "graph-dev")).containsEntry("neo4j_query", "READ_ONLY")
        .containsEntry("neo4j_schema", "READ_ONLY").containsEntry("neo4j_execute", "APPROVAL").hasSize(3);

    // A write sent as a read is refused by the connector's own classification.
    String create = "MERGE (n:" + LABEL + " {id: 'm1'}) SET n.owner = 'ann', n.balance = 120";
    JsonNode refused = module.api.call(2, "neo4j_query", "{\"connection\":\"graph-dev\",\"cypher\":\"" + create + "\"}");
    assertThat(refused.toString()).doesNotContain("\"isError\":false").contains("neo4j_execute");

    JsonNode pending = module.api.call(3, "neo4j_execute", "{\"connection\":\"graph-dev\",\"cypher\":\"" + create + "\"}");
    String handle = pending.at("/result/structuredContent/handle").asString();
    assertThat(handle).as(pending.toString()).startsWith("ap_");
    JsonNode detail = module.api.json(module.api.admin("GET", "/api/approvals/" + handle, null), 200);
    assertThat(detail.at("/action/text").asString()).as("the connector's exact action").contains(create);
    JsonNode status = module.api.approve(4, handle);
    assertThat(status.at("/result/structuredContent/status").asString()).as(status.toString())
        .isEqualTo("completed");

    JsonNode rows = module.api.call(5, "neo4j_query", "{\"connection\":\"graph-dev\",\"cypher\":\"MATCH (n:" + LABEL
        + " {id: 'm1'}) RETURN n.owner AS owner, n.balance AS balance\"}");
    assertThat(rows.at("/result/isError").asBoolean(true)).as(rows.toString()).isFalse();
    assertThat(rows.at("/result/structuredContent").toString()).contains("ann").contains("120");

    JsonNode schema = module.api.call(6, "neo4j_schema", "{\"connection\":\"graph-dev\"}");
    assertThat(schema.at("/result/structuredContent").toString()).as(schema.toString()).contains(LABEL);
  }
}
