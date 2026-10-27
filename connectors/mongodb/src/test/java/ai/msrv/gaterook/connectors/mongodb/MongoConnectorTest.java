package ai.msrv.gaterook.connectors.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.EndpointView;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.PreparedAction;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.connector.api.ToolDefinition.Nature;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The connector without a server: manifest, tools per endpoint, classification, the exact action, refusals. */
class MongoConnectorTest {

  private final MongoConnector connector = new MongoConnector();

  @AfterEach
  void close() {
    connector.close();
  }

  @Test
  void manifestIsTheCatalogCard() {
    ConnectorManifest m = connector.manifest();

    assertThat(m.id()).isEqualTo("mongodb");
    assertThat(m.kind()).isEqualTo(ConnectorManifest.Kind.BUILT_IN);
    assertThat(m.name()).isEqualTo("MongoDB");
    assertThat(m.group()).isEqualTo("Databases");
    assertThat(m.icon()).isEqualTo("leaf");
    assertThat(m.configSchema().path("type").asString()).isEqualTo("object");
    assertThat(m.policySchema()).isNull();
  }

  @Test
  void publishesTwoReadToolsWithOutputSchemas() {
    List<ToolDefinition> tools = connector.manifest().tools();

    assertThat(tools).extracting(ToolDefinition::name).containsExactly("mongo_find", "mongo_aggregate");
    for (ToolDefinition t : tools) {
      assertThat(t.nature()).as(t.name()).isEqualTo(Nature.READ);
      assertThat(t.mutating()).as(t.name()).isFalse();
      assertThat(t.defaultMode()).isEqualTo(Mode.READ_ONLY);
      assertThat(t.prodDefaultMode()).isEqualTo(Mode.READ_ONLY);
      assertThat(t.outputSchema()).as(t.name()).isNotNull();
      assertThat(t.annotations().readOnlyHint()).isTrue();
      assertThat(t.annotations().destructiveHint()).isFalse();
      assertThat(t.inputSchema().at("/properties/connection").toString()).isEqualTo("{\"type\":\"string\"}");
      assertThat(t.risk().privateData()).isTrue();
      assertThat(t.risk().untrustedContent()).isTrue();
    }
  }

  @Test
  void onAnEndpointTheDescriptionsCarryTheStrictestLimits() {
    ConnectionConfig dev = Fixtures.config("dev-mongo", Env.DEV, "{\"connection\":{\"uri\":\"mongodb://h/o\"}}", 1);
    ConnectionConfig quick = Fixtures.config("quick-mongo", Env.DEV, "{\"connection\":{\"uri\":\"mongodb://h/o\"},"
        + "\"advanced\":{\"maxTimeMs\":1500}}", 1);
    EndpointView one = new EndpointView("dev", Env.DEV, List.of(dev), Map.of("dev-mongo",
        Fixtures.Context.limits(Env.DEV)), List.of());
    EndpointView two = new EndpointView("dev", Env.DEV, List.of(dev, quick), Map.of("dev-mongo",
        Fixtures.Context.limits(Env.DEV), "quick-mongo", Fixtures.Context.limits(Env.DEV)), List.of());
    ConnectionConfig prod = Fixtures.config("prod-mongo", Env.PROD, "{\"connection\":{\"uri\":\"mongodb://h/o\"}}",
        1);
    EndpointView incident = new EndpointView("incident-prod", Env.PROD, List.of(prod), Map.of("prod-mongo",
        Fixtures.Context.limits(Env.PROD)), List.of());

    assertThat(connector.tools(one).getFirst().description())
        .isEqualTo("Find documents with a filter and a projection. Max 1,000 documents, 5 s.");
    assertThat(connector.tools(two).get(1).description())
        .isEqualTo("Run an aggregation pipeline. $out and $merge are refused. Max 1,000 documents, 1.5 s.");
    assertThat(connector.tools(incident).getFirst().description()).endsWith("Max 100 documents, 3 s.");
    assertThat(connector.tools(incident).getFirst().inputSchema()).isEqualTo(connector.manifest().tools()
        .getFirst().inputSchema());
  }

  @Test
  void unknownToolsAndUnusableConnectionsAreDenied() {
    ConnectionConfig ok = Fixtures.uri(Env.DEV, "mongodb://h/orders");
    ConnectionConfig userInUri = Fixtures.uri(Env.DEV, "mongodb://gw:hunter2@h/orders");

    Classification unknown = connector.classify(Fixtures.call("mongo_insert", "{\"collection\":\"orders\"}"), ok,
        Fixtures.policy(Env.DEV));
    Classification bad = connector.classify(Fixtures.call("mongo_find", "{\"collection\":\"orders\"}"), userInUri,
        Fixtures.policy(Env.DEV));

    assertThat(unknown.ruleId()).isEqualTo("dev.mongodb.unknown-tool");
    assertThat(bad.ruleId()).isEqualTo("dev.mongodb.bad-config");
    assertThat(bad.reason()).contains("Remove the user name and password").doesNotContain("hunter2");
  }

