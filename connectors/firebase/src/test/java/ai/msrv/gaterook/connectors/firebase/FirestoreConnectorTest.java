package ai.msrv.gaterook.connectors.firebase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import mockwebserver3.MockResponse;
import mockwebserver3.RecordedRequest;
import mockwebserver3.SocketEffect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** The Firestore connector against a fake of Firestore's REST API and of Google's token endpoints. */
class FirestoreConnectorTest {

  private static final String DOCS = "projects/acme-stage/databases/(default)/documents";
  private static final String API = "/v1/" + DOCS;
  private static final AtomicInteger CALLS = new AtomicInteger();

  private FakeGoogle google;
  private FirestoreConnector firestore;
  private final TestSecrets secrets = new TestSecrets();

  @BeforeEach
  void start() throws Exception {
    google = new FakeGoogle();
    firestore = new FirestoreConnector(google.cloud);
  }

  @AfterEach
  void stop() {
    firestore.close();
    google.close();
  }

  private static ConnectionConfig configWith(Env env, String credentials, String... collections) {
    ObjectNode c = JsonNodeFactory.instance.objectNode().put("project", "acme-stage");
    c.set("credentials", TestCtx.JSON.readTree(credentials));
    if (collections.length > 0) {
      var list = c.putArray("collections");
      for (String col : collections) {
        list.add(col);
      }
    }
    return new ConnectionConfig("conn-fs-" + env.id(), env.id() + "-firestore", "firestore", env, 1, c,
        Set.of("/credentials/serviceAccountKey"), null);
  }

  private static ConnectionConfig config(Env env, String... collections) {
    return configWith(env, "{\"source\": \"adc\"}", collections);
  }

  private static PolicyView policy(Env env) {
    return new PolicyView(env, env.id() + ".firestore", JsonNodeFactory.instance.objectNode(),
        env == Env.PROD ? TestCtx.prod() : TestCtx.dev());
  }

  private static ToolCall call(String tool, String args) {
    return new ToolCall("tc_fs_" + CALLS.incrementAndGet(), tool, "dev-firestore",
        (ObjectNode) TestCtx.JSON.readTree(args), null);
  }

  private Classification classify(String tool, String args, String... collections) {
    return firestore.classify(call(tool, args), config(Env.DEV, collections), policy(Env.DEV));
  }

  private static String doc(String path, String fields) {
    return "{\"name\": \"" + DOCS + "/" + path + "\", \"fields\": " + fields + ", \"createTime\": "
        + "\"2026-01-02T03:04:05.123456Z\", \"updateTime\": \"2026-01-03T03:04:05.654321Z\"}";
  }

  private static MockResponse results(String... docs) {
    StringBuilder out = new StringBuilder("[");
    for (String d : docs) {
      out.append(out.length() > 1 ? "," : "").append("{\"document\": ").append(d)
          .append(", \"readTime\": \"2026-01-04T00:00:00Z\"}");
    }
    if (docs.length == 0) {
      out.append("{\"readTime\": \"2026-01-04T00:00:00Z\"}");
    }
    return FakeGoogle.json(200, out.append("]").toString());
  }

