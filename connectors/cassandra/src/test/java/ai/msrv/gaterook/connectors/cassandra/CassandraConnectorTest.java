package ai.msrv.gaterook.connectors.cassandra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.PreparedAction;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** The Cassandra connector without a cluster: its tools, routes, refusals, exact actions and settings. */
class CassandraConnectorTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final CassandraConnector cassandra = new CassandraConnector();

  @AfterEach
  void close() {
    cassandra.close();
  }

  static ConnectionConfig config(Env env, Consumer<ObjectNode> tweak) {
    ObjectNode c = JsonNodeFactory.instance.objectNode();
    c.putArray("contactPoints").add("cassandra-1.example.com:9042").add("10.0.4.12");
    c.put("localDataCenter", "dc1");
    c.put("keyspace", "shop");
    c.putArray("keyspaces").add("shop").add("shop_*");
    c.putObject("credentials").put("user", "gw_reader");
    if (tweak != null) {
      tweak.accept(c);
    }
    return new ConnectionConfig("conn-cass-" + env.id(), env.id() + "-cassandra", "cassandra", env, 1, c,
        Set.of(CassandraSettings.PASSWORD), null);
  }

  static PolicyView policy(Env env) {
    return new PolicyView(env, env.id() + ".cassandra", JsonNodeFactory.instance.objectNode(), env == Env.PROD
        ? TestCtx.prod() : TestCtx.dev());
  }

  static ToolCall call(String tool, String args) {
    return new ToolCall("tc_" + Long.toHexString(System.nanoTime()), tool, "dev-cassandra",
        (ObjectNode) JSON.readTree(args), null);
  }

  static String statement(String cql) {
    return JSON.writeValueAsString(Map.of("statement", cql));
  }

  private Classification classify(Env env, Consumer<ObjectNode> tweak, String tool, String args) {
    return cassandra.classify(call(tool, args), config(env, tweak), policy(env));
  }

  private Classification query(String cql) {
    return classify(Env.DEV, null, CassandraTools.QUERY, statement(cql));
  }

  private Classification execute(String cql) {
    return classify(Env.DEV, null, CassandraTools.EXECUTE, statement(cql));
  }

  // ── Tools ───────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void publishesThreeReadsAndOneApprovedChange() {
    assertThat(cassandra.manifest().id()).isEqualTo("cassandra");
    assertThat(cassandra.manifest().name()).isEqualTo("Apache Cassandra");
    assertThat(cassandra.manifest().group()).isEqualTo("Databases");
    assertThat(cassandra.manifest().icon()).isEqualTo("database");
    assertThat(cassandra.manifest().description()).endsWith(".");
    assertThat(cassandra.manifest().tools()).extracting(ToolDefinition::name).containsExactly(
        "cassandra_list_keyspaces", "cassandra_describe_table", "cassandra_query", "cassandra_execute");
    for (ToolDefinition t : cassandra.manifest().tools()) {
      assertThat(t.title()).isNotBlank().isNotEqualTo(t.name());
      assertThat(t.inputSchema().path("additionalProperties").asBoolean(true)).as(t.name()).isFalse();
      assertThat(t.inputSchema().at("/properties/connection")).as(t.name())
          .isEqualTo(JsonNodeFactory.instance.objectNode().put("type", "string"));
      if (!t.mutating()) {
        assertThat(t.defaultMode()).isEqualTo(ToolDefinition.Mode.READ_ONLY);
        assertThat(t.prodDefaultMode()).isEqualTo(ToolDefinition.Mode.READ_ONLY);
      }
    }
    ToolDefinition execute = cassandra.manifest().tools().getLast();
    assertThat(execute.mutating()).isTrue();
    assertThat(execute.readOnlyModeAllowed()).isFalse();
    assertThat(execute.defaultMode()).isEqualTo(ToolDefinition.Mode.APPROVAL);
    assertThat(execute.prodDefaultMode()).isEqualTo(ToolDefinition.Mode.APPROVAL);
    assertThat(execute.outputSchema()).isNull();
    // The core shows the agent's reason to the approver from args.note.
    assertThat(execute.inputSchema().at("/properties/note")).isEqualTo(JSON.readTree("{\"type\": \"string\", "
        + "\"maxLength\": 500, \"description\": \"Why you need this change. Shown to the approver as unverified.\"}"));
    assertThat(cassandra.manifest().tools().get(2).resultShape()).isEqualTo(ToolDefinition.ResultShape.TABLE);
  }

  @Test
  void schemasAreValidAndTheFormKeepsThePasswordWriteOnly() {
    SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    Schema meta = registry.getSchema(SchemaLocation.of("https://json-schema.org/draft/2020-12/schema"));
    JsonNode config = cassandra.manifest().configSchema();
    assertThat(meta.validate(config)).isEmpty();
    cassandra.manifest().tools().forEach(t -> assertThat(meta.validate(t.inputSchema())).as(t.name()).isEmpty());
    assertThat(config.at("/x-card/description").asString()).isNotBlank();
    assertThat(config.at("/properties/credentials/x-gateway-secret").asString()).isEqualTo("login");
    assertThat(config.at("/properties/credentials/properties/password/writeOnly").asBoolean()).isTrue();
    assertThat(config.at("/properties/tls/default").asBoolean()).isTrue();
    assertThat(config.at("/properties/tlsVerify/default").asString()).isEqualTo("full");
    assertThat(config.at("/properties/advanced/properties/allowFiltering/x-forbidden-env").toString())
        .isEqualTo("[\"stage\",\"prod\"]");
    assertThat(config.toString()).doesNotContain("x-person-only");

    Schema form = registry.getSchema(SchemaLocation.of("urn:test:cassandra"), config);
    assertThat(form.validate(config(Env.DEV, null).config())).isEmpty();
    assertThat(form.validate(JSON.readTree("{\"contactPoints\": [\"[2001:db8::1]:9042\"], \"localDataCenter\": "
        + "\"dc1\", \"tls\": false}"))).isEmpty();
    assertThat(form.validate(JSON.readTree("{\"contactPoints\": [], \"localDataCenter\": \"dc1\"}"))).isNotEmpty();
    assertThat(form.validate(JSON.readTree("{\"contactPoints\": [\"a:b:c\"], \"localDataCenter\": \"dc1\"}")))
        .isNotEmpty();
    assertThat(form.validate(JSON.readTree("{\"contactPoints\": [\"db\"]}"))).isNotEmpty();
    assertThat(form.validate(JSON.readTree("{\"contactPoints\": [\"db\"], \"localDataCenter\": \"dc1\", "
        + "\"password\": \"x\"}"))).isNotEmpty();
    Schema queryInput = registry.getSchema(SchemaLocation.of("urn:test:cassandra:query"),
        cassandra.manifest().tools().get(2).inputSchema());
    assertThat(queryInput.validate(JSON.readTree("{\"connection\": \"c\", \"statement\": \"SELECT 1\", \"values\": "
        + "[1, \"a\", {\"k\": 1}]}"))).isEmpty();
    assertThat(queryInput.validate(JSON.readTree("{\"connection\": \"c\", \"statement\": \"SELECT 1\", \"values\": "
        + "{\"id\": 1}}"))).isEmpty();
    assertThat(queryInput.validate(JSON.readTree("{\"connection\": \"c\", \"statement\": \"SELECT 1\", \"values\": "
        + "\"1\"}"))).isNotEmpty();
  }

  // ── Routes ──────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void statementsAreRoutedByWhatTheyDo() {
    assertThat(query("SELECT * FROM shop.orders WHERE id = 1").kind()).isEqualTo(Classification.Kind.READ);
    assertThat(query("SELECT * FROM orders WHERE note = 'DELETE; DROP'").kind()).isEqualTo(Classification.Kind.READ);
    Classification writes = query("DELETE FROM shop.orders WHERE id = 1");
    assertThat(writes.kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(writes.ruleId()).isEqualTo("dev.cassandra.query-writes");
    assertThat(writes.hint()).contains("cassandra_execute");
    Classification change = execute("UPDATE orders SET total = 1 WHERE id = 1 IF total = 0");
    assertThat(change.kind()).isEqualTo(Classification.Kind.MUTATION);
    assertThat(change.facts().path("statement").asString()).isEqualTo("UPDATE");
    assertThat(change.facts().path("target").asString()).isEqualTo("shop.orders");
    assertThat(change.facts().path("conditional").asBoolean()).isTrue();
    assertThat(execute("CREATE TABLE shop_archive.orders (id int PRIMARY KEY)").reason()).contains("schema");
    assertThat(execute("SELECT * FROM shop.orders").ruleId()).isEqualTo("dev.cassandra.execute-read");
    assertThat(execute("TRUNCATE shop.orders").kind()).isEqualTo(Classification.Kind.MUTATION);
    assertThat(classify(Env.PROD, null, CassandraTools.QUERY, statement("INSERT INTO shop.t (id) VALUES (1)"))
        .ruleId()).isEqualTo("prod.cassandra.query-writes");
    assertThat(classify(Env.DEV, null, "cassandra_nothing", "{}").ruleId()).isEqualTo("dev.cassandra.unknown-tool");
  }

  @Test
  void whatStaysWithPeopleIsRefusedByItsOwnRule() {
    Map<String, String> rules = Map.ofEntries(
        Map.entry("BEGIN BATCH INSERT INTO shop.t (id) VALUES (1); APPLY BATCH", "batch"),
        Map.entry("GRANT SELECT ON KEYSPACE shop TO bob", "access-control"),
        Map.entry("REVOKE SELECT ON KEYSPACE shop FROM bob", "access-control"),
        Map.entry("CREATE ROLE bob WITH LOGIN = true", "access-control"),
        Map.entry("LIST PERMISSIONS OF bob", "access-control"),
        Map.entry("ALTER KEYSPACE shop WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 3}",
            "keyspace-change"),
        Map.entry("DROP KEYSPACE shop", "keyspace-change"),
        Map.entry("CREATE FUNCTION shop.f(a int) CALLED ON NULL INPUT RETURNS int LANGUAGE java AS 'return a;'",
            "server-code"),
        Map.entry("CREATE TRIGGER t ON shop.orders USING 'org.example.T'", "server-code"),
        Map.entry("USE shop", "use-keyspace"),
        Map.entry("DESCRIBE TABLES", "not-supported"),
        Map.entry("SELECT * FROM shop.t; DROP TABLE shop.t", "unparsed"),
        Map.entry("SELECT * FROM shop.t WHERE a = 'open", "unparsed"),
        Map.entry("SELECT * FROM shop.t -- end", "unparsed"));
    rules.forEach((cql, rule) -> {
      for (String tool : List.of(CassandraTools.QUERY, CassandraTools.EXECUTE)) {
        Classification c = classify(Env.DEV, null, tool, statement(cql));
        assertThat(c.kind()).as(tool + " " + cql).isEqualTo(Classification.Kind.DENY);
        assertThat(c.ruleId()).as(tool + " " + cql).isEqualTo("dev.cassandra." + rule);
        assertThat(c.reason()).as(cql).isNotBlank();
      }
    });
    assertThat(query("SELECT * FROM shop.t WHERE a = 'open").reason()).contains("doesn't run what it can't read");
  }

  @Test
  void keyspacesAreCheckedWhereverAStatementNamesThem() {
    assertThat(query("SELECT * FROM shop_eu.orders").kind()).isEqualTo(Classification.Kind.READ);
    Classification other = query("SELECT * FROM billing.invoices");
    assertThat(other.ruleId()).isEqualTo("dev.cassandra.keyspace-not-allowed");
    assertThat(other.reason()).contains("billing", "dev");
    assertThat(other.hint()).contains("cassandra_list_keyspaces");
    // A statement without a keyspace uses the connection's; without one, it must name it.
    assertThat(query("SELECT * FROM orders").kind()).isEqualTo(Classification.Kind.READ);
    Classification none = classify(Env.DEV, c -> c.remove("keyspace"), CassandraTools.QUERY,
        statement("SELECT * FROM orders"));
    assertThat(none.ruleId()).isEqualTo("dev.cassandra.no-keyspace");
    Classification notListed = classify(Env.DEV, c -> c.put("keyspace", "billing"), CassandraTools.QUERY,
        statement("SELECT * FROM orders"));
    assertThat(notListed.ruleId()).isEqualTo("dev.cassandra.keyspace-not-allowed");
    // Without a list, every keyspace but Cassandra's own.
    Consumer<ObjectNode> open = c -> c.remove("keyspaces");
    assertThat(classify(Env.DEV, open, CassandraTools.QUERY, statement("SELECT * FROM billing.invoices")).kind())
        .isEqualTo(Classification.Kind.READ);
    assertThat(classify(Env.DEV, open, CassandraTools.QUERY, statement("SELECT * FROM system_views.clients"))
        .ruleId()).isEqualTo("dev.cassandra.keyspace-not-allowed");
    assertThat(classify(Env.DEV, c -> c.putArray("keyspaces").add("*"), CassandraTools.QUERY,
        statement("SELECT * FROM system_views.clients")).ruleId()).isEqualTo("dev.cassandra.keyspace-not-allowed");
    assertThat(classify(Env.DEV, c -> c.putArray("keyspaces").add("system_views"), CassandraTools.QUERY,
        statement("SELECT * FROM system_views.clients")).kind()).isEqualTo(Classification.Kind.READ);
    // Roles, password hashes and permissions: never, whatever the list says.
    for (String cql : List.of("SELECT * FROM system_auth.roles", "SELECT salted_hash FROM system.roles",
        "SELECT * FROM system.role_permissions", "SELECT * FROM dse_security.roles")) {
      assertThat(classify(Env.DEV, c -> c.putArray("keyspaces").add("system*").add("dse_*"), CassandraTools.QUERY,
          statement(cql)).ruleId()).as(cql).isEqualTo("dev.cassandra.auth-tables");
    }
    assertThat(classify(Env.DEV, c -> c.putArray("keyspaces").add("system"), CassandraTools.QUERY,
        statement("SELECT cluster_name FROM system.local")).kind()).isEqualTo(Classification.Kind.READ);
    // No change to Cassandra's own keyspaces, even when they are listed.
    assertThat(classify(Env.DEV, c -> c.putArray("keyspaces").add("system_views"), CassandraTools.EXECUTE,
        statement("UPDATE system_views.settings SET value = 'x' WHERE name = 'y'")).ruleId())
        .isEqualTo("dev.cassandra.system-keyspace");
    // Functions named with a keyspace, and a view's base table, count too.
    assertThat(query("SELECT billing.fmt(total) FROM shop.orders").ruleId())
        .isEqualTo("dev.cassandra.keyspace-not-allowed");
    assertThat(query("SELECT shop_eu.fmt(total), system.now() FROM shop.orders").kind())
        .isEqualTo(Classification.Kind.READ);
    assertThat(execute("CREATE MATERIALIZED VIEW shop.by_total AS SELECT * FROM billing.invoices WHERE total IS NOT "
        + "NULL AND id IS NOT NULL PRIMARY KEY (total, id)").ruleId()).isEqualTo("dev.cassandra.keyspace-not-allowed");
    assertThat(execute("DROP INDEX billing.invoices_total").ruleId()).isEqualTo("dev.cassandra.keyspace-not-allowed");
    assertThat(execute("CREATE INDEX ON billing.invoices (total)").ruleId())
        .isEqualTo("dev.cassandra.keyspace-not-allowed");
  }

  @Test
  void allowFilteringIsRefusedUnlessADevConnectionAllowsIt() {
    String cql = "SELECT * FROM shop.orders WHERE total > 10 ALLOW FILTERING";
    Classification refused = query(cql);
    assertThat(refused.ruleId()).isEqualTo("dev.cassandra.allow-filtering");
    assertThat(refused.hint()).contains("partition key");
    Consumer<ObjectNode> allow = c -> c.putObject("advanced").put("allowFiltering", true);
    assertThat(classify(Env.DEV, allow, CassandraTools.QUERY, statement(cql)).kind())
        .isEqualTo(Classification.Kind.READ);
    assertThat(classify(Env.STAGE, allow, CassandraTools.QUERY, statement(cql)).ruleId())
        .isEqualTo("stage.cassandra.allow-filtering");
    assertThat(classify(Env.PROD, allow, CassandraTools.QUERY, statement(cql)).ruleId())
        .isEqualTo("prod.cassandra.allow-filtering");
    // A dev connection on a stricter endpoint follows the endpoint.
    assertThat(cassandra.classify(call(CassandraTools.QUERY, statement(cql)), config(Env.DEV, allow),
        policy(Env.PROD)).ruleId()).isEqualTo("prod.cassandra.allow-filtering");
  }

  @Test
  void metadataToolsCheckTheirKeyspaceAndValuesTheirShape() {
    assertThat(classify(Env.DEV, null, CassandraTools.LIST_KEYSPACES, "{}").kind())
        .isEqualTo(Classification.Kind.READ);
    assertThat(classify(Env.DEV, null, CassandraTools.LIST_KEYSPACES, "{\"keyspace\": \"billing\"}").ruleId())
        .isEqualTo("dev.cassandra.keyspace-not-allowed");
    assertThat(classify(Env.DEV, null, CassandraTools.DESCRIBE_TABLE, "{\"table\": \"orders\"}").kind())
        .isEqualTo(Classification.Kind.READ);
    assertThat(classify(Env.DEV, null, CassandraTools.DESCRIBE_TABLE, "{\"table\": \"\\\"Shop\\\".\\\"Orders\\\"\"}")
        .ruleId()).isEqualTo("dev.cassandra.keyspace-not-allowed");
    assertThat(classify(Env.DEV, null, CassandraTools.DESCRIBE_TABLE, "{\"table\": \"system_auth.roles\"}").ruleId())
        .isEqualTo("dev.cassandra.auth-tables");
    assertThat(classify(Env.DEV, null, CassandraTools.DESCRIBE_TABLE, "{\"table\": \"a b\"}").ruleId())
        .isEqualTo("dev.cassandra.bad-argument");
    assertThat(classify(Env.DEV, null, CassandraTools.QUERY, "{\"statement\": \"SELECT * FROM t\", \"values\": 3}")
        .ruleId()).isEqualTo("dev.cassandra.bad-argument");
    ObjectNode many = JsonNodeFactory.instance.objectNode().put("statement", "SELECT * FROM shop.t");
    var values = many.putArray("values");
    for (int i = 0; i <= CassandraRules.MAX_VALUES; i++) {
      values.add(i);
    }
    assertThat(cassandra.classify(new ToolCall("tc", CassandraTools.QUERY, "c", many, null), config(Env.DEV, null),
        policy(Env.DEV)).ruleId()).isEqualTo("dev.cassandra.bad-argument");
  }

  @Test
  void aConnectionWithBadSettingsRefusesEveryCall() {
    Classification c = classify(Env.DEV, x -> x.putArray("contactPoints"), CassandraTools.QUERY,
        statement("SELECT * FROM shop.t"));
    assertThat(c.ruleId()).isEqualTo("dev.cassandra.bad-config");
    assertThat(c.reason()).contains("contact point");
  }

  // ── The exact action ────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void theApprovalShowsTheExactStatementItsValuesAndWhatItDoes() {
    ToolCall call = call(CassandraTools.EXECUTE, "{\"statement\": \"UPDATE orders USING TIMESTAMP ? SET total = ? "
        + "WHERE id = ? IF total = 0\", \"values\": [1700000000000000, \"12.30\", 7], \"note\": \"fix the total\"}");
    ActionPreview p = cassandra.describeAction(call, config(Env.DEV, null));
    assertThat(p.kind()).isEqualTo(ActionPreview.Kind.SQL);
    assertThat(p.text()).isEqualTo("UPDATE orders USING TIMESTAMP ? SET total = ? WHERE id = ? IF total = 0");
    assertThat(p.target()).isEqualTo("dev-cassandra · dc1 · shop.orders");
    assertThat(p.summary()).isEqualTo("cassandra_execute on dev-cassandra: UPDATE shop.orders");
    assertThat(p.fields()).extracting(ActionPreview.Field::label).containsExactly("Values", "Keyspace",
        "Consistency");
    assertThat(p.fields().getFirst().value()).isEqualTo("[1700000000000000,\"12.30\",7]");
    assertThat(p.fields().get(2).value()).isEqualTo("LOCAL_QUORUM, serial LOCAL_SERIAL");
    assertThat(p.impact()).extracting(ActionPreview.Field::label).contains("Target", "Condition", "Retried");
    assertThat(p.warnings()).singleElement().asString().contains("USING TIMESTAMP");
    assertThat(p.payload().path("statement").asString()).isEqualTo(p.text());
    assertThat(p.payload().path("values").toString()).isEqualTo("[1700000000000000,\"12.30\",7]");
    assertThat(p.payload().path("keyspace").asString()).isEqualTo("shop");
    assertThat(p.payload().has("note")).isFalse();
    assertThat(p.auditFacets()).containsEntry("statement", "UPDATE").containsEntry("target", "shop.orders");

    assertThat(cassandra.describeAction(call(CassandraTools.EXECUTE, statement("TRUNCATE shop.orders")),
        config(Env.DEV, null)).warnings()).singleElement().asString().contains("every row", "can't be undone");
    assertThat(cassandra.describeAction(call(CassandraTools.EXECUTE, statement("DROP TABLE shop.orders")),
        config(Env.DEV, null)).warnings()).singleElement().asString().contains("all of its data");
    ActionPreview ddl = cassandra.describeAction(call(CassandraTools.EXECUTE, statement("ALTER TABLE shop.orders "
        + "ADD note text")), config(Env.DEV, null));
    assertThat(ddl.impact()).extracting(ActionPreview.Field::label).contains("Schema");
    assertThat(ddl.payload().has("values")).isFalse();

    // Long values are cut in their field, and the approval says so; the payload keeps all of them.
    String note = "n".repeat(3_000);
    ActionPreview longValues = cassandra.describeAction(call(CassandraTools.EXECUTE, "{\"statement\": \"UPDATE "
        + "shop.orders SET note = ? WHERE id = 1\", \"values\": [\"" + note + "\"]}"), config(Env.DEV, null));
    assertThat(longValues.fields().getFirst().value()).hasSize(CassandraConnector.SHOWN_VALUES);
    assertThat(longValues.warnings()).singleElement().asString().contains("first 2000 of 3004 characters");
    assertThat(longValues.payload().at("/values/0").asString()).isEqualTo(note);

    // A read an endpoint put in Approval: its arguments are the action.
    ActionPreview read = cassandra.describeAction(call(CassandraTools.QUERY, statement("SELECT * FROM orders")),
        config(Env.DEV, null));
    assertThat(read.text()).isEqualTo("SELECT * FROM orders");
    assertThat(read.payload().path("arguments").path("statement").asString()).isEqualTo("SELECT * FROM orders");
  }

  @Test
  void aChangeRunsOnlyApprovedAndOnlyAsApproved() throws Exception {
    ConnectionConfig cfg = config(Env.DEV, null);
    ToolCall call = call(CassandraTools.EXECUTE, statement("DELETE FROM shop.orders WHERE id = 1"));
    assertThatThrownBy(() -> cassandra.execute(call, cfg, TestSecrets.password("pw"), TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
          assertThat(e.getMessage()).startsWith("Not approved");
        });
    // A payload that classify would refuse is refused again, before any connection.
    for (String refused : List.of("GRANT ALL ON KEYSPACE shop TO bob", "SELECT * FROM shop.orders",
        "DELETE FROM billing.invoices WHERE id = 1", "BEGIN BATCH DELETE FROM shop.t WHERE id = 1 APPLY BATCH")) {
      JsonNode payload = JsonNodeFactory.instance.objectNode().put("statement", refused).put("keyspace", "shop");
      assertThatThrownBy(() -> cassandra.execute(call, cfg, TestSecrets.password("pw"), TestCtx.of()
          .approved(payload))).as(refused).isInstanceOfSatisfying(ConnectorException.class,
              e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
    }
    // An unqualified statement approved for another keyspace doesn't run in this one.
    JsonNode moved = JsonNodeFactory.instance.objectNode().put("statement", "DELETE FROM orders WHERE id = 1")
        .put("keyspace", "shop_old");
    assertThatThrownBy(() -> cassandra.execute(call, cfg, TestSecrets.password("pw"), TestCtx.of().approved(moved)))
        .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.getMessage())
            .contains("keyspace changed"));
    // A read tool never runs a write, whatever arrives.
    assertThatThrownBy(() -> cassandra.execute(call(CassandraTools.QUERY, statement("DELETE FROM shop.orders WHERE "
        + "id = 1")), cfg, TestSecrets.password("pw"), TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind())
            .isEqualTo(ConnectorException.Kind.DENIED));
  }

  @Test
  void prepareChecksNothingButChangesAndRefusesAddressesGateRookNeverReaches() throws Exception {
    ConnectionConfig cfg = config(Env.DEV, null);
    ToolCall ddl = call(CassandraTools.EXECUTE, statement("DROP TABLE shop.orders"));
    assertThat(cassandra.prepare(ddl, cfg, TestSecrets.password("pw"), TestCtx.of()).outcome())
        .isEqualTo(PreparedAction.Outcome.APPROVAL);
    ConnectionConfig metadata = config(Env.DEV, c -> c.putArray("contactPoints").add("169.254.169.254:9042"));
    PreparedAction refused = cassandra.prepare(call(CassandraTools.EXECUTE, statement("DELETE FROM shop.orders "
        + "WHERE id = 1")), metadata, TestSecrets.password("pw"), TestCtx.of());
    assertThat(refused.outcome()).isEqualTo(PreparedAction.Outcome.REJECT);
    assertThat(refused.message()).contains("169.254.169.254", "never connects");
  }

  @Test
  void describeTellsAgentsHowToUseTheConnection() {
    JsonNode d = cassandra.describe(config(Env.DEV, null), TestSecrets.password("pw"), TestCtx.of(), "orders")
        .orElseThrow();
    assertThat(d.path("localDataCenter").asString()).isEqualTo("dc1");
    assertThat(d.path("keyspace").asString()).isEqualTo("shop");
    assertThat(d.path("keyspaces").toString()).contains("shop_*");
    assertThat(d.path("reads").asString()).contains("ALLOW FILTERING is refused");
    assertThat(d.path("table").asString()).contains("cassandra_describe_table");
    JsonNode open = cassandra.describe(config(Env.DEV, c -> c.remove("keyspaces")), null, TestCtx.of(), null)
        .orElseThrow();
    assertThat(open.path("keyspaces").asString()).contains("system keyspaces");
  }

  @Test
  void testConnectionReportsSettingsAndAddressesWithoutConnecting() {
    TestResult bad = cassandra.test(config(Env.DEV, c -> c.remove("localDataCenter")), TestSecrets.password("pw"),
        TestCtx.of());
    assertThat(bad.ok()).isFalse();
    assertThat(bad.error().code()).isEqualTo("cassandra.settings");
    TestResult metadata = cassandra.test(config(Env.DEV, c -> c.putArray("contactPoints").add("169.254.169.254")),
        TestSecrets.password("pw"), TestCtx.of());
    assertThat(metadata.error().code()).isEqualTo("cassandra.address");
    TestResult noUser = cassandra.test(config(Env.DEV, c -> c.remove("credentials")), TestSecrets.password("pw"),
        TestCtx.of());
    assertThat(noUser.error().code()).isEqualTo("cassandra.credentials");
    assertThat(noUser.error().message()).contains("no user");
  }

  @Test
  void describeTextSummarizesATable() {
    ObjectNode d = (ObjectNode) JSON.readTree("{\"keyspace\": \"shop\", \"table\": \"orders\", \"kind\": \"table\", "
        + "\"columns\": [{\"name\": \"customer\", \"type\": \"uuid\", \"kind\": \"partition_key\"}, {\"name\": "
        + "\"placed\", \"type\": \"timestamp\", \"kind\": \"clustering\", \"order\": \"desc\"}, {\"name\": \"region\", "
        + "\"type\": \"text\", \"kind\": \"static\"}, {\"name\": \"total\", \"type\": \"decimal\", \"kind\": "
        + "\"regular\"}], \"indexes\": [{\"name\": \"orders_total\", \"target\": \"total\"}], \"views\": "
        + "[\"orders_by_total\"], \"options\": {\"defaultTimeToLive\": 60, \"compaction\": {\"class\": "
        + "\"UnifiedCompactionStrategy\"}}}");
    assertThat(CassandraConnector.describeText(d)).isEqualTo("shop.orders (table): customer uuid partition key, "
        + "placed timestamp clustering desc, region text static, total decimal · indexes: orders_total on total · "
        + "views: orders_by_total · default TTL 60 s · UnifiedCompactionStrategy");
  }
}