  @Test
  void theExactActionIsTheCommandAsSentWithTheCallsLimits() {
    ConnectionConfig cfg = Fixtures.config(Env.DEV, "{\"connection\":{\"uri\":\"mongodb://mongo.dev.internal:27017/"
        + "orders\",\"readPreference\":\"secondaryPreferred\"}}");
    ToolCall call = Fixtures.call("mongo_find", "{\"collection\":\"orders\",\"filter\":{\"status\":\"paid\","
        + "\"n\":{\"$lt\":5}},\"projection\":{\"s\":1,\"n\":1},\"sort\":{\"n\":-1},\"limit\":4}");

    PreparedAction prepared = connector.prepare(call, cfg, Fixtures.Secrets.none(), Fixtures.Context.of(Env.DEV));

    ActionPreview p = prepared.preview();
    assertThat(prepared.outcome()).isEqualTo(PreparedAction.Outcome.APPROVAL);
    assertThat(p.kind()).isEqualTo(ActionPreview.Kind.JSON);
    assertThat(p.text()).isEqualTo("{\"find\": \"orders\", \"filter\": {\"status\": \"paid\", \"n\": {\"$lt\": 5}}, "
        + "\"projection\": {\"s\": 1, \"n\": 1}, \"sort\": {\"n\": -1}, \"skip\": 0, \"limit\": 5, \"maxTimeMS\": 5000, "
        + "\"allowDiskUse\": false}");
    assertThat(p.target()).isEqualTo("orders-mongo · mongo.dev.internal:27017/orders · secondaryPreferred");
    assertThat(p.summary()).isEqualTo("find orders.orders · orders-mongo (dev)");
    assertThat(p.fields()).extracting(ActionPreview.Field::value).contains("at most 4", "5 s (maxTimeMS)");
    assertThat(p.payload().path("filter").asString()).isEqualTo("{\"status\": \"paid\", \"n\": {\"$lt\": "
        + "{\"$numberInt\": \"5\"}}}");
    assertThat(p.payload().path("limit").asInt()).isEqualTo(4);
  }

  @Test
  void anAggregationShowsTheGatewaysLimitStageAndEscapesInvisibleCharacters() {
    ConnectionConfig cfg = Fixtures.uri(Env.PROD, "mongodb://h/orders");
    ToolCall call = Fixtures.call("mongo_aggregate", "{\"collection\":\"orders\",\"pipeline\":[{\"$match\":"
        + "{\"note\":\"pay\u202Edetsil\"}}],\"limit\":500}");

    ActionPreview p = connector.prepare(call, cfg, Fixtures.Secrets.none(), Fixtures.Context.of(Env.PROD)).preview();

    // prod: at most 100 documents and 3 s. The driver's JSON writer escapes the right-to-left override: the person
    // reads \u202e where the agent hid the character, and it is still exactly the document that is sent.
    assertThat(p.text()).isEqualTo("{\"aggregate\": \"orders\", \"pipeline\": [{\"$match\": {\"note\": "
        + "\"pay\\u202edetsil\"}}, {\"$limit\": 101}], \"maxTimeMS\": 3000, \"allowDiskUse\": false}");
    assertThat(p.payload().at("/pipeline/0/$match/note").asString()).isEqualTo("pay\u202Edetsil");
    assertThat(p.target()).endsWith("· secondaryPreferred");
  }

  @Test
  void anArgumentThatDoesntParseRejectsTheRequest() {
    PreparedAction p = connector.prepare(Fixtures.call("mongo_find", "{\"collection\":\"orders\",\"filter\":\"{a:\"}"),
        Fixtures.uri(Env.DEV, "mongodb://h/orders"), Fixtures.Secrets.none(), Fixtures.Context.of(Env.DEV));

    assertThat(p.outcome()).isEqualTo(PreparedAction.Outcome.REJECT);
    assertThat(p.message()).startsWith("filter isn't Extended JSON");
  }

  @Test
  void aRegularExpressionDoesntHideNestingFromTheDepthLimit() {
    // The quote inside /"/ once opened a "string" for the depth count, which then skipped every bracket up to the
    // quote of the next regular expression: 50,000 levels reached the driver's recursive reader and took the stack.
    String deep = "[".repeat(49_900) + "]".repeat(49_900);
    String filter = "{\"a\": /\"/, \"b\": " + deep + ", \"c\": /\"/}";
    ConnectionConfig cfg = Fixtures.uri(Env.DEV, "mongodb://h/orders");

    Classification c = connector.classify(Fixtures.call("mongo_find", args("orders",
        filter)), cfg, Fixtures.policy(Env.DEV));

    assertThat(c.ruleId()).isEqualTo("dev.mongodb.bad-argument");
    assertThat(c.reason()).isEqualTo("filter is nested deeper than 64 levels.");
    // A regular expression with a quote stays a regular expression.
    assertThat(connector.classify(Fixtures.call("mongo_find", args("orders",
        "{\"a\": /\"/, \"c\": /\"/i}")), cfg, Fixtures.policy(Env.DEV)).kind())
        .isEqualTo(Classification.Kind.READ);
  }

