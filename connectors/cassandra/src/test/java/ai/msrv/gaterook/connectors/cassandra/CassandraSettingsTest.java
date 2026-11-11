package ai.msrv.gaterook.connectors.cassandra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.TestResult.Hint;
import ai.msrv.gaterook.connector.api.TestResult.Level;
import ai.msrv.gaterook.connector.api.TestResult.Severity;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** The connection's settings, its keyspace rules, cursors, and what Test connection makes of a role's grants. */
class CassandraSettingsTest {

  /**
   * A test CA certificate (self-signed, CN=gw-test-ca, its key discarded when it was made): only parsed here, never
   * trusted by anything.
   */
  static final String CA = """
      -----BEGIN CERTIFICATE-----
      MIIBXjCCAQSgAwIBAgIURLqq0+MCb2GcQo+NflVWVlpEUiwwCgYIKoZIzj0EAwIw
      FTETMBEGA1UEAwwKZ3ctdGVzdC1jYTAeFw0yNjExMTExNDIxNDVaFw0zNjExMDgx
      NDIxNDVaMBUxEzARBgNVBAMMCmd3LXRlc3QtY2EwWTATBgcqhkjOPQIBBggqhkjO
      PQMBBwNCAARvdGaGCp4TbtfdX3PcUSQ20LQuFqhqHvwTJPoErnQUeZTFP5BeBV8b
      TLrxFYF3q2easaJ6NuO36RIhkjh+nl5QozIwMDAdBgNVHQ4EFgQUqxxH6K0Q8drs
      DTCpR7KWkAYxk6UwDwYDVR0TAQH/BAUwAwEB/zAKBggqhkjOPQQDAgNIADBFAiBS
      HcQmfX28IfX3qGOi/7EwPaPuuVjTBVP6W+BrqNs2VQIhAMUrapciLw68gXzBcZzE
      h2Znxd2x8Pg+Qz/qGgLmKvAT
      -----END CERTIFICATE-----
      """;

  static ConnectionConfig cfg(Env env, Consumer<ObjectNode> tweak) {
    ObjectNode c = JsonNodeFactory.instance.objectNode();
    c.putArray("contactPoints").add("cassandra-1.example.com");
    c.put("localDataCenter", "dc1");
    if (tweak != null) {
      tweak.accept(c);
    }
    return new ConnectionConfig("conn-1", "c", "cassandra", env, 1, c, Set.of(CassandraSettings.PASSWORD), null);
  }

  static CassandraSettings of(Consumer<ObjectNode> tweak) throws ConnectorException {
    return CassandraSettings.of(cfg(Env.DEV, tweak));
  }