  // ── Tools and routes ────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void publishesItsToolsWithTheWriteAwaitingApproval() {
    var m = firestore.manifest();
    assertThat(m.id()).isEqualTo("firestore");
    assertThat(m.name()).isEqualTo("Cloud Firestore");
    assertThat(m.icon()).isEqualTo("database");
    assertThat(m.group()).isEqualTo("Databases");
    assertThat(m.description()).endsWith(".");
    assertThat(m.configSchema().at("/x-card/description").asString())
        .isEqualTo("Documents, collections and queries. Changes wait for approval.");
    assertThat(m.tools()).extracting(ToolDefinition::name).containsExactly("firestore_list_collections",
        "firestore_get_document", "firestore_query", "firestore_write");
    for (ToolDefinition t : m.tools()) {
      assertThat(t.inputSchema().path("additionalProperties").asBoolean(true)).as(t.name()).isFalse();
      assertThat(t.inputSchema().at("/properties/connection")).as(t.name())
          .isEqualTo(JsonNodeFactory.instance.objectNode().put("type", "string"));
      assertThat(t.title()).isNotEqualTo(t.name());
    }
    assertThat(m.tools().subList(0, 3)).allSatisfy(t -> {
      assertThat(t.nature()).isEqualTo(ToolDefinition.Nature.READ);
      assertThat(t.defaultMode()).isEqualTo(ToolDefinition.Mode.READ_ONLY);
      assertThat(t.prodDefaultMode()).isEqualTo(ToolDefinition.Mode.READ_ONLY);
    });
    ToolDefinition write = m.tools().getLast();
    assertThat(write.mutating()).isTrue();
    assertThat(write.readOnlyModeAllowed()).isFalse();
    assertThat(write.annotations()).isEqualTo(ToolDefinition.Annotations.write(true));
    assertThat(write.outputSchema()).isNull();
    assertThat(List.of(write.defaultMode(), write.prodDefaultMode())).containsOnly(ToolDefinition.Mode.APPROVAL);
  }

  @Test
  void pathsAndFieldPathsAreReadStrictly() {
    assertThat(classify("firestore_get_document", "{\"document\": \"users/alice\"}").kind())
        .isEqualTo(Classification.Kind.READ);
    assertThat(classify("firestore_get_document", "{\"document\": \"/users/alice smith\"}").kind())
        .isEqualTo(Classification.Kind.READ);
    for (String path : List.of("users/../admin", "users/..", "users/.", "users/a#b", "users/a?b", "users/%2e%2e",
        "users/a%2Fb", "users//alice", "users/alice/", "users/__secret__", "users/a\u0000b", "users/a‮b",
        "users/a​b", "users/a\\b", "users", "users/alice/orders", "", "/")) {
      Classification c = classify("firestore_get_document", TestCtx.JSON.writeValueAsString(Map.of("document",
          path)));
      assertThat(c.kind()).as(path).isEqualTo(Classification.Kind.DENY);
      assertThat(c.ruleId()).as(path).isEqualTo("dev.firestore.bad-argument");
    }
    for (String field : List.of("a..b", ".a", "a.", "`open", "`a`b", "a`b", "__x__", "a.__name__", "a\u0001")) {
      Classification c = classify("firestore_query", TestCtx.JSON.writeValueAsString(Map.of("collection", "users",
          "where", List.of(Map.of("field", field, "op", "==", "value", 1)))));
      assertThat(c.kind()).as(field).isEqualTo(Classification.Kind.DENY);
    }
    assertThat(classify("firestore_query", "{\"collection\": \"users\", \"where\": [{\"field\": \"first-name\", "
        + "\"op\": \"==\", \"value\": \"Ann\"}, {\"field\": \"`a.b`.c\", \"op\": \">\", \"value\": 1}]}").kind())
        .isEqualTo(Classification.Kind.READ);
    assertThat(classify("firestore_query", "{\"collection\": \"users\", \"collectionGroup\": \"orders\"}").kind())
        .isEqualTo(Classification.Kind.DENY);
    assertThat(classify("firestore_query", "{\"collection\": \"users/alice\"}").kind())
        .isEqualTo(Classification.Kind.DENY);
    assertThat(classify("firestore_query", "{\"collection\": \"users\", \"where\": [{\"field\": \"a\", \"op\": "
        + "\"<\", \"value\": null}]}").reason()).contains("null and NaN");
    assertThat(classify("firestore_list_collections", "{\"parent\": \"users\"}").kind())
        .isEqualTo(Classification.Kind.DENY);
    assertThat(classify("firestore_nope", "{}").ruleId()).isEqualTo("dev.firestore.unknown-tool");
  }

  @Test
  void theListAllowsIdsEverywhereAndPathsWhereTheyPoint() {
    String[] list = {"orders", "/audit", "users/*/invoices"};
    Map<String, Classification.Kind> documents = Map.of(
        "users/alice", Classification.Kind.DENY,
        "orders/o-1", Classification.Kind.READ,
        "users/alice/orders/o-1", Classification.Kind.READ,
        "audit/e-1", Classification.Kind.READ,
        "teams/t/audit/e-1", Classification.Kind.DENY,
        "users/alice/invoices/i-1", Classification.Kind.READ,
        "teams/t/invoices/i-1", Classification.Kind.DENY);
    documents.forEach((path, kind) -> {
      Classification c = classify("firestore_get_document", "{\"document\": \"" + path + "\"}", list);
      assertThat(c.kind()).as(path).isEqualTo(kind);
      if (kind == Classification.Kind.DENY) {
        assertThat(c.ruleId()).isEqualTo("dev.firestore.collection-not-allowed");
      }
    });
    assertThat(classify("firestore_query", "{\"collectionGroup\": \"orders\"}", list).kind())
        .isEqualTo(Classification.Kind.READ);
    assertThat(classify("firestore_query", "{\"collectionGroup\": \"invoices\"}", list).kind())
        .as("a path never allows a collection group").isEqualTo(Classification.Kind.DENY);
    assertThat(classify("firestore_query", "{\"collection\": \"users\"}", list).kind())
        .isEqualTo(Classification.Kind.DENY);
    assertThat(classify("firestore_write", "{\"operation\": \"delete\", \"document\": \"users/alice\"}", list).kind())
        .isEqualTo(Classification.Kind.DENY);
    Classification write = classify("firestore_write", "{\"operation\": \"update\", \"document\": "
        + "\"users/alice/invoices/i-1\", \"fields\": {\"paid\": true}}", list);
    assertThat(write.kind()).isEqualTo(Classification.Kind.MUTATION);
    assertThat(write.facts().path("operation").asString()).isEqualTo("update");
    assertThat(write.facts().path("collection").asString()).isEqualTo("users/alice/invoices");
  }

  // ── Reads ───────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void aQueryCarriesTheWholeOrderingAndPagesAfterTheLastDocumentShown() throws Exception {
    google.on(API + "/users/alice:runQuery", r -> FakeGoogle.body(r).at("/structuredQuery/startAt").isMissingNode()
        ? results(doc("users/alice/orders/o-3", "{\"created\": {\"timestampValue\": \"2026-03-01T00:00:00Z\"}, "
            + "\"total\": {\"integerValue\": \"40\"}}"), doc("users/alice/orders/o-2", "{\"created\": "
            + "{\"timestampValue\": \"2026-02-01T00:00:00Z\"}, \"total\": {\"integerValue\": \"30\"}}"),
            doc("users/alice/orders/o-1", "{\"created\": {\"timestampValue\": \"2026-01-01T00:00:00Z\"}, \"total\": "
                + "{\"integerValue\": \"20\"}}"))
        : results(doc("users/alice/orders/o-1", "{\"created\": {\"timestampValue\": \"2026-01-01T00:00:00Z\"}, "
            + "\"total\": {\"integerValue\": \"20\"}}")));
    ConnectionConfig cfg = config(Env.DEV);
    String args = "{\"collection\": \"users/alice/orders\", \"where\": [{\"field\": \"status\", \"op\": \"==\", "
        + "\"value\": \"paid\"}, {\"field\": \"refund\", \"op\": \"==\", \"value\": null}, {\"field\": \"total\", "
        + "\"op\": \">\", \"value\": 10}], \"orderBy\": [{\"field\": \"created\", \"direction\": \"desc\"}], "
        + "\"limit\": 2}";
    TestCtx ctx = TestCtx.of();
    ToolResult first = firestore.execute(call("firestore_query", args), cfg, secrets, ctx);

    RecordedRequest sent = google.to(API + "/users/alice:runQuery").getFirst();
    assertThat(sent.getMethod()).isEqualTo("POST");
    assertThat(sent.getHeaders().get("Authorization")).isEqualTo("Bearer " + google.userToken);
    assertThat(sent.getHeaders().get("x-goog-user-project")).isEqualTo("acme-billing");
    JsonNode q = FakeGoogle.body(sent).path("structuredQuery");
    assertThat(q.path("from").toString()).isEqualTo("[{\"collectionId\":\"orders\",\"allDescendants\":false}]");
    JsonNode filters = q.at("/where/compositeFilter/filters");
    assertThat(q.at("/where/compositeFilter/op").asString()).isEqualTo("AND");
    assertThat(filters.get(0).toString()).isEqualTo("{\"fieldFilter\":{\"field\":{\"fieldPath\":\"status\"},"
        + "\"op\":\"EQUAL\",\"value\":{\"stringValue\":\"paid\"}}}");
    assertThat(filters.get(1).toString()).isEqualTo("{\"unaryFilter\":{\"op\":\"IS_NULL\",\"field\":"
        + "{\"fieldPath\":\"refund\"}}}");
    assertThat(filters.get(2).at("/fieldFilter/value").toString()).isEqualTo("{\"integerValue\":\"10\"}");
    // The agent's order, the inequality's field, then __name__, each in the direction of the last.
    assertThat(q.path("orderBy").toString()).isEqualTo("[{\"field\":{\"fieldPath\":\"created\"},\"direction\":"
        + "\"DESCENDING\"},{\"field\":{\"fieldPath\":\"total\"},\"direction\":\"DESCENDING\"},{\"field\":"
        + "{\"fieldPath\":\"__name__\"},\"direction\":\"DESCENDING\"}]");
    assertThat(q.path("limit").asInt()).as("one more than asked: is there a next page").isEqualTo(3);
    assertThat(ctx.taints).contains(google.userToken);

    JsonNode out = first.structuredContent();
    assertThat(out.path("count").asInt()).isEqualTo(2);
    assertThat(out.at("/documents/0/path").asString()).isEqualTo("users/alice/orders/o-3");
    assertThat(out.at("/documents/0/id").asString()).isEqualTo("o-3");
    assertThat(out.at("/documents/0/fields/total").isIntegralNumber()).isTrue();
    assertThat(out.at("/documents/0/fields/created").asString()).isEqualTo("2026-03-01T00:00:00Z");
    String cursor = out.path("nextCursor").asString();
    assertThat(cursor).startsWith("fs1.");
    assertThat(first.content().getFirst().text()).startsWith("2 documents from users/alice/orders")
        .contains("More documents: pass nextCursor.");

    ObjectNode more = (ObjectNode) TestCtx.JSON.readTree(args);
    more.put("cursor", cursor);
    ToolResult second = firestore.execute(call("firestore_query", more.toString()), cfg, secrets, ctx.next());
    JsonNode start = FakeGoogle.body(google.to(API + "/users/alice:runQuery").getLast()).at("/structuredQuery/startAt");
    assertThat(start.path("before").asBoolean(true)).isFalse();
    assertThat(start.path("values").toString()).isEqualTo("[{\"timestampValue\":\"2026-02-01T00:00:00Z\"},"
        + "{\"integerValue\":\"30\"},{\"referenceValue\":\"" + DOCS + "/users/alice/orders/o-2\"}]");
    assertThat(second.structuredContent().path("count").asInt()).isEqualTo(1);
    assertThat(second.structuredContent().has("nextCursor")).isFalse();

    // The cursor belongs to this request of this connection.
    ObjectNode other = (ObjectNode) TestCtx.JSON.readTree(args);
    other.putArray("orderBy").addObject().put("field", "total");
    other.put("cursor", cursor);
    assertThatThrownBy(() -> firestore.execute(call("firestore_query", other.toString()), cfg, secrets, ctx.next()))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT));
    ConnectionConfig elsewhere = new ConnectionConfig("conn-other", "other", "firestore", Env.DEV, 1, cfg.config(),
        Set.of(), null);
    assertThatThrownBy(() -> firestore.execute(call("firestore_query", more.toString()), elsewhere, secrets,
        ctx.next())).isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.getMessage()).contains("another connection or request"));
    assertThatThrownBy(() -> firestore.execute(call("firestore_query", more.put("cursor", "fs1.forged.x")
        .toString()), cfg, secrets, ctx.next())).isInstanceOf(ConnectorException.class);
  }

  @Test
  void aCollectionGroupAndNameFiltersAreFirestores() throws Exception {
    google.on(API + ":runQuery", r -> results(doc("users/alice/orders/o-1", "{}")));
    firestore.execute(call("firestore_query", "{\"collectionGroup\": \"orders\", \"where\": [{\"field\": "
        + "\"__name__\", \"op\": \"in\", \"value\": [\"users/alice/orders/o-1\", {\"referenceValue\": "
        + "\"users/bob/orders/o-2\"}]}, {\"field\": \"score\", \"op\": \"!=\", \"value\": {\"doubleValue\": "
        + "\"NaN\"}}]}"), config(Env.DEV, "orders"), secrets, TestCtx.of());
    JsonNode q = FakeGoogle.body(google.to(API + ":runQuery").getFirst()).path("structuredQuery");
    assertThat(q.path("from").toString()).isEqualTo("[{\"collectionId\":\"orders\",\"allDescendants\":true}]");
    assertThat(q.at("/where/compositeFilter/filters/0/fieldFilter/value/arrayValue/values").toString())
        .isEqualTo("[{\"referenceValue\":\"" + DOCS + "/users/alice/orders/o-1\"},{\"referenceValue\":\"" + DOCS
            + "/users/bob/orders/o-2\"}]");
    assertThat(q.at("/where/compositeFilter/filters/1").toString()).isEqualTo("{\"unaryFilter\":{\"op\":"
        + "\"IS_NOT_NAN\",\"field\":{\"fieldPath\":\"score\"}}}");
    assertThat(q.path("orderBy").toString()).isEqualTo("[{\"field\":{\"fieldPath\":\"score\"},\"direction\":"
        + "\"ASCENDING\"},{\"field\":{\"fieldPath\":\"__name__\"},\"direction\":\"ASCENDING\"}]");

    // A reference to another database is refused before anything is sent.
    assertThat(classify("firestore_query", "{\"collection\": \"users\", \"where\": [{\"field\": \"owner\", \"op\": "
        + "\"==\", \"value\": {\"referenceValue\": \"projects/other/databases/(default)/documents/users/x\"}}]}")
        .kind()).isEqualTo(Classification.Kind.DENY);
  }

  @Test
  void aDocumentComesBackAsPlainJsonOrAsMissing() throws Exception {
    google.on(API + ":batchGet", r -> {
      String name = FakeGoogle.body(r).at("/documents/0").asString();
      if (name.endsWith("/bob")) {
        return FakeGoogle.json(200, "[{\"missing\": \"" + name + "\", \"readTime\": \"2026-01-04T00:00:00Z\"}]");
      }
      return FakeGoogle.json(200, "[{\"found\": " + doc("users/alice", """
          {"name": {"stringValue": "Alice"}, "age": {"integerValue": "42"},
           "big": {"integerValue": "9007199254740993"}, "edge": {"integerValue": "9007199254740992"},
           "ratio": {"doubleValue": 0.5}, "nan": {"doubleValue": "NaN"}, "ok": {"booleanValue": true},
           "none": {"nullValue": null}, "seen": {"timestampValue": "2026-01-02T03:04:05.123456Z"},
           "raw": {"bytesValue": "AP8Q"}, "boss": {"referenceValue": "%s/users/carol"},
           "home": {"geoPointValue": {"latitude": 52.52, "longitude": 13.405}},
           "tags": {"arrayValue": {"values": [{"stringValue": "a"}, {"integerValue": "1"}]}},
           "address": {"mapValue": {"fields": {"city": {"stringValue": "Berlin"}}}},
           "empty": {"arrayValue": {}}, "long": {"stringValue": "%s"}}""".formatted(DOCS, "x".repeat(9000)))
          + ", \"readTime\": \"2026-01-04T00:00:00Z\"}]");
    });
    ToolResult r = firestore.execute(call("firestore_get_document", "{\"document\": \"users/alice\"}"),
        config(Env.DEV), secrets, TestCtx.of());
    assertThat(FakeGoogle.body(google.to(API + ":batchGet").getFirst()).toString())
        .isEqualTo("{\"documents\":[\"" + DOCS + "/users/alice\"]}");
    JsonNode d = r.structuredContent().path("document");
    assertThat(r.structuredContent().path("found").asBoolean()).isTrue();
    JsonNode f = d.path("fields");
    assertThat(f.path("name").asString()).isEqualTo("Alice");
    assertThat(f.path("age").isIntegralNumber()).isTrue();
    assertThat(f.path("big").isString()).as("beyond 2^53: text").isTrue();
    assertThat(f.path("big").asString()).isEqualTo("9007199254740993");
    assertThat(f.path("edge").isIntegralNumber()).as("2^53 itself is exact").isTrue();
    assertThat(f.path("ratio").asDouble()).isEqualTo(0.5);
    assertThat(f.path("nan").asString()).isEqualTo("NaN");
    assertThat(f.path("ok").asBoolean()).isTrue();
    assertThat(f.path("none").isNull()).isTrue();
    assertThat(f.path("seen").asString()).isEqualTo("2026-01-02T03:04:05.123456Z");
    assertThat(f.path("raw").asString()).isEqualTo("\\x00ff10");
    assertThat(f.path("boss").asString()).isEqualTo("users/carol");
    assertThat(f.path("home").toString()).isEqualTo("{\"latitude\":52.52,\"longitude\":13.405}");
    assertThat(f.path("tags").toString()).isEqualTo("[\"a\",1]");
    assertThat(f.path("address").path("city").asString()).isEqualTo("Berlin");
    assertThat(f.path("empty").toString()).isEqualTo("[]");
    assertThat(f.path("long").asString()).startsWith("xxxx").endsWith("…‹9000 characters›");
    assertThat(d.path("updateTime").asString()).isEqualTo("2026-01-03T03:04:05.654321Z");

    ToolResult missing = firestore.execute(call("firestore_get_document", "{\"document\": \"users/bob\"}"),
        config(Env.DEV), secrets, TestCtx.of());
    assertThat(missing.isError()).isFalse();
    assertThat(missing.structuredContent().path("found").asBoolean(true)).isFalse();
    assertThat(missing.content().getFirst().text()).isEqualTo("No document users/bob.");
  }

  @Test
  void aLargeDocumentIsCutToTheFieldsThatFitAndTheQueryGoesOnAfterIt() throws Exception {
    StringBuilder fields = new StringBuilder("{");
    for (int i = 0; i < 40; i++) {
      fields.append(i == 0 ? "" : ",").append("\"f").append(i).append("\": {\"stringValue\": \"")
          .append("y".repeat(900)).append("\"}");
    }
    String big = fields.append("}").toString();
    google.on(API + ":runQuery", r -> results(doc("logs/a", big), doc("logs/b", big)));
    Limits small = new Limits(100, 100, 32 * 1024, 64 * 1024, 64 * 1024, 64 * 1024, 1_000, Duration.ofSeconds(3),
        Duration.ofSeconds(30), Duration.ofSeconds(10), null);
    ToolResult r = firestore.execute(call("firestore_query", "{\"collection\": \"logs\"}"), config(Env.DEV), secrets,
        TestCtx.of().with(small));
    assertThat(r.truncated()).isTrue();
    assertThat(r.truncatedReason()).isEqualTo("size_limit");
    JsonNode out = r.structuredContent();
    assertThat(out.path("count").asInt()).isEqualTo(1);
    assertThat(out.at("/documents/0/omittedFields").asInt()).isPositive();
    assertThat(out.path("nextCursor").asString()).startsWith("fs1.");
  }

  @Test
  void listingKeepsToTheListAndPagesWithFirestoresToken() throws Exception {
    google.on(API + "/users/alice:listCollectionIds", r -> FakeGoogle.body(r).has("pageToken")
        ? FakeGoogle.json(200, "{\"collectionIds\": [\"orders\"]}")
        : FakeGoogle.json(200, "{\"collectionIds\": [\"private\"], \"nextPageToken\": \"t-2\"}"));
    ConnectionConfig cfg = config(Env.DEV, "orders", "invoices");
    ToolResult r = firestore.execute(call("firestore_list_collections", "{\"parent\": \"users/alice\", \"limit\": "
        + "5}"), cfg, secrets, TestCtx.of());
    List<RecordedRequest> sent = google.to(API + "/users/alice:listCollectionIds");
    assertThat(sent).hasSize(2);
    assertThat(FakeGoogle.body(sent.get(0)).toString()).isEqualTo("{\"pageSize\":5}");
    assertThat(FakeGoogle.body(sent.get(1)).toString()).isEqualTo("{\"pageSize\":5,\"pageToken\":\"t-2\"}");
    assertThat(r.structuredContent().path("collections").toString()).isEqualTo("[\"orders\"]");
    assertThat(r.structuredContent().has("nextCursor")).isFalse();
    assertThat(r.content().getFirst().text()).isEqualTo("1 collection under users/alice: orders");

    google.on(API + ":listCollectionIds", req -> FakeGoogle.json(200, "{\"collectionIds\": [\"orders\", "
        + "\"users\"], \"nextPageToken\": \"t-9\"}"));
    ToolResult root = firestore.execute(call("firestore_list_collections", "{\"limit\": 1}"), cfg, secrets,
        TestCtx.of());
    String cursor = root.structuredContent().path("nextCursor").asString();
    firestore.execute(call("firestore_list_collections", "{\"limit\": 1, \"cursor\": \"" + cursor + "\"}"), cfg,
        secrets, TestCtx.of());
    assertThat(FakeGoogle.body(google.to(API + ":listCollectionIds").getLast()).path("pageToken").asString())
        .isEqualTo("t-9");
  }

  // ── Writes ──────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void aWriteRunsOnlyAfterApprovalAndRunsExactlyWhatWasShown() throws Exception {
    google.on(API + ":commit", r -> FakeGoogle.json(200, "{\"writeResults\": [{\"updateTime\": "
        + "\"2026-05-01T00:00:00Z\"}], \"commitTime\": \"2026-05-01T00:00:00Z\"}"));
    ConnectionConfig cfg = config(Env.DEV, "users");
    ToolCall create = call("firestore_write", "{\"operation\": \"create\", \"document\": \"users/alice\", "
        + "\"fields\": {\"name\": \"Alice\", \"age\": 42, \"ratio\": 0.5, \"tags\": [\"a\", \"b\"], \"meta\": "
        + "{\"since\": {\"timestampValue\": \"2026-01-02T03:04:05Z\"}, \"boss\": {\"referenceValue\": "
        + "\"users/carol\"}}, \"first-name\": \"A\"}, \"note\": \"New customer\"}");
    assertThat(firestore.classify(create, cfg, policy(Env.DEV)).kind()).isEqualTo(Classification.Kind.MUTATION);
    ActionPreview preview = firestore.describeAction(create, cfg);
    assertThat(preview.kind()).isEqualTo(ActionPreview.Kind.JSON);
    assertThat(preview.target()).isEqualTo("dev-firestore · acme-stage/(default) · users/alice");
    assertThat(preview.auditFacets()).containsEntry("operation", "create").containsEntry("collection", "users");
    JsonNode write = preview.payload().at("/writes/0");
    assertThat(write.at("/update/name").asString()).isEqualTo(DOCS + "/users/alice");
    assertThat(write.at("/currentDocument/exists").asBoolean(true)).isFalse();
    assertThat(write.has("updateMask")).isFalse();
    assertThat(write.at("/update/fields/age").toString()).isEqualTo("{\"integerValue\":\"42\"}");
    assertThat(write.at("/update/fields/meta/mapValue/fields/boss/referenceValue").asString())
        .isEqualTo(DOCS + "/users/carol");

    assertThatThrownBy(() -> firestore.execute(create, cfg, secrets, TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
    assertThat(google.to(API + ":commit")).isEmpty();

    // The call's own arguments say something else: what runs is what the person approved.
    ToolCall other = call("firestore_write", "{\"operation\": \"delete\", \"document\": \"users/bob\"}");
    ToolResult done = firestore.execute(other, cfg, secrets, TestCtx.of().approved(preview.payload()));
    RecordedRequest sent = google.to(API + ":commit").getFirst();
    assertThat(sent.getBody().utf8()).isEqualTo(preview.text());
    assertThat(done.structuredContent().path("committed").asBoolean()).isTrue();
    assertThat(done.structuredContent().path("updateTime").asString()).isEqualTo("2026-05-01T00:00:00Z");
    assertThat(done.content().getFirst().text()).isEqualTo("Created users/alice.");

    ActionPreview update = firestore.describeAction(call("firestore_write", "{\"operation\": \"update\", "
        + "\"document\": \"users/alice\", \"fields\": {\"age\": 43, \"first-name\": \"Ann\"}}"), cfg);
    assertThat(update.payload().at("/writes/0/updateMask/fieldPaths").toString())
        .isEqualTo("[\"age\",\"`first-name`\"]");
    assertThat(update.payload().at("/writes/0/currentDocument/exists").asBoolean()).isTrue();
    firestore.execute(other, cfg, secrets, TestCtx.of().approved(update.payload()));
    assertThat(google.to(API + ":commit").getLast().getBody().utf8()).isEqualTo(update.text());

    ActionPreview delete = firestore.describeAction(other, config(Env.DEV, "users"));
    assertThat(delete.payload().toString()).isEqualTo("{\"writes\":[{\"delete\":\"" + DOCS + "/users/bob\","
        + "\"currentDocument\":{\"exists\":true}}]}");
    assertThat(delete.warnings()).anySatisfy(w -> assertThat(w).contains("subcollections"));
  }

  @Test
  void anApprovedWriteIsReadBackAndNothingElseRuns() {
    ConnectionConfig cfg = config(Env.DEV, "users");
    ObjectNode good = (ObjectNode) firestore.describeAction(call("firestore_write", "{\"operation\": \"update\", "
        + "\"document\": \"users/alice\", \"fields\": {\"age\": 43}}"), cfg).payload();
    List<ObjectNode> tampered = List.of(
        withAt(good, "/writes/0/update/name", DOCS.replace("acme-stage", "acme-prod") + "/users/alice"),
        withAt(good, "/writes/0/update/name", DOCS + "/secrets/alice"),
        withAt(good, "/writes/0/currentDocument/exists", "x"),
        add(good, "/writes/0", "updateTransforms"),
        add(good, "", "transaction"),
        withAt(good, "/writes/0/updateMask/fieldPaths/0", "other"),
        withAt(good, "/writes/0/update/fields/age", "plain"));
    for (ObjectNode t : tampered) {
      assertThatThrownBy(() -> firestore.execute(call("firestore_write", "{}"), cfg, secrets,
          TestCtx.of().approved(t))).as(t.toString()).isInstanceOfSatisfying(ConnectorException.class,
              e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
    }
    assertThat(google.to(API + ":commit")).isEmpty();
  }

  private static ObjectNode withAt(ObjectNode tree, String pointer, String value) {
    ObjectNode copy = tree.deepCopy();
    int slash = pointer.lastIndexOf('/');
    JsonNode parent = copy.at(pointer.substring(0, slash));
    String key = pointer.substring(slash + 1);
    if (parent instanceof ObjectNode o) {
      o.put(key, value);
    } else if (parent instanceof tools.jackson.databind.node.ArrayNode a) {
      a.set(Integer.parseInt(key), JsonNodeFactory.instance.stringNode(value));
    }
    return copy;
  }

  private static ObjectNode add(ObjectNode tree, String pointer, String key) {
    ObjectNode copy = tree.deepCopy();
    ((ObjectNode) (pointer.isEmpty() ? copy : copy.at(pointer))).putArray(key);
    return copy;
  }

  @Test
  void aLostAnswerToAWriteIsUnknownAndRefusalsSayWhatHappened() {
    ConnectionConfig cfg = config(Env.DEV);
    JsonNode payload = firestore.describeAction(call("firestore_write", "{\"operation\": \"create\", \"document\": "
        + "\"users/alice\", \"fields\": {\"a\": 1}}"), cfg).payload();
    google.on(API + ":commit", r -> FakeGoogle.json(503, "{\"error\": {\"code\": 503, \"message\": \"The service is "
        + "currently unavailable.\", \"status\": \"UNAVAILABLE\"}}"));
    assertThatThrownBy(() -> firestore.execute(call("firestore_write", "{}"), cfg, secrets,
        TestCtx.of().approved(payload))).isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.OUTCOME_UNKNOWN));
    google.on(API + ":commit", r -> new MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection.INSTANCE)
        .build());
    assertThatThrownBy(() -> firestore.execute(call("firestore_write", "{}"), cfg, secrets,
        TestCtx.of().approved(payload))).isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.OUTCOME_UNKNOWN));
    int sent = google.to(API + ":commit").size();
    google.on(API + ":commit", r -> FakeGoogle.json(409, "{\"error\": {\"code\": 409, \"message\": \"Document "
        + "already exists: " + DOCS + "/users/alice\", \"status\": \"ALREADY_EXISTS\"}}"));
    assertThatThrownBy(() -> firestore.execute(call("firestore_write", "{}"), cfg, secrets,
        TestCtx.of().approved(payload))).isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_ERROR);
          assertThat(e.getMessage()).contains("already exists, so nothing changed");
        });
    assertThat(google.to(API + ":commit")).as("never retried").hasSize(sent + 1);
  }

  @Test
  void aStalledReadIsATimeoutAndTheConnectionDescribesItself() {
    google.on(API + ":runQuery", r -> new MockResponse.Builder().onResponseStart(SocketEffect.Stall.INSTANCE).build());
    assertThatThrownBy(() -> firestore.execute(call("firestore_query", "{\"collection\": \"users\"}"),
        config(Env.DEV), secrets, TestCtx.of().timeout(Duration.ofSeconds(2))))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.TIMEOUT));
    JsonNode d = firestore.describe(config(Env.DEV, "orders"), secrets, TestCtx.of(), "orders").orElseThrow();
    assertThat(d.path("project").asString()).isEqualTo("acme-stage");
    assertThat(d.path("database").asString()).isEqualTo("(default)");
    assertThat(d.path("collections").toString()).isEqualTo("[\"orders\"]");
    assertThat(d.path("collection").asString()).contains("firestore_query");
  }

  @Test
  void firestoresRefusalsSayWhatToDo() {
    ConnectionConfig cfg = config(Env.DEV);
    google.on(API + ":batchGet", r -> FakeGoogle.json(403, "{\"error\": {\"code\": 403, \"message\": \"Missing or "
        + "insufficient permissions.\", \"status\": \"PERMISSION_DENIED\"}}"));
    assertThatThrownBy(() -> firestore.execute(call("firestore_get_document", "{\"document\": \"users/a\"}"), cfg,
        secrets, TestCtx.of())).isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.getMessage()).contains("insufficient permissions", "roles/datastore.viewer"));
    google.on(API + ":batchGet", r -> FakeGoogle.json(403, """
        {"error": {"code": 403, "message": "Cloud Firestore API has not been used in project 987",
         "status": "PERMISSION_DENIED", "details": [{"@type": "type.googleapis.com/google.rpc.ErrorInfo",
         "reason": "SERVICE_DISABLED", "metadata": {"consumer": "projects/987",
         "service": "firestore.googleapis.com"}}]}}"""));
    assertThatThrownBy(() -> firestore.execute(call("firestore_get_document", "{\"document\": \"users/a\"}"), cfg,
        secrets, TestCtx.of())).hasMessageContaining("gcloud services enable firestore.googleapis.com "
            + "--project=987");
    google.on(API + ":runQuery", r -> FakeGoogle.json(400, "[{\"error\": {\"code\": 400, \"message\": \"The query "
        + "requires an index. You can create it here: https://console.firebase.google.com/project/acme/indexes\", "
        + "\"status\": \"FAILED_PRECONDITION\"}}]"));
    assertThatThrownBy(() -> firestore.execute(call("firestore_query", "{\"collection\": \"users\", \"where\": "
        + "[{\"field\": \"a\", \"op\": \"==\", \"value\": 1}], \"orderBy\": [{\"field\": \"b\"}]}"), cfg, secrets,
        TestCtx.of())).isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_ERROR);
          assertThat(e.getMessage()).contains("requires an index", "console.firebase.google.com");
        });
    google.on(API + ":runQuery", r -> FakeGoogle.json(200, "[{\"error\": {\"code\": 400, \"message\": \"Invalid "
        + "query.\", \"status\": \"INVALID_ARGUMENT\"}}]"));
    assertThatThrownBy(() -> firestore.execute(call("firestore_query", "{\"collection\": \"users\"}"), cfg, secrets,
        TestCtx.of())).isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT));
    google.on(API + ":listCollectionIds", r -> FakeGoogle.json(401, "{\"error\": {\"code\": 401, \"message\": "
        + "\"Request had invalid authentication credentials.\", \"status\": \"UNAUTHENTICATED\"}}"));
    assertThatThrownBy(() -> firestore.execute(call("firestore_list_collections", "{}"), cfg, secrets,
        TestCtx.of())).isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_UNAVAILABLE);
          assertThat(e.getMessage()).contains("gcloud auth application-default login");
        });
  }

  // ── Credentials and Test connection ─────────────────────────────────────────────────────────────────────────

  @Test
  void testConnectionNamesThePersonSignedInAndWarnsOnProd() {
    google.on(API + ":listCollectionIds", r -> FakeGoogle.json(200, "{\"collectionIds\": [\"orders\", \"users\"]}"));
    TestResult r = firestore.test(config(Env.PROD), secrets, TestCtx.of().env(Env.PROD));
    assertThat(r.ok()).as(String.valueOf(r.error())).isTrue();
    assertThat(r.identity()).isEqualTo("alice@example.com");
    assertThat(r.checks()).extracting(TestResult.Check::name).containsExactly("identity", "collections");
    assertThat(r.checks().get(0).detail()).isEqualTo("Signed in as alice@example.com");
    assertThat(r.checks().get(1).detail()).isEqualTo("2 collections at the root that agents may use: orders, users");
    assertThat(r.hints()).anySatisfy(h -> {
      assertThat(h.code()).isEqualTo("firestore.long-lived-credentials");
      assertThat(h.severity()).isEqualTo(TestResult.Severity.WARNING);
      assertThat(h.text()).contains("your own Google sign-in");
    });
    RecordedRequest info = google.to("/tokeninfo").getFirst();
    assertThat(info.getMethod()).isEqualTo("POST");
    assertThat(info.getHeaders().get("Authorization")).isEqualTo("Bearer " + google.userToken);
    assertThat(info.getUrl().query()).as("the token never in a URL").isNull();
    assertThat(r.metadataUpdate().path("project").asString()).isEqualTo("acme-stage");
    assertThat(FakeGoogle.body(google.to(API + ":listCollectionIds").getFirst()).path("pageSize").asInt())
        .isEqualTo(FirestoreConnector.PROBE);
  }

  @Test
  void anImpersonatedTokenOrAKeysTokenIsLimitedToDatastore() throws Exception {
    google.on(API + ":listCollectionIds", r -> FakeGoogle.json(200, "{}"));
    TestResult impersonated = firestore.test(configWith(Env.PROD, "{\"source\": \"adc\", \"impersonate\": \""
        + FakeGoogle.SA + "\", \"lifetimeSeconds\": 600}"), secrets, TestCtx.of().env(Env.PROD));
    assertThat(impersonated.ok()).as(String.valueOf(impersonated.error())).isTrue();
    assertThat(impersonated.identity()).isEqualTo(FakeGoogle.SA);
    assertThat(impersonated.checks().getFirst().detail()).isEqualTo("Impersonates " + FakeGoogle.SA + " for 10 min");
    assertThat(impersonated.hints()).noneSatisfy(h -> assertThat(h.code())
        .isEqualTo("firestore.long-lived-credentials"));
    JsonNode iam = FakeGoogle.body(google.requests.stream().filter(q -> q.getUrl().encodedPath()
        .endsWith(":generateAccessToken")).findFirst().orElseThrow());
    assertThat(iam.path("scope").toString()).isEqualTo("[\"https://www.googleapis.com/auth/datastore\"]");
    assertThat(iam.path("lifetime").asString()).isEqualTo("600s");
    assertThat(google.to(API + ":listCollectionIds").getLast().getHeaders().get("Authorization"))
        .isEqualTo("Bearer " + google.impersonatedToken);

    TestSecrets key = new TestSecrets().with("/credentials/serviceAccountKey", FakeGoogle.serviceAccountKey());
    TestResult withKey = firestore.test(configWith(Env.DEV, "{\"source\": \"serviceAccountKey\"}"), key, TestCtx.of());
    assertThat(withKey.identity()).isEqualTo(FakeGoogle.KEY_SA);
    RecordedRequest token = google.requests.stream().filter(q -> q.getUrl().encodedPath().equals("/token")
        && q.getBody().utf8().contains("jwt-bearer")).findFirst().orElseThrow();
    assertThat(FakeGoogle.assertion(token).path("scope").asString())
        .isEqualTo("https://www.googleapis.com/auth/datastore");
    assertThat(withKey.hints()).extracting(TestResult.Hint::code).contains("firestore.no-collections");
  }

  @Test
  void aTestApiWithoutTestTokensGetsNoToken() {
    try (CloudCredentials real = new CloudCredentials(google.endpoints(false), Clock.systemUTC());
        FirestoreConnector c = new FirestoreConnector(real)) {
      assertThatThrownBy(() -> c.execute(call("firestore_get_document", "{\"document\": \"users/alice\"}"),
          config(Env.DEV), secrets, TestCtx.of())).isInstanceOfSatisfying(ConnectorException.class, e -> {
            assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
            assertThat(e.getMessage()).contains("only to Google's own hosts");
          });
      assertThat(c.test(config(Env.DEV), secrets, TestCtx.of()).error().code()).isEqualTo("firestore.credentials");
    }
    assertThat(google.requests).isEmpty();
  }

  @Test
  void anIncompleteConnectionIsRefusedWithoutAnyRequest() {
    ObjectNode c = JsonNodeFactory.instance.objectNode().put("project", "Not A Project");
    c.putObject("credentials").put("source", "adc");
    ConnectionConfig broken = new ConnectionConfig("conn-x", "x", "firestore", Env.DEV, 1, c, Set.of(), null);
    assertThat(firestore.classify(call("firestore_list_collections", "{}"), broken, policy(Env.DEV)).ruleId())
        .isEqualTo("dev.firestore.bad-config");
    assertThat(firestore.test(broken, secrets, TestCtx.of()).error().code()).isEqualTo("firestore.settings");
    ObjectNode badList = (ObjectNode) config(Env.DEV).config().deepCopy();
    badList.putArray("collections").add("users#x");
    assertThat(firestore.test(new ConnectionConfig("conn-y", "y", "firestore", Env.DEV, 1, badList, Set.of(), null),
        secrets, TestCtx.of()).error().code()).isEqualTo("firestore.settings");
    assertThat(firestore.health(broken, secrets, TestCtx.of()).state()).isEqualTo(HealthResult.State.UNREACHABLE);
    assertThat(google.requests).isEmpty();
  }

  @Test
  void aWriteLargerThanARequestMayBeIsRefused() {
    String fields = "{\"blob\": \"" + "z".repeat(300 * 1024) + "\"}";
    Classification c = firestore.classify(call("firestore_write", "{\"operation\": \"create\", \"document\": "
        + "\"users/alice\", \"fields\": " + fields + "}"), config(Env.DEV), policy(Env.DEV));
    assertThat(c.kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(c.ruleId()).isEqualTo("dev.firestore.too-large");
  }
}