  /** {@code mongo_find} arguments with {@code filter} as an Extended JSON string. */
  private static String args(String collection, String filter) {
    return Ejson.JSON.createObjectNode().put("collection", collection).put("filter", filter).toString();
  }

  @Test
  void executeRefusesWhatClassifyDeniesBeforeAnyConnection() {
    // Called directly, as if classify had been skipped: nothing is built, nothing is sent.
    ConnectionConfig cfg = Fixtures.uri(Env.DEV, "mongodb://127.0.0.1:1/orders");
    for (String[] c : new String[][] {
        {"mongo_aggregate", "{\"collection\":\"orders\",\"pipeline\":[{\"$out\":\"stolen\"}]}"},
        {"mongo_aggregate", "{\"collection\":\"orders\",\"pipeline\":[{\"$facet\":{\"a\":[{\"$merge\":\"x\"}]}}]}"},
        {"mongo_find", "{\"collection\":\"orders\",\"filter\":{\"$where\":\"sleep(1000)\"}}"},
        {"mongo_find", "{\"database\":\"admin\",\"collection\":\"system.users\"}"}}) {
      long t = System.nanoTime();
      assertThatThrownBy(() -> connector.execute(Fixtures.call(c[0], c[1]), cfg, Fixtures.Secrets.password("pw"),
          Fixtures.Context.of(Env.DEV)))
          .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind())
              .isEqualTo(ConnectorException.Kind.DENIED));
      assertThat(Duration.ofNanos(System.nanoTime() - t)).isLessThan(Duration.ofMillis(500));
    }
    assertThat(connector.openClients()).isZero();
  }

  @Test
  void anApprovedCallRunsThePayloadAndChecksItAgain() {
    ConnectionConfig cfg = Fixtures.uri(Env.DEV, "mongodb://127.0.0.1:1/orders");
    // A payload naming $out (edited after approval) is refused exactly like the arguments would be.
    var payload = Fixtures.json("{\"database\":\"orders\",\"collection\":\"orders\",\"pipeline\":[{\"$out\":\"x\"}],"
        + "\"limit\":5,\"maxTimeMS\":1000}");

    assertThatThrownBy(() -> connector.execute(Fixtures.call("mongo_aggregate", "{\"collection\":\"orders\","
        + "\"pipeline\":[{\"$match\":{}}]}"), cfg, Fixtures.Secrets.none(), Fixtures.Context.approved(Env.DEV,
        payload)))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
          assertThat(e.getMessage()).startsWith("$out writes to a collection");
        });
  }

  @Test
  void anUnusableConnectionFailsItsTestWithTheField() {
    TestResult r = connector.test(Fixtures.uri(Env.PROD, "mongodb://h/orders?tlsInsecure=true"),
        Fixtures.Secrets.none(), Fixtures.Context.of(Env.PROD));

    assertThat(r.ok()).isFalse();
    assertThat(r.error().code()).isEqualTo("mongo.config");
    assertThat(r.hints()).extracting(TestResult.Hint::field).containsExactly("/connection/uri");
  }

  @Test
  void aRefusedPortFailsTheTestWithTheKitsText() {
    ConnectionConfig cfg = Fixtures.config(Env.DEV, "{\"connection\":{\"uri\":\"mongodb://127.0.0.1:1/orders\"},"
        + "\"credentials\":{\"user\":\"gw_reader\"},\"advanced\":{\"serverSelectionTimeoutMs\":1000}}");

    TestResult r = connector.test(cfg, Fixtures.Secrets.password("smoke-only-0001"), Fixtures.Context.of(Env.DEV));

    assertThat(r.ok()).isFalse();
    assertThat(r.error().code()).isEqualTo("mongo.refused");
    assertThat(r.error().message()).isEqualTo("127.0.0.1:1 refused the connection on port 1. Is mongod running?");
    assertThat(r.error().technical()).doesNotContain("smoke-only-0001");
  }

  @Test
  void aUserWithoutAStoredPasswordIsNamed() {
    ConnectionConfig cfg = Fixtures.config(Env.DEV, "{\"connection\":{\"uri\":\"mongodb://127.0.0.1:1/orders\"},"
        + "\"credentials\":{\"user\":\"gw_reader\"}}");

    TestResult r = connector.test(cfg, Fixtures.Secrets.none(), Fixtures.Context.of(Env.DEV));

    assertThat(r.error().code()).isEqualTo("mongo.secret");
    assertThat(r.error().message()).startsWith("No password is stored for gw_reader");
  }
}