  @Test
  void defaultsAreStrict() throws Exception {
    CassandraSettings s = of(null);
    assertThat(s.contactPoints()).containsExactly(new CassandraSettings.ContactPoint("cassandra-1.example.com",
        9042));
    assertThat(s.tls()).isTrue();
    assertThat(s.verifyHostname()).isTrue();
    assertThat(s.caCertificates()).isEmpty();
    assertThat(s.readConsistency()).isEqualTo("LOCAL_ONE");
    assertThat(s.allowFiltering()).isFalse();
    assertThat(s.keyspace()).isNull();
    assertThat(s.statementTimeout()).isNull();
    assertThat(s.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
    assertThat(s.connectionsPerNode()).isEqualTo(1);
  }

  @Test
  void contactPointsAreHostAndPort() throws Exception {
    assertThat(CassandraSettings.contactPoint("10.0.4.12:9142")).isEqualTo(new CassandraSettings.ContactPoint(
        "10.0.4.12", 9142));
    assertThat(CassandraSettings.contactPoint("[2001:db8::1]:9042").host()).isEqualTo("2001:db8::1");
    assertThat(CassandraSettings.contactPoint("[2001:db8::1]").toString()).isEqualTo("[2001:db8::1]:9042");
    assertThat(CassandraSettings.contactPoint(" node-1.example.com ").port()).isEqualTo(9042);
    for (String bad : new String[] {"", "::1", "host:0", "host:70000", "host:port", "ho st", "-host.example.com",
        "http://host:9042", "host:9042/ks"}) {
      assertThatThrownBy(() -> CassandraSettings.contactPoint(bad)).as(bad).isInstanceOf(ConnectorException.class);
    }
    assertThatThrownBy(() -> of(c -> {
      var a = c.putArray("contactPoints");
      for (int i = 0; i < 21; i++) {
        a.add("node-" + i + ".example.com");
      }
    })).hasMessageContaining("At most 20");
  }

  @Test
  void settingsThatCantWorkAreRefusedWithWhatToDo() {
    assertThatThrownBy(() -> of(c -> c.remove("localDataCenter"))).hasMessageContaining("local data center");
    assertThatThrownBy(() -> of(c -> c.put("keyspace", "shop-eu"))).hasMessageContaining("isn't a keyspace name");
    assertThatThrownBy(() -> of(c -> c.putArray("keyspaces").add("shop.*"))).hasMessageContaining("pattern");
    assertThatThrownBy(() -> of(c -> c.put("tlsVerify", "none"))).hasMessageContaining("full or ca");
    assertThatThrownBy(() -> of(c -> c.put("tlsVerify", "ca"))).hasMessageContaining("own CA certificate");
    assertThatThrownBy(() -> of(c -> c.put("caCertificate", "not a pem"))).hasMessageContaining("CA certificate");
    assertThatThrownBy(() -> of(c -> c.put("consistency", "ALL"))).hasMessageContaining("LOCAL_ONE or LOCAL_QUORUM");
    assertThatThrownBy(() -> of(c -> c.put("tlsServerName", "bad name"))).hasMessageContaining("host name");
  }

  @Test
  void aPrivateCaAllowsVerifyingTheCaOnly() throws Exception {
    CassandraSettings s = of(c -> c.put("tlsVerify", "ca").put("caCertificate", CA));
    assertThat(s.verifyHostname()).isFalse();
    assertThat(s.caCertificates()).hasSize(1);
    assertThat(of(c -> c.put("tls", false).put("tlsVerify", "ca")).tls()).isFalse();
  }

  @Test
  void allowFilteringOnlyEverAppliesOnDev() throws Exception {
    Consumer<ObjectNode> allow = c -> c.putObject("advanced").put("allowFiltering", true);
    assertThat(CassandraSettings.of(cfg(Env.DEV, allow)).allowFiltering()).isTrue();
    assertThat(CassandraSettings.of(cfg(Env.STAGE, allow)).allowFiltering()).isFalse();
    assertThat(CassandraSettings.of(cfg(Env.PROD, allow)).allowFiltering()).isFalse();
  }

  @Test
  void theKeyspaceListOpensCassandrasOwnKeyspacesOnlyByName() throws Exception {
    CassandraSettings open = of(null);
    assertThat(open.allows("shop")).isTrue();
    assertThat(open.allows("system")).isFalse();
    assertThat(open.allows("system_views")).isFalse();
    assertThat(open.allows("dse_perf")).isFalse();
    assertThat(open.allows("OpsCenter")).isFalse();
    CassandraSettings listed = of(c -> c.putArray("keyspaces").add("shop_*").add("*_audit").add("system_v*"));
    assertThat(listed.allows("shop_eu")).isTrue();
    assertThat(listed.allows("orders_audit")).isTrue();
    assertThat(listed.allows("shop")).isFalse();
    assertThat(listed.allows("system_views")).isTrue();
    assertThat(listed.allows("system_audit")).isFalse();
    CassandraSettings everything = of(c -> c.putArray("keyspaces").add("*").add("system_auth").add("dse_security"));
    assertThat(everything.allows("billing")).isTrue();
    assertThat(everything.allows("system_schema")).isFalse();
    assertThat(everything.allows("system_auth")).isFalse();
    assertThat(everything.allows("dse_security")).isFalse();
    assertThat(CassandraSettings.authTable("system", "roles")).isTrue();
    assertThat(CassandraSettings.authTable("system", "role_members")).isTrue();
    assertThat(CassandraSettings.authTable("system", "local")).isFalse();
    assertThat(of(c -> c.put("keyspace", "shop").putArray("keyspaces").add("billing")).defaultKeyspaceAllowed())
        .isFalse();
  }

  @Test
  void cursorsBelongToTheirConnectionAndRequest() throws Exception {
    TestCtx ctx = TestCtx.of();
    String hash = Cursors.hash(JsonNodeFactory.instance.objectNode().put("statement", "SELECT * FROM shop.t"));
    String cursor = Cursors.seal(ctx, CassandraTools.QUERY, "conn-1", hash, JsonNodeFactory.instance.objectNode()
        .put("k", 2));
    assertThat(cursor).startsWith(Cursors.PREFIX);
    assertThat(Cursors.purpose(CassandraTools.QUERY)).isEqualTo("cassandra.cassandra-query").matches(
        "[a-z][a-z0-9.-]{0,47}");
    assertThat(Cursors.open(ctx.next(), CassandraTools.QUERY, cursor, "conn-1", hash).path("k").asInt())
        .isEqualTo(2);
    assertThatThrownBy(() -> Cursors.open(ctx, CassandraTools.QUERY, cursor, "conn-2", hash))
        .hasMessageContaining("another connection or request");
    assertThatThrownBy(() -> Cursors.open(ctx, CassandraTools.QUERY, cursor, "conn-1", "other"))
        .hasMessageContaining("another connection or request");
    assertThatThrownBy(() -> Cursors.open(ctx, CassandraTools.QUERY, "dd1.x", "conn-1", hash))
        .hasMessageContaining("doesn't come from");
    assertThatThrownBy(() -> Cursors.open(ctx, CassandraTools.QUERY, Cursors.PREFIX + "x.y", "conn-1", hash))
        .hasMessageContaining("invalid");
  }

  // ── Test connection's reading of grants ─────────────────────────────────────────────────────────────────────

  private static CassandraProbe.Grants grants(boolean superuser, String... pairs) {
    List<String[]> list = new java.util.ArrayList<>();
    for (int i = 0; i < pairs.length; i += 2) {
      list.add(new String[] {pairs[i], pairs[i + 1]});
    }
    return new CassandraProbe.Grants(superuser, list, null);
  }

  @Test
  void grantsGiveTheRolesLevel() {
    assertThat(CassandraProbe.permissions(grants(false, "SELECT", "<keyspace shop>")).level()).isEqualTo(Level.READ);
    assertThat(CassandraProbe.permissions(grants(false, "SELECT", "<keyspace shop>", "EXECUTE",
        "<all functions in shop>")).level()).isEqualTo(Level.READ);
    TestResult.Permissions writer = CassandraProbe.permissions(grants(false, "SELECT", "<keyspace shop>", "MODIFY",
        "<keyspace shop>"));
    assertThat(writer.level()).isEqualTo(Level.WRITE);
    assertThat(writer.write()).isEqualTo("MODIFY on <keyspace shop>");
    assertThat(writer.read()).isEqualTo("SELECT on <keyspace shop>");
    assertThat(CassandraProbe.permissions(grants(false, "AUTHORIZE", "<keyspace shop>")).level())
        .isEqualTo(Level.ADMIN);
    assertThat(CassandraProbe.permissions(grants(false, "ALTER", "<all roles>")).level()).isEqualTo(Level.ADMIN);
    assertThat(CassandraProbe.permissions(grants(true)).level()).isEqualTo(Level.ADMIN);
    assertThat(CassandraProbe.permissions(grants(false)).read()).isEqualTo("No SELECT grants");
    assertThat(CassandraProbe.permissions(new CassandraProbe.Grants(false, List.of(), "LIST isn't supported"))
        .level()).isEqualTo(Level.UNKNOWN);
    assertThat(CassandraProbe.permissions(null).level()).isEqualTo(Level.UNKNOWN);
  }

  @Test
  void hintsAdviseARoleThatOnlyReads() throws Exception {
    CassandraSettings s = of(c -> c.put("keyspace", "shop").putArray("keyspaces").add("shop").add("billing")
        .add("audit_*"));
    String role = CassandraProbe.readerRole(s);
    assertThat(role).isEqualTo("CREATE ROLE gw_reader WITH PASSWORD = '…' AND LOGIN = true;\n"
        + "GRANT SELECT ON KEYSPACE shop TO gw_reader;\nGRANT SELECT ON KEYSPACE billing TO gw_reader;");
    assertThat(CassandraProbe.readerRole(of(null))).contains("GRANT SELECT ON KEYSPACE app TO gw_reader;");

    Map<String, Integer> one = Map.of("dc1", 3);
    List<Hint> superuser = CassandraProbe.hints(Env.PROD, s, "cassandra", grants(true),
        CassandraProbe.permissions(grants(true)), one, List.of("shop"));
    assertThat(superuser).anySatisfy(h -> {
      assertThat(h.code()).isEqualTo("cassandra.admin-role");
      assertThat(h.severity()).isEqualTo(Severity.DANGER);
      assertThat(h.text()).contains("superuser", role);
    });
    List<Hint> writer = CassandraProbe.hints(Env.STAGE, s, "gw", grants(false, "MODIFY", "<keyspace shop>"),
        CassandraProbe.permissions(grants(false, "MODIFY", "<keyspace shop>")), one, List.of("shop"));
    assertThat(writer).anySatisfy(h -> {
      assertThat(h.code()).isEqualTo("cassandra.write-role");
      assertThat(h.severity()).isEqualTo(Severity.WARNING);
    });
    List<Hint> reader = CassandraProbe.hints(Env.PROD, s, "gw", grants(false, "SELECT", "<keyspace shop>"),
        CassandraProbe.permissions(grants(false, "SELECT", "<keyspace shop>")), Map.of("dc1", 3, "dc2", 3),
        List.of("shop"));
    assertThat(reader).extracting(Hint::code).containsExactly("cassandra.read-only-boundary",
        "cassandra.other-data-centers");
    List<Hint> anonymous = CassandraProbe.hints(Env.PROD, of(c -> c.put("tls", false)), null, null,
        CassandraProbe.permissions(null), one, List.of());
    assertThat(anonymous).extracting(Hint::code).contains("cassandra.no-sign-in", "cassandra.no-tls",
        "cassandra.no-keyspaces");
    assertThat(anonymous).allSatisfy(h -> assertThat(h.severity()).isNotEqualTo(Severity.ERROR));
    List<Hint> unknown = CassandraProbe.hints(Env.DEV, of(c -> c.put("keyspace", "shop").putArray("keyspaces")
        .add("billing")), "gw", new CassandraProbe.Grants(false, List.of(), "Cassandra didn't list the role"),
        CassandraProbe.permissions(new CassandraProbe.Grants(false, List.of(), "x")), one, List.of("billing"));
    assertThat(unknown).extracting(Hint::code).contains("cassandra.unknown-permissions",
        "cassandra.keyspace-not-allowed");
    JsonNode details = CassandraProbe.permissions(grants(false, "SELECT", "<keyspace shop>")).details();
    assertThat(details.at("/grants/0/resource").asString()).isEqualTo("<keyspace shop>");
  }
}
