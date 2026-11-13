package com.gaterook.connectors.cassandra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datastax.oss.driver.api.core.config.OptionsMap;
import com.datastax.oss.driver.api.core.config.TypedDriverOption;
import com.datastax.oss.driver.internal.core.loadbalancing.DcInferringLoadBalancingPolicy;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.Suggestions;
import com.gaterook.connector.api.Suggestions.Option;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Help for the Cassandra form: cqlsh's host, port and user (never its password) and a local node, offered while the
 * fields are empty; the form's defaults; Local data center required yet asked for ({@code x-suggest}) and inferred by a
 * draft's own session; and what a signed-in session's facts become. Signing in to a real node is covered by
 * {@code CassandraIT}.
 */
class CassandraSuggestionsTest {

  @TempDir
  Path dir;

  private static ConnectionConfig draft(Consumer<ObjectNode> tweak) {
    ObjectNode c = JsonNodeFactory.instance.objectNode();
    c.putArray("contactPoints");
    if (tweak != null) {
      tweak.accept(c);
    }
    return new ConnectionConfig("draft-1", "draft", "cassandra", Env.DEV, 0, c, Set.of(CassandraSettings.PASSWORD),
        null);
  }

  private Suggestions local(ConnectionConfig cfg, boolean listening, Path cqlshrc) {
    return CassandraSuggestions.suggest(cfg, new TestSecrets(), TestCtx.of(), Suggestions.Scope.LOCAL,
        port -> listening && port == 9042, cqlshrc);
  }

  @Test
  void cqlshsHostPortAndUserAreOfferedButNeverItsPassword() throws Exception {
    Path rc = dir.resolve("cqlshrc");
    Files.writeString(rc, """
        ; cqlsh settings
        [authentication]
        username = app_reader
        password = cnry-cqlsh-password-9
        [connection]
        hostname = cassandra.dev.example.test
        port = 9142
        ssl = true
        """);

    Suggestions s = local(draft(null), true, rc);

    Suggestions.Field points = s.fields().get("/contactPoints");
    assertThat(points.value()).as("never filled in").isNull();
    assertThat(points.options()).extracting(Option::value)
        .containsExactly("cassandra.dev.example.test:9142", "localhost:9042");
    assertThat(points.options().getFirst().detail()).isEqualTo("cqlsh connects here (~/.cassandra/cqlshrc)");
    assertThat(points.options().get(1).detail()).isEqualTo("A Cassandra node answers on port 9042");
    Suggestions.Field user = s.fields().get("/credentials/user");
    assertThat(user.source()).isEqualTo("~/.cassandra/cqlshrc");
    assertThat(user.options()).extracting(Option::value).containsExactly("app_reader");
    assertThat(s.toString()).doesNotContain("cnry-cqlsh-password-9");
  }

  @Test
  void typedFieldsAndAMissingFileGetNothing() {
    assertThat(local(draft(c -> c.withArray("contactPoints").add("10.0.4.12:9042")), true, dir.resolve("none"))
        .isEmpty()).isTrue();
    assertThat(local(draft(null), false, dir.resolve("none")).isEmpty()).isTrue();
    assertThat(CassandraSuggestions.cqlsh(dir.resolve("none"))).isNull();
  }

  @Test
  void newConnectionsDefaultToAutoAndTheDataCenterIsAskedForWhileEmpty() {
    JsonNode schema = new CassandraConnector().manifest().configSchema();
    JsonNode tls = schema.at("/properties/tls");

    assertThat(tls.path("default").asString()).isEqualTo("auto");
    assertThat(tls.at("/x-env-default/prod").asBoolean()).isTrue();
    assertThat(tls.path("enum").toString()).isEqualTo("[\"auto\",true,false]");
    assertThat(schema.path("required").toString()).contains("\"localDataCenter\"");
    assertThat(schema.at("/properties/localDataCenter/x-suggest").asBoolean()).isTrue();
  }

