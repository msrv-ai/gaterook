package ai.msrv.gaterook.connectors.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.client.MongoClient;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * The supported range of servers (report 05 §8): the oldest and the newest release of the matrix, from their official
 * tarballs (downloaded once, SHA-256 checked), only with {@code -Pmongo-it}. What differs between releases is what
 * the connector reads from the server: privilege action names of {@code connectionStatus}, the output of
 * {@code buildInfo}, {@code hello}, {@code listCollections}, error codes, the Extended JSON of the driver.
 */
@Tag("mongo-it")
class MongoVersionsIT {

  static final Map<String, LocalMongod> SERVERS = new ConcurrentHashMap<>();
  static final MongoConnector CONNECTOR = new MongoConnector();

  @AfterAll
  static void stop() {
    CONNECTOR.close();
    SERVERS.values().forEach(LocalMongod::close);
  }

  static LocalMongod server(String version) throws Exception {
    LocalMongod m = SERVERS.get(version);
    if (m == null) {
      m = LocalMongod.start(LocalMongod.tarball(version), true, false);
      SERVERS.put(version, m);
    }
    return m;
  }

  static ConnectionConfig cfg(LocalMongod m, String user) {
    return Fixtures.config("orders-mongo-" + m.version + "-" + user, Env.DEV, "{\"connection\":{\"uri\":\""
        + m.uri("orders") + "\"},\"credentials\":{\"user\":\"" + user + "\"}}", 1);
  }

  static Fixtures.Secrets secrets(String user) {
    return Fixtures.Secrets.password(LocalMongod.password(user));
  }

  static ToolResult run(LocalMongod m, String tool, String args) throws ConnectorException {
    ConnectionConfig cfg = cfg(m, "gw_ro");
    Classification c = CONNECTOR.classify(Fixtures.call(tool, args), cfg, Fixtures.policy(Env.DEV));
    assertThat(c.kind()).as(c.reason()).isEqualTo(Classification.Kind.READ);
    return CONNECTOR.execute(Fixtures.call(tool, args), cfg, secrets("gw_ro"), Fixtures.Context.of(Env.DEV));
  }

  @ParameterizedTest
  @ValueSource(strings = {"7.0.45", "9.0.4"})
  void testConnectionReadsTheRolesOfEveryRelease(String version) throws Exception {
    LocalMongod m = server(version);

    TestResult ro = CONNECTOR.test(cfg(m, "gw_ro"), secrets("gw_ro"), Fixtures.Context.of(Env.DEV));
    TestResult rw = CONNECTOR.test(cfg(m, "gw_rw"), secrets("gw_rw"), Fixtures.Context.of(Env.DEV));
    TestResult root = CONNECTOR.test(cfg(m, LocalMongod.ROOT), secrets(LocalMongod.ROOT),
        Fixtures.Context.of(Env.DEV));

    assertThat(m.version).isEqualTo(version);
    assertThat(ro.ok()).as(String.valueOf(ro.error())).isTrue();
    assertThat(ro.serverVersion()).isEqualTo("MongoDB " + version);
    assertThat(ro.metadataUpdate().path("serverVersion").asString())
        .isEqualTo("MongoDB " + MongoProbe.majorMinor(version));
    assertThat(ro.permissions().level()).isEqualTo(TestResult.Level.READ);
    assertThat(ro.hints()).isEmpty();
    assertThat(rw.permissions().level()).isEqualTo(TestResult.Level.WRITE);
    assertThat(rw.hints()).extracting(TestResult.Hint::code).containsExactly("mongo.user-can-write");
    assertThat(root.permissions().level()).isEqualTo(TestResult.Level.ADMIN);
    assertThat(root.hints()).extracting(TestResult.Hint::code).containsExactly("mongo.user-is-admin");
  }

