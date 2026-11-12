package ai.msrv.gaterook.e2e.modules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.msrv.gaterook.e2e.TestServices;
import ai.msrv.gaterook.e2e.platform.CoreProcess;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
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
 * The Cassandra connector as the connector module a release downloads (docs/connector-delivery.md): the package of
 * {@code modules/cassandra}, installed by the assembled core when the type's form is asked for, and run in a module
 * host against Apache Cassandra 5.0 of the Compose services (profile {@code cassandra};
 * {@code -Pservices,cassandra-it}). The connection verifies the node's certificate over TLS; reads run at once, a
 * change waits for approval and runs the approved statement. See {@link BuiltModule} for the channel and for
 * {@code -Dgateway.e2e.officialLists=true}.
 *
 * <p>The keyspace is made with {@code cqlsh} in the service's container: the connector itself makes no keyspace.
 */
@Tag("cassandra-it")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CassandraModuleCoreProcessIT {

  private static final String KS = "gaterook_module_it";
  private static final String DC = "gw-dc1";
  private static BuiltModule module;

  @TempDir
  static Path home;

  @BeforeAll
  static void start() throws Exception {
    assumeTrue(TestServices.enabled(), "The Cassandra module test needs the Compose test services");
    assumeTrue(Files.isReadable(TestServices.file("run/cassandra-certs/node.crt")),
        "Start the cassandra profile: scripts/test-services.sh up cassandra");
    Path built = BuiltModule.packageOf("cassandra");
    assumeTrue(built != null, "Build modules/cassandra first: mvn -pl modules/cassandra -am package");
    seed();
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
  void cassandraIsACardThatDownloadsAndItsFormInstallsTheModule() throws Exception {
    JsonNode card = module.api.card("cassandra");
    assertThat(card.path("name").asString()).isEqualTo("Apache Cassandra");
    assertThat(card.path("kind").asString()).isEqualTo("built-in");
    assertThat(card.path("tools").asInt()).isEqualTo(4);
    assertThat(card.at("/module/installed").asBoolean()).isFalse();
    assertThat(card.at("/module/sizeBytes").asLong()).isEqualTo(module.jar.length);

    JsonNode form = module.api.json(module.api.admin("GET", "/api/connector-types/cassandra/form", null), 200);
    assertThat(form.at("/schema/properties/contactPoints").isObject()).as(form.toString()).isTrue();
    JsonNode installed = module.api.module(module.manifest.id());
    assertThat(installed.path("state").asString()).isEqualTo("ready");
    assertThat(installed.path("version").asString()).isEqualTo(module.manifest.version());
  }

  @Test
  @Order(2)
  void aConnectionIsTestedAndReadThroughTheModulesHost() throws Exception {
    ObjectNode config = CoreProcess.JSON.createObjectNode();
    config.putArray("contactPoints").add("127.0.0.1:" + TestServices.port("GW_CASSANDRA_PORT"));
    config.put("localDataCenter", DC).put("keyspace", KS);
    config.putArray("keyspaces").add(KS);
    config.putObject("credentials").put("user", TestServices.value("GW_CASSANDRA_USER"));
    config.put("tls", true).put("caCertificate", Files.readString(TestServices.file("run/cassandra-certs/node.crt")));
    ObjectNode connection = CoreProcess.JSON.createObjectNode().put("name", "cass-dev").put("type", "cassandra")
        .put("env", "dev");
    connection.set("config", config);
    connection.putObject("secrets").putObject("/credentials/password").put("source", "local")
        .put("value", TestServices.value("GW_CASSANDRA_PASSWORD"));
    module.api.json(module.api.admin("POST", "/api/connections", connection.toString()), 201);

    JsonNode tested = module.api.json(module.api.admin("POST", "/api/connections/cass-dev/test", null), 200);
    assertThat(tested.path("ok").asBoolean()).as(tested.toString()).isTrue();
    assertThat(tested.path("serverVersion").asString()).contains("Cassandra 5.");

    module.api.endpoint("cass", "cass-dev");
    assertThat(module.api.modes("cass", "cass-dev")).containsEntry("cassandra_query", "READ_ONLY")
        .containsEntry("cassandra_execute", "APPROVAL").hasSize(4);

    JsonNode keyspaces = module.api.call(2, "cassandra_list_keyspaces", "{\"connection\":\"cass-dev\"}");
    assertThat(keyspaces.at("/result/isError").asBoolean(true)).as(keyspaces.toString()).isFalse();
    assertThat(keyspaces.at("/result/structuredContent").toString()).contains(KS);

    JsonNode rows = module.api.call(3, "cassandra_query", "{\"connection\":\"cass-dev\",\"statement\":\"SELECT id, "
        + "owner, balance FROM " + KS + ".accounts WHERE id = 'a1'\"}");
    assertThat(rows.at("/result/isError").asBoolean(true)).as(rows.toString()).isFalse();
    assertThat(rows.at("/result/structuredContent").toString()).contains("ann").contains("120");

    JsonNode table = module.api.call(4, "cassandra_describe_table", "{\"connection\":\"cass-dev\",\"table\":"
        + "\"accounts\"}");
    assertThat(table.at("/result/structuredContent").toString()).as(table.toString()).contains("balance");

    // The connector's own classification: a write sent as a query is refused before anything runs.
    JsonNode refused = module.api.call(5, "cassandra_query", "{\"connection\":\"cass-dev\",\"statement\":\"DELETE FROM "
        + KS + ".accounts WHERE id = 'a1'\"}");
    assertThat(refused.toString()).doesNotContain("\"isError\":false").contains("cassandra_execute");
  }

  @Test
  @Order(3)
  void aChangeWaitsForApprovalAndRunsTheApprovedStatementOnce() throws Exception {
    String statement = "UPDATE " + KS + ".accounts SET balance = 75 WHERE id = 'a1'";
    JsonNode pending = module.api.call(6, "cassandra_execute", "{\"connection\":\"cass-dev\",\"statement\":\""
        + statement + "\"}");
    String handle = pending.at("/result/structuredContent/handle").asString();
    assertThat(handle).as(pending.toString()).startsWith("ap_");
    JsonNode detail = module.api.json(module.api.admin("GET", "/api/approvals/" + handle, null), 200);
    assertThat(detail.at("/action/text").asString()).as("the connector's exact action").contains(statement);
    JsonNode status = module.api.approve(7, handle);
    assertThat(status.at("/result/structuredContent/status").asString()).as(status.toString())
        .isEqualTo("completed");

    JsonNode rows = module.api.call(8, "cassandra_query", "{\"connection\":\"cass-dev\",\"statement\":\"SELECT balance "
        + "FROM " + KS + ".accounts WHERE id = 'a1'\"}");
    assertThat(rows.at("/result/structuredContent").toString()).as(rows.toString()).contains("75");
  }

  /** A keyspace with one table and one row, made in the service's container. */
  private static void seed() throws Exception {
    String cql = "CREATE KEYSPACE IF NOT EXISTS " + KS + " WITH replication = {'class': 'SimpleStrategy', "
        + "'replication_factor': 1}; CREATE TABLE IF NOT EXISTS " + KS + ".accounts (id text PRIMARY KEY, owner text, "
        + "balance int); INSERT INTO " + KS + ".accounts (id, owner, balance) VALUES ('a1', 'ann', 120);";
    Process cqlsh;
    try {
      cqlsh = new ProcessBuilder("docker", "exec", "gw-test-cassandra", "cqlsh", "-u",
          TestServices.value("GW_CASSANDRA_USER"), "-p", TestServices.value("GW_CASSANDRA_PASSWORD"), "-e", cql)
          .redirectErrorStream(true).start();
    } catch (java.io.IOException e) {
      assumeTrue(false, "docker isn't available to seed the Cassandra service: " + e.getMessage());
      return;
    }
    String said = new String(cqlsh.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(cqlsh.waitFor(120, TimeUnit.SECONDS)).as(said).isTrue();
    assertThat(cqlsh.exitValue()).as(said).isZero();
  }
}