  @Test
  void onlyADraftMayLeaveTheDataCenterEmptyAndItsSessionInfersIt() throws Exception {
    ConnectionConfig cfg = draft(c -> c.withArray("contactPoints").add("127.0.0.1:9042"));

    assertThatThrownBy(() -> CassandraSettings.of(cfg)).isInstanceOf(ConnectorException.class)
        .hasMessageStartingWith("Enter the local data center");
    CassandraSettings draft = CassandraSettings.draft(cfg);
    assertThat(draft.localDataCenter()).isNull();
    OptionsMap o = CassandraSessions.options("draft-1", draft);
    assertThat(o.get(TypedDriverOption.LOAD_BALANCING_POLICY_CLASS))
        .isEqualTo(DcInferringLoadBalancingPolicy.class.getName());
    assertThat(o.get(TypedDriverOption.LOAD_BALANCING_LOCAL_DATACENTER)).isNull();
    // A saved connection keeps the driver's policy with its data center.
    OptionsMap saved = CassandraSessions.options("conn-1", CassandraSettings.of(draft(c -> {
      c.withArray("contactPoints").add("127.0.0.1:9042");
      c.put("localDataCenter", "dc1");
    })));
    assertThat(saved.get(TypedDriverOption.LOAD_BALANCING_LOCAL_DATACENTER)).isEqualTo("dc1");
    assertThat(saved.get(TypedDriverOption.LOAD_BALANCING_POLICY_CLASS)).isEqualTo("DefaultLoadBalancingPolicy");
  }

  @Test
  void anEmptyDataCenterTakesTheContactPointsOneAndKeyspacesAreOffered() throws Exception {
    CassandraSettings chosen = CassandraSettings.draft(draft(c -> {
      c.withArray("contactPoints").add("127.0.0.1:9042");
      c.put("tls", "auto");
      c.withArray("keyspaces").add("shop*");
    }));
    CassandraSuggestions.Facts facts = new CassandraSuggestions.Facts("Shop Cluster", Map.of("eu-1", 3, "us-1", 2),
        "eu-1", List.of("orders", "shop", "shop_archive"), null);
    Suggestions.Builder out = Suggestions.builder();

    CassandraSuggestions.apply(facts, chosen, chosen.withTls(true), "gw_reader", out);

    Suggestions s = out.build();
    Suggestions.Field dc = s.fields().get("/localDataCenter");
    assertThat(dc.value()).isEqualTo("eu-1");
    assertThat(dc.source()).isEqualTo("the cluster");
    assertThat(dc.options()).extracting(Option::value).containsExactlyInAnyOrder("eu-1", "us-1");
    assertThat(s.fields().get("/keyspaces").options()).extracting(Option::value)
        .containsExactly("orders", "shop", "shop_archive");
    // Keyspace offers only what the connection's list lets agents use.
    assertThat(s.fields().get("/keyspace").options()).extracting(Option::value).containsExactly("shop",
        "shop_archive");
    assertThat(s.notes()).containsExactly("The nodes speak TLS: Auto connects with TLS.",
        "Signed in as gw_reader to Shop Cluster: data center eu-1 (3 nodes). 3 keyspaces: choose the ones agents may "
            + "use.");
  }

  @Test
  void aTypedDataCenterTheClusterLacksIsANoteAndNothingIsFilledIn() throws Exception {
    CassandraSettings chosen = CassandraSettings.draft(draft(c -> {
      c.withArray("contactPoints").add("127.0.0.1:9042");
      c.put("localDataCenter", "datacenter1");
      c.put("tls", false);
    }));
    CassandraSuggestions.Facts facts = new CassandraSuggestions.Facts(null, Map.of("dc1", 1), "datacenter1", null,
        "No node of the local data center is available.");
    Suggestions.Builder out = Suggestions.builder();

    CassandraSuggestions.apply(facts, chosen, chosen, null, out);

    Suggestions s = out.build();
    assertThat(s.fields().get("/localDataCenter").value()).isNull();
    assertThat(s.fields().get("/localDataCenter").options()).extracting(Option::value).containsExactly("dc1");
    assertThat(s.fields()).doesNotContainKeys("/keyspace", "/keyspaces");
    assertThat(s.notes()).containsExactly("The cluster has no data center datacenter1: its data centers are dc1.",
        "Connected without signing in. The keyspaces weren't listed: No node of the local data center is available.");
  }

  @Test
  void serviceSuggestionsStopAtSettingsTheConnectorCantUse() {
    Suggestions s = CassandraSuggestions.suggest(draft(c -> c.withArray("contactPoints").add("not a host!")),
        new TestSecrets(), TestCtx.of(), Suggestions.Scope.SERVICE, port -> false, dir.resolve("none"));

    assertThat(s.notes()).singleElement().asString().startsWith("not a host! isn't host:port");
    assertThat(s.fields()).isEmpty();
  }
}
