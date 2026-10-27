package ai.msrv.gaterook.connectors.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.SecretUnavailableException;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.TestResult.Hint;
import ai.msrv.gaterook.connector.api.TestResult.Severity;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.security.PublicKey;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * Test connection, health, TLS, {@code mongodb+srv://} and the client cache against real mongods (report 05 §5):
 * one with access control, one without, one with TLS and a CA of its own; SRV and TXT come from a local DNS server.
 */
class MongoConnectionIT {

  static LocalMongod auth;
  static LocalMongod open;
  static LocalMongod tls;
  static DnsStub dns;
  static MongoConnector connector;

  @BeforeAll
  static void start() throws Exception {
    auth = LocalMongod.start(LocalMongod.defaultBinary(), true, false);
    open = LocalMongod.start(LocalMongod.defaultBinary(), false, false);
    tls = LocalMongod.start(LocalMongod.defaultBinary(), true, true);
    dns = new DnsStub();
    // The TLS server through SRV: rs.0.0.1 → 127.0.0.1:<port> (an IP target keeps the host name out of the system
    // resolver, and the certificate names 127.0.0.1); its TXT record sets the auth database as Atlas does.
    dns.srv("_mongodb._tcp.rs.0.0.1", 0, 0, tls.port, "127.0.0.1").txt("rs.0.0.1", "authSource=admin");
    connector = new MongoConnector(dns.address());
  }

  @AfterAll
  static void stop() {
    connector.close();
    for (AutoCloseable c : new AutoCloseable[] {auth, open, tls, dns}) {
      try {
        if (c != null) {
          c.close();
        }
      } catch (Exception ignored) {
        // Stopping anyway.
      }
    }
  }

  static ConnectionConfig cfg(LocalMongod m, Env env, String user, String connectionExtra, String extra) {
    String connection = "\"uri\":\"" + m.uri("orders") + "\"" + (connectionExtra == null ? "" : "," + connectionExtra);
    return Fixtures.config(env.id() + "-" + user + "-" + m.port, env, "{\"connection\":{" + connection + "}"
        + (user == null ? "" : ",\"credentials\":{\"user\":\"" + user + "\"}") + (extra == null ? "" : "," + extra)
        + "}", 1);
  }

  static TestResult test(ConnectionConfig cfg, String user) {
    return connector.test(cfg, user == null ? Fixtures.Secrets.none()
        : Fixtures.Secrets.password(LocalMongod.password(user)), Fixtures.Context.of(cfg.env()));
  }

  static List<String> codes(TestResult r) {
    return r.hints().stream().map(Hint::code).toList();
  }

  @Test
  void aReadOnlyUserIsReportedAsSuch() {
    TestResult r = test(cfg(auth, Env.DEV, "gw_ro", null, null), "gw_ro");

    assertThat(r.ok()).as(String.valueOf(r.error())).isTrue();
    assertThat(r.serverVersion()).isEqualTo("MongoDB " + auth.version);
    assertThat(r.identity()).isEqualTo("gw_ro@admin");
    assertThat(r.permissions().level()).isEqualTo(TestResult.Level.READ);
    assertThat(r.permissions().read()).startsWith("find on orders");
    assertThat(r.permissions().write()).isNull();
    assertThat(r.hints()).isEmpty();
    assertThat(r.checks()).extracting(TestResult.Check::name)
        .containsExactly("connect", "latency", "tls", "permissions", "allowlist");
    assertThat(r.metadataUpdate().path("serverVersion").asString())
        .isEqualTo("MongoDB " + MongoProbe.majorMinor(auth.version));
    assertThat(r.details().at("/server/topology").asString()).isEqualTo("standalone");
  }

  @Test
  void aUserThatCanWriteGetsTheKitsHintAndASnippet() {
    TestResult dev = test(cfg(auth, Env.DEV, "gw_rw", null, null), "gw_rw");
    TestResult prod = test(cfg(auth, Env.PROD, "gw_rw", "\"tls\":false", null), "gw_rw");

    assertThat(dev.permissions().level()).isEqualTo(TestResult.Level.WRITE);
    assertThat(dev.permissions().write()).contains("insert").contains("update").contains("remove")
        .contains("on orders").contains("readWrite@orders");
    Hint hint = dev.hints().getFirst();
    assertThat(hint.code()).isEqualTo("mongo.user-can-write");
    assertThat(hint.severity()).isEqualTo(Severity.INFO);
    assertThat(hint.field()).isEqualTo("/credentials/user");
    assertThat(dev.details().at("/snippets/mongo.user-can-write/text").asString())
        .contains("roles: [{ role: \"read\", db: \"orders\" }]");
    assertThat(codes(prod)).containsExactly("mongo.user-can-write", "mongo.tls-off");
    assertThat(prod.hints().getFirst().severity()).isEqualTo(Severity.WARNING);
  }

  @Test
  void anAdministratorIsDanger() {
    TestResult r = test(cfg(auth, Env.DEV, LocalMongod.ROOT, null, null), LocalMongod.ROOT);

    assertThat(r.permissions().level()).isEqualTo(TestResult.Level.ADMIN);
    assertThat(r.permissions().admin()).contains("root");
    assertThat(r.hints().getFirst().severity()).isEqualTo(Severity.DANGER);
    assertThat(r.hints().getFirst().code()).isEqualTo("mongo.user-is-admin");
  }

  @Test
  void aServerWithoutAccessControlIsSaidLoudly() {
    TestResult dev = test(cfg(open, Env.DEV, null, null, null), null);
    TestResult prod = test(cfg(open, Env.PROD, null, "\"tls\":false", null), null);

    assertThat(dev.ok()).isTrue();
    assertThat(dev.permissions().level()).isEqualTo(TestResult.Level.ADMIN);
    assertThat(dev.hints().getFirst().code()).isEqualTo("mongo.no-auth");
    assertThat(dev.hints().getFirst().severity()).isEqualTo(Severity.WARNING);
    assertThat(prod.hints().getFirst().severity()).isEqualTo(Severity.DANGER);
  }

  @Test
  void failuresNameTheirCauseAndNeverThePassword() {
    TestResult wrong = connector.test(cfg(auth, Env.DEV, "gw_ro", null, null),
        Fixtures.Secrets.password("not-the-password-0001"), Fixtures.Context.of(Env.DEV));
    TestResult host = test(Fixtures.config(Env.DEV, "{\"connection\":{\"uri\":\"mongodb://no-such-host.invalid"
        + ":27017/orders\"},\"advanced\":{\"serverSelectionTimeoutMs\":1000}}"), null);

    assertThat(wrong.error().code()).isEqualTo("mongo.auth");
    assertThat(wrong.error().message()).isEqualTo("127.0.0.1:" + auth.port + " refused the login: authentication "
        + "failed for gw_ro (auth database admin).");
    assertThat(wrong.error().technical()).doesNotContain("not-the-password-0001");
    assertThat(host.error().code()).isEqualTo("mongo.unknown-host");
    assertThat(host.error().message()).isEqualTo("no-such-host.invalid:27017 can't be found: can't resolve "
        + "no-such-host.invalid. Check the VPN or the host name.");
  }

  @Test
  void allowlistEntriesThatArentThereAreHinted() {
    TestResult r = test(cfg(auth, Env.DEV, "gw_ro", null, "\"advanced\":{\"namespaceAllowlist\":"
        + "[\"orders.orders\",\"orders.ordrs\",\"billing.*\"]}"), "gw_ro");

    assertThat(r.checks().getLast().ok()).isFalse();
    assertThat(r.checks().getLast().detail()).isEqualTo("Not found: orders.ordrs, billing.*");
    assertThat(r.hints()).extracting(Hint::code).containsExactly("mongo.allowlist-not-found");
  }

  @Test
  void tlsWithThePrivateCaAndWhatGoesWrongWithout() throws Exception {
    String ca = "\"tls\":true,\"caCertificate\":" + Fixtures.JSON.writeValueAsString(tls.caCertificate());
    TestResult good = test(cfg(tls, Env.PROD, "gw_ro", ca, null), "gw_ro");
    TestResult noCa = test(cfg(tls, Env.DEV, "gw_ro", "\"tls\":true", "\"advanced\":{\"serverSelectionTimeoutMs\""
        + ":1500}"), "gw_ro");

    assertThat(good.ok()).as(String.valueOf(good.error())).isTrue();
    assertThat(good.details().at("/tls/enabled").asBoolean()).isTrue();
    assertThat(codes(good)).doesNotContain("mongo.tls-off");
    assertThat(noCa.error().code()).isEqualTo("mongo.tls");
    assertThat(noCa.error().message()).endsWith("refused TLS: the server certificate isn't trusted. Add its CA "
        + "certificate.");
  }

  @Test
  void aNameTheCertificateDoesNotHaveFailsUnlessHostnamesAreAllowed() throws Exception {
    assumeTrue(tls.secondLoopback, "the server doesn't listen on 127.0.0.2 (macOS, Compose services)");
    String ca = "\"tls\":true,\"caCertificate\":" + Fixtures.JSON.writeValueAsString(tls.caCertificate());
    ConnectionConfig otherName = Fixtures.config(Env.DEV, "{\"connection\":{\"uri\":\"mongodb://127.0.0.2:"
        + tls.port + "/orders\"," + ca + "},\"credentials\":{\"user\":\"gw_ro\"},\"advanced\":"
        + "{\"serverSelectionTimeoutMs\":1500}}");
    TestResult mismatch = test(otherName, "gw_ro");
    ConnectionConfig skip = Fixtures.config(Env.DEV, "{\"connection\":{\"uri\":\"mongodb://127.0.0.2:" + tls.port
        + "/orders\"," + ca + ",\"allowInvalidHostnames\":true},\"credentials\":{\"user\":\"gw_ro\"}}");

    assertThat(mismatch.error().code()).isEqualTo("mongo.tls");
    assertThat(mismatch.error().message()).contains("the certificate doesn't match 127.0.0.2");
    assertThat(test(skip, "gw_ro").ok()).isTrue();
  }

  @Test
  void srvAndTxtComeFromTheGatewaysOwnDnsClient() throws Exception {
    String ca = "\"caCertificate\":" + Fixtures.JSON.writeValueAsString(tls.caCertificate());
    ConnectionConfig cfg = Fixtures.config(Env.DEV, "{\"connection\":{\"uri\":\"mongodb+srv://rs.0.0.1/orders\","
        + ca + "},\"credentials\":{\"user\":\"gw_ro\"}}");
    ConnectionConfig missing = Fixtures.config(Env.DEV, "{\"connection\":{\"uri\":\"mongodb+srv://none.0.0.1/"
        + "orders\"," + ca + "},\"credentials\":{\"user\":\"gw_ro\"},\"advanced\":{\"serverSelectionTimeoutMs\":"
        + "1500}}");

    TestResult r = test(cfg, "gw_ro");
    ToolResult found = connector.execute(Fixtures.call("mongo_find", "{\"collection\":\"orders\",\"filter\":"
        + "{\"_id\":3}}"), cfg, Fixtures.Secrets.password(LocalMongod.password("gw_ro")), Fixtures.Context.of(Env.DEV));
    TestResult none = test(missing, "gw_ro");

    assertThat(r.ok()).as(String.valueOf(r.error())).isTrue();
    assertThat(r.details().at("/tls/enabled").asBoolean()).as("srv turns TLS on").isTrue();
    assertThat(found.structuredContent().path("returned").asInt()).isEqualTo(1);
    assertThat(dns.udpQueries()).isGreaterThanOrEqualTo(2);
    assertThat(none.error().code()).isEqualTo("mongo.srv");
    assertThat(none.error().message()).startsWith("none.0.0.1 can't be found through DNS");
  }

  @Test
  void healthPingsThroughAnOpenClientOrAShortLivedOne() throws Exception {
    ConnectionConfig cfg = cfg(auth, Env.DEV, "gw_ro", null, null);
    Fixtures.Secrets secrets = Fixtures.Secrets.password(LocalMongod.password("gw_ro"));

    HealthResult cold = connector.health(cfg, secrets, Fixtures.Context.of(Env.DEV));
    connector.execute(Fixtures.call("mongo_find", "{\"collection\":\"orders\",\"limit\":1}"), cfg, secrets,
        Fixtures.Context.of(Env.DEV));
    HealthResult warm = connector.health(cfg, secrets, Fixtures.Context.of(Env.DEV));
    HealthResult down = connector.health(Fixtures.config(Env.DEV, "{\"connection\":{\"uri\":\"mongodb://127.0.0.1:1"
        + "/orders\"},\"advanced\":{\"serverSelectionTimeoutMs\":1000}}"), Fixtures.Secrets.none(),
        Fixtures.Context.of(Env.DEV));

    assertThat(cold.state()).isEqualTo(HealthResult.State.HEALTHY);
    assertThat(warm.state()).isEqualTo(HealthResult.State.HEALTHY);
    assertThat(down.state()).isEqualTo(HealthResult.State.UNREACHABLE);
    assertThat(down.message()).isEqualTo("127.0.0.1:1 refused the connection on port 1. Is mongod running?");
  }

  @Test
  void aClientBelongsToOneVersionAndOneLogin() throws Exception {
    try (MongoConnector c = new MongoConnector()) {
      ObjectNode config = Fixtures.json("{\"connection\":{\"uri\":\"" + auth.uri("orders") + "\"},"
          + "\"credentials\":{\"user\":\"gw_ro\"}}");
      String args = "{\"collection\":\"orders\",\"limit\":1}";
      ConnectionConfig v1 = new ConnectionConfig("cache", "cache", "mongodb", Env.DEV, 1, config,
          java.util.Set.of(MongoSettings.PASSWORD), null);
      ConnectionConfig v2 = new ConnectionConfig("cache", "cache", "mongodb", Env.DEV, 2, config,
          java.util.Set.of(MongoSettings.PASSWORD), null);
      Fixtures.Secrets pw = Fixtures.Secrets.password(LocalMongod.password("gw_ro"));

      c.execute(Fixtures.call("mongo_find", args), v1, pw, Fixtures.Context.of(Env.DEV));
      c.execute(Fixtures.call("mongo_find", args), v1, pw, Fixtures.Context.of(Env.DEV));
      assertThat(c.openClients()).isEqualTo(1);
      c.execute(Fixtures.call("mongo_find", args), v2, pw, Fixtures.Context.of(Env.DEV));
      assertThat(c.openClients()).isEqualTo(1);
      // A new value of the password (a new secret version) is a new login: the old client can't keep the old one.
      c.execute(Fixtures.call("mongo_find", args), v2, new Fixtures.Secrets(LocalMongod.password("gw_ro"), "v2"),
          Fixtures.Context.of(Env.DEV));
      assertThat(c.openClients()).isEqualTo(1);
      c.onConnectionRemoved("cache");
      assertThat(c.openClients()).isZero();
      // A wrong stored password is the server's refusal, as a connection problem the circuit breaker counts.
      ConnectorException wrong = org.assertj.core.api.Assertions.catchThrowableOfType(ConnectorException.class,
          () -> c.execute(Fixtures.call("mongo_find", args), v2, new Fixtures.Secrets("wrong-pw-0001", "v3"),
              Fixtures.Context.of(Env.DEV)));
      assertThat(wrong.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_UNAVAILABLE);
      assertThat(wrong.getMessage()).contains("refused the login").doesNotContain("wrong-pw-0001");
    }
  }

  /** Vault's database engine as the core hands it out: user and password of a lease, its version and end. */
  static SecretResolver dynamicLogin(String user, String version, Instant notAfter) {
    Fixtures.Secrets password = Fixtures.Secrets.password(LocalMongod.password(user));
    return new SecretResolver() {
      @Override
      public Secret secret(String field) throws SecretUnavailableException {
        throw new SecretUnavailableException("A dynamic login has no single value", null);
      }

      @Override
      public Login login(String field) throws SecretUnavailableException {
        return new Login(user, password.secret(field), version, notAfter);
      }

      @Override
      public boolean isSet(String field) {
        return MongoSettings.PASSWORD.equals(field);
      }

      @Override
      public Meta meta(String field) {
        return new Meta("dynamic", version, notAfter);
      }

      @Override
      public SshCertificate sshCertificate(String field, PublicKey key, SshCertRequest request)
          throws SecretUnavailableException {
        throw new SecretUnavailableException("No SSH CA here", null);
      }
    };
  }

  @Test
  void aDynamicLoginBringsItsOwnUserAndANewLeaseANewClient() throws Exception {
    try (MongoConnector c = new MongoConnector()) {
      // The form's user is a placeholder: with Vault's database engine the lease names the user.
      ConnectionConfig cfg = Fixtures.config("dynamic", Env.DEV, "{\"connection\":{\"uri\":\"" + auth.uri("orders")
          + "\"},\"credentials\":{\"user\":\"vault-issued\"}}", 1);
      String args = "{\"collection\":\"orders\",\"filter\":{\"_id\":2}}";
      Instant end = Instant.now().plusSeconds(600);

      TestResult tested = c.test(cfg, dynamicLogin("gw_ro", "lease-1", end), Fixtures.Context.of(Env.DEV));
      ToolResult first = c.execute(Fixtures.call("mongo_find", args), cfg, dynamicLogin("gw_ro", "lease-1", end),
          Fixtures.Context.of(Env.DEV));
      ToolResult renewed = c.execute(Fixtures.call("mongo_find", args), cfg, dynamicLogin("gw_ro", "lease-2", end),
          Fixtures.Context.of(Env.DEV));

      assertThat(tested.ok()).as(String.valueOf(tested.error())).isTrue();
      assertThat(tested.identity()).isEqualTo("gw_ro@admin");
      assertThat(first.structuredContent().path("returned").asInt()).isEqualTo(1);
      assertThat(renewed.structuredContent().path("returned").asInt()).isEqualTo(1);
      // The client of lease-1 was replaced and closed once its call was over.
      assertThat(c.openClients()).isEqualTo(1);
    }
  }
}