  @ParameterizedTest
  @ValueSource(strings = {"7.0.45", "9.0.4"})
  void findAndAggregateAnswerTheSameExtendedJson(String version) throws Exception {
    LocalMongod m = server(version);

    JsonNode docs = run(m, "mongo_find", "{\"collection\":\"orders\",\"sort\":{\"_id\":1}}").structuredContent()
        .path("documents");
    JsonNode grouped = run(m, "mongo_aggregate", "{\"collection\":\"orders\",\"pipeline\":[{\"$lookup\":{\"from\":"
        + "\"customers\",\"localField\":\"customer.id\",\"foreignField\":\"_id\",\"as\":\"c\"}},{\"$facet\":{\"by\":"
        + "[{\"$group\":{\"_id\":\"$status\",\"n\":{\"$sum\":1}}},{\"$sort\":{\"_id\":1}}],\"named\":[{\"$match\":"
        + "{\"c.name\":\"Ann\"}},{\"$project\":{\"_id\":1}}]}}]}").structuredContent().path("documents");

    assertThat(docs.get(0).toString()).isEqualTo("{\"_id\":1,\"id\":48213,\"status\":\"pending\",\"total\":"
        + "{\"$numberDecimal\":\"120.50\"},\"created_at\":{\"$date\":\"2026-10-22T12:29:41Z\"},\"customer\":{\"id\":"
        + "{\"$numberLong\":\"9007199254740993\"},\"email\":\"ann@example.com\"}}");
    assertThat(docs.get(2).path("tag").toString()).isEqualTo("{\"$binary\":{\"base64\":\"D4+tW9nLRp+hZXCGdyiVDg==\","
        + "\"subType\":\"04\"}}");
    assertThat(grouped.toString()).isEqualTo("[{\"by\":[{\"_id\":\"pending\",\"n\":2},{\"_id\":\"shipped\",\"n\":1}],"
        + "\"named\":[{\"_id\":2}]}]");
  }

  @ParameterizedTest
  @ValueSource(strings = {"7.0.45", "9.0.4"})
  void writesNeverReachTheServerAndItsTimeLimitStopsAQuery(String version) throws Exception {
    LocalMongod m = server(version);
    ConnectionConfig rw = cfg(m, "gw_rw");
    String out = "{\"collection\":\"orders\",\"pipeline\":[{\"$unionWith\":{\"coll\":\"customers\",\"pipeline\":"
        + "[{\"$out\":\"stolen\"}]}}]}";

    assertThatThrownBy(() -> CONNECTOR.execute(Fixtures.call("mongo_aggregate", out), rw, secrets("gw_rw"),
        Fixtures.Context.of(Env.DEV))).isInstanceOfSatisfying(ConnectorException.class,
        e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
    try (MongoClient root = m.root()) {
      assertThat(root.getDatabase("orders").listCollectionNames().into(new ArrayList<>())).doesNotContain("stolen");
    }

    m.failNextWithTimeLimit(1);
    try {
      assertThatThrownBy(() -> run(m, "mongo_find", "{\"collection\":\"events\"}"))
          .isInstanceOfSatisfying(ConnectorException.class,
              e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.TIMEOUT));
    } finally {
      m.clearFailPoint();
    }
    assertThatThrownBy(() -> run(m, "mongo_find", "{\"collection\":\"orders\",\"filter\":{\"$bogus\":1}}"))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.getMessage()).contains("$bogus"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"7.0.45", "9.0.4"})
  void describeKnowsViewsAndTimeSeries(String version) throws Exception {
    LocalMongod m = server(version);

    JsonNode d = CONNECTOR.describe(cfg(m, "gw_ro"), secrets("gw_ro"), Fixtures.Context.of(Env.DEV), null)
        .orElseThrow();

    assertThat(d.at("/server/version").asString()).isEqualTo(version);
    List<String> types = new ArrayList<>();
    d.at("/databases/0/collections").forEach(c -> types.add(c.path("name").asString() + ":"
        + c.path("type").asString()));
    assertThat(d.at("/databases/0/name").asString()).isEqualTo("orders");
    assertThat(types).contains("orders:collection", "paid_orders:view", "metrics:timeseries")
        .noneMatch(t -> t.startsWith("system."));
  }
}
