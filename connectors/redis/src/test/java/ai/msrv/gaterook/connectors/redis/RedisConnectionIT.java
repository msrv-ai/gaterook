package ai.msrv.gaterook.connectors.redis;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.TestResult.Hint;
import ai.msrv.gaterook.connector.api.TestResult.Level;
import ai.msrv.gaterook.connector.api.TestResult.Severity;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.resps.AccessControlLogEntry;

/**
 * Test connection against real servers (report 05 §5, §8): the four ACL users of {@link LocalRedis}, a replica, TLS
 * with full and CA-only verification, a cluster node, a closed port and a server that never answers. The probe must
 * tell each level apart without writing anything, and every failure must say what to do.
 */
class RedisConnectionIT {

  private static LocalRedis primary;
  private static LocalRedis replica;
  private static LocalRedis open;
  private static LocalRedis.Certificates certs;
  private final RedisConnector connector = new RedisConnector(Clock.systemUTC());

  @BeforeAll
  static void start() {
    certs = LocalRedis.Certificates.create(Path.of("target", "redis-it", "tls-" + ProcessHandle.current().pid())
        .toAbsolutePath());
    primary = LocalRedis.start("probe", LocalRedis.Options.of(LocalRedis.localBinary()).withTls(certs));
    replica = LocalRedis.start("replica", LocalRedis.Options.of(LocalRedis.localBinary()).withReplicaOf(primary));
    open = LocalRedis.start("open", LocalRedis.Options.of(LocalRedis.localBinary()).withOpenDefault());
    try (Jedis j = primary.admin()) {
      for (int i = 0; i < 20; i++) {
        j.set("cache:" + i, "v");
      }
      j.select(1);
      for (int i = 0; i < 20; i++) {
        j.set("other:" + i, "v");
      }
    }
  }

  @AfterAll
  static void stop() {
    for (LocalRedis r : new LocalRedis[] {replica, primary, open}) {
      if (r != null) {
        r.close();
      }
    }
  }

  @AfterEach
  void close() {
    connector.close();
  }

  private TestResult test(ConnectionConfig c, SecretResolver secrets) {
    TestResult r = connector.test(c, secrets, TestCtx.of());
    // Nothing of what the form holds as a secret ever reaches the result (§9: the agent never sees a secret).
    String all = TestCtx.JSON.writeValueAsString(r);
    for (String password : List.of(LocalRedis.DEFAULT_PASSWORD, "ro-pass", "rokeys-pass", "rw-pass", "nope-pass")) {
      assertThat(all).doesNotContain(password);
    }
    return r;
  }

  private static Configs.Cfg cfg(LocalRedis r, String user) {
    Configs.Cfg c = Configs.local(r);
    return user == null ? c : c.user(user);
  }

  private static List<String> codes(TestResult r) {
    return r.hints().stream().map(Hint::code).toList();
  }

  @Test
  void databaseDiscoveryUsesTheServerCountOrReportsAnIncompleteList() {
    TestResult full = connector.discoverDatabases(cfg(primary, null).build(),
        TestSecrets.password(LocalRedis.DEFAULT_PASSWORD), TestCtx.of());
    assertThat(full.ok()).isTrue();
    assertThat(full.details().path("discoveryComplete").asBoolean()).isTrue();
    assertThat(full.details().path("databases").size()).isEqualTo(16);
    assertThat(full.details().path("databases").get(15).asInt()).isEqualTo(15);
    TestResult partial = connector.discoverDatabases(cfg(primary, "ro").database(1).build(),
        TestSecrets.password("ro-pass"), TestCtx.of());
    assertThat(partial.ok()).isTrue();
    assertThat(partial.details().path("discoveryComplete").asBoolean()).isFalse();
    assertThat(partial.details().path("databases").toString()).isEqualTo("[1]");
    assertThat(codes(partial)).contains("redis.databases-incomplete");
  }

  private static Hint hint(TestResult r, String code) {
    return r.hints().stream().filter(h -> h.code().equals(code)).findFirst().orElseThrow(
        () -> new AssertionError("no hint " + code + " in " + r.hints()));
  }

  private static List<String> probeKeys(LocalRedis r) {
    try (Jedis j = r.admin()) {
      List<String> keys = new CopyOnWriteArrayList<>(j.keys(ReadOnlyCommandExecutor.PROBE_PREFIX + "*"));
      j.select(1);
      keys.addAll(j.keys(ReadOnlyCommandExecutor.PROBE_PREFIX + "*"));
      return keys;
    }
  }

  @Test
  void theDefaultUserIsAnAdministratorAndSaysSo() {
    TestResult r = test(cfg(primary, null).build(), TestSecrets.password(LocalRedis.DEFAULT_PASSWORD));
    assertThat(r.ok()).isTrue();
    assertThat(r.serverVersion()).isEqualTo("Redis 7.0.15");
    assertThat(r.identity()).isEqualTo("default");
    assertThat(r.permissions().level()).isEqualTo(Level.ADMIN);
    assertThat(r.permissions().write()).isEqualTo("can write");
    assertThat(r.permissions().admin()).containsExactly("CONFIG");
    assertThat(r.permissions().details().path("writeProbe").asString()).isEqualTo("ACL DRYRUN SET: allowed");
    assertThat(hint(r, "redis.admin").severity()).isEqualTo(Severity.DANGER);
    assertThat(hint(r, "redis.default-user").severity()).isEqualTo(Severity.WARNING);
    assertThat(hint(r, "redis.user-can-write").severity()).isEqualTo(Severity.INFO);
    assertThat(r.details().path("snippets").path("redis.user-can-write").path("text").asString())
        .isEqualTo("ACL SETUSER gw_reader on >CHANGE-ME ~* resetchannels -@all +@read +@connection -@dangerous "
            + "-dump -xread -lolwut");
    assertThat(r.metadataUpdate().path("serverVersion").asString()).isEqualTo("Redis 7");
    assertThat(r.metadataUpdate().path("mode").asString()).isEqualTo("standalone");
    assertThat(r.checks()).extracting(TestResult.Check::name).containsExactly("connect", "read");
    assertThat(r.checks().get(1).detail()).isEqualTo("20 keys in database 0");
    assertThat(r.details().path("keys").asLong()).isEqualTo(20);
    assertThat(probeKeys(primary)).isEmpty();
  }

  @Test
  void aReadOnlyUserIsReadAndItsRefusedProbeShowsInTheAclLog() {
    try (Jedis j = primary.admin()) {
      j.aclLogReset();
    }
    TestResult r = test(cfg(primary, "ro").build(), TestSecrets.password("ro-pass"));
    assertThat(r.ok()).isTrue();
    assertThat(r.identity()).isEqualTo("ro");
    assertThat(r.permissions().level()).isEqualTo(Level.READ);
    assertThat(r.permissions().read()).isEqualTo("reads");
    assertThat(r.permissions().write()).isEqualTo("can't write");
    assertThat(r.permissions().admin()).isEmpty();
    assertThat(r.permissions().details().path("writeProbe").asString()).isEqualTo("SET: NOPERM");
    assertThat(codes(r)).doesNotContain("redis.admin", "redis.user-can-write", "redis.default-user",
        "redis.key-patterns", "redis.no-password");
    assertThat(r.details().path("snippets").isEmpty()).isTrue();
    assertThat(r.details().path("probe").asString()).contains("ACL LOG");
    try (Jedis j = primary.admin()) {
      List<AccessControlLogEntry> log = j.aclLog();
      assertThat(log).anySatisfy(e -> {
        assertThat(e.getUsername()).isEqualTo("ro");
        assertThat(e.getObject()).isEqualTo("set");
      });
    }
    assertThat(probeKeys(primary)).isEmpty();
  }

  @Test
  void keyPatternsOfTheUserAreNoticed() {
    // Database 1 holds only other:* keys, which rokeys (~cache:*) may list but not read.
    TestResult r = test(cfg(primary, "rokeys").database(1).build(), TestSecrets.password("rokeys-pass"));
    assertThat(r.ok()).isTrue();
    assertThat(r.permissions().level()).isEqualTo(Level.READ);
    assertThat(r.permissions().read()).isEqualTo("reads, limited by key patterns");
    assertThat(hint(r, "redis.key-patterns").severity()).isEqualTo(Severity.INFO);
    assertThat(r.checks().get(1).detail()).isEqualTo("20 keys in database 1");
  }

  @Test
  void aUserThatCanWriteGetsTheReadOnlyLineToCreate() {
    TestResult r = test(cfg(primary, "rw").build(), TestSecrets.password("rw-pass"));
    assertThat(r.ok()).isTrue();
    assertThat(r.permissions().level()).isEqualTo(Level.WRITE);
    assertThat(r.permissions().details().path("writeProbe").asString())
        .isEqualTo("SET … NX XX: syntax error after the ACL check");
    assertThat(codes(r)).contains("redis.user-can-write").doesNotContain("redis.admin", "redis.default-user");
    assertThat(r.details().path("snippets").path("redis.user-can-write").path("language").asString())
        .isEqualTo("text");
    // On staging and prod writing is a warning, not a note.
    TestResult staging = test(cfg(primary, "rw").env(Env.STAGE).tls(false, null, null).build(),
        TestSecrets.password("rw-pass"));
    assertThat(hint(staging, "redis.user-can-write").severity()).isEqualTo(Severity.WARNING);
    assertThat(hint(staging, "redis.tls-off").severity()).isEqualTo(Severity.WARNING);
    assertThat(probeKeys(primary)).isEmpty();
  }

  @Test
  void aReplicaIsReadOnlyEvenForAUserThatCouldWrite() {
    TestResult r = test(cfg(replica, "rw").build(), TestSecrets.password("rw-pass"));
    assertThat(r.ok()).isTrue();
    assertThat(r.permissions().level()).isEqualTo(Level.READ);
    assertThat(r.permissions().details().path("writeProbe").asString()).isEqualTo("SET: READONLY (a replica)");
    assertThat(hint(r, "redis.replica").text()).isEqualTo("Read-only replica: good for agent reads.");
    assertThat(r.metadataUpdate().path("role").asString()).isIn("replica", "slave");
    assertThat(probeKeys(replica)).isEmpty();
  }

  @Test
  void aWrongPasswordNamesTheUserAndTheServer() {
    TestResult r = test(cfg(primary, "ro").build(), TestSecrets.password("nope-pass"));
    assertThat(r.ok()).isFalse();
    assertThat(r.error().code()).isEqualTo("redis.auth");
    assertThat(r.error().message()).isEqualTo("Wrong user name or password for ro on 127.0.0.1:" + primary.port + ".");
  }

  @Test
  void noPasswordForAServerThatWantsOneSaysWhereToSetIt() {
    TestResult r = test(cfg(primary, null).build(), new TestSecrets());
    assertThat(r.ok()).isFalse();
    assertThat(r.error().code()).isEqualTo("redis.noauth");
    assertThat(r.error().message()).isEqualTo("This server needs a password: set Password in Credentials.");
  }

  @Test
  void aServerWithoutAPasswordIsAWarningOnDevAndDangerOnProd() {
    TestResult dev = test(cfg(open, null).build(), new TestSecrets());
    assertThat(dev.ok()).isTrue();
    assertThat(hint(dev, "redis.no-password").severity()).isEqualTo(Severity.WARNING);
    assertThat(hint(dev, "redis.no-password").field()).isEqualTo("credentials.password");
    TestResult prod = test(cfg(open, null).env(Env.PROD).tls(false, null, null).build(), new TestSecrets());
    assertThat(prod.ok()).isTrue();
    assertThat(hint(prod, "redis.no-password").severity()).isEqualTo(Severity.DANGER);
    assertThat(codes(prod)).contains("redis.tls-off");
  }

  @Test
  void tlsWithFullVerificationNeedsTheNameOfTheCertificate() {
    TestResult byName = test(Configs.redis("localhost", primary.tlsPort).tls(true, "full", certs.caPem()).build(),
        TestSecrets.password(LocalRedis.DEFAULT_PASSWORD));
    assertThat(byName.ok()).as("%s", byName.error()).isTrue();
    assertThat(byName.checks().getFirst().detail()).isEqualTo("TLS · localhost:" + primary.tlsPort);
    assertThat(byName.details().path("tls").asBoolean()).isTrue();
    assertThat(codes(byName)).doesNotContain("redis.tls-off");

    TestResult byIp = test(Configs.redis("127.0.0.1", primary.tlsPort).tls(true, "full", certs.caPem()).build(),
        TestSecrets.password(LocalRedis.DEFAULT_PASSWORD));
    assertThat(byIp.ok()).isFalse();
    assertThat(byIp.error().code()).isEqualTo("redis.tls");
    assertThat(byIp.error().message()).isEqualTo("TLS: the certificate doesn't match 127.0.0.1.");

    TestResult caOnly = test(Configs.redis("127.0.0.1", primary.tlsPort).tls(true, "ca", certs.caPem()).build(),
        TestSecrets.password(LocalRedis.DEFAULT_PASSWORD));
    assertThat(caOnly.ok()).as("%s", caOnly.error()).isTrue();
  }

  @Test
  void tlsFailuresSayWhatToChange() {
    TestResult otherCa = test(Configs.redis("localhost", primary.tlsPort).tls(true, "full", certs.otherCaPem())
        .build(), TestSecrets.password(LocalRedis.DEFAULT_PASSWORD));
    assertThat(otherCa.error().code()).isEqualTo("redis.tls");
    assertThat(otherCa.error().message()).isEqualTo("TLS: the server certificate isn't trusted. Add its CA "
        + "certificate.");

    TestResult platformTrust = test(Configs.redis("localhost", primary.tlsPort).tls(true, "full", null).build(),
        TestSecrets.password(LocalRedis.DEFAULT_PASSWORD));
    assertThat(platformTrust.error().message()).isEqualTo("TLS: the server certificate isn't trusted. Add its CA "
        + "certificate.");

    // redis-server reads a ClientHello on its plain port as the start of a command and waits for the rest.
    TestResult tlsOnPlain = test(Configs.redis("localhost", primary.port).tls(true, "full", certs.caPem())
        .advanced("commandTimeoutMs", 500).build(), TestSecrets.password(LocalRedis.DEFAULT_PASSWORD));
    assertThat(tlsOnPlain.error().code()).isEqualTo("redis.tls");
    assertThat(tlsOnPlain.error().message()).isEqualTo("TLS: localhost:" + primary.port + " didn't answer the TLS "
        + "handshake within 0.5 s. If this port doesn't speak TLS, turn off Use TLS.");

    TestResult plainOnTls = test(Configs.redis("127.0.0.1", primary.tlsPort).build(),
        TestSecrets.password(LocalRedis.DEFAULT_PASSWORD));
    assertThat(plainOnTls.ok()).isFalse();
    assertThat(plainOnTls.error().code()).as("%s", plainOnTls.error()).isEqualTo("redis.closed");
    assertThat(plainOnTls.error().message()).endsWith("If this is a TLS port, turn on Use TLS.");
  }

  @Test
  void aClusterNodeIsRefusedWithWhatToDoInstead() {
    try (LocalRedis cluster = LocalRedis.start("cluster", LocalRedis.Options.of(LocalRedis.localBinary())
        .withCluster())) {
      TestResult r = test(cfg(cluster, null).build(), TestSecrets.password(LocalRedis.DEFAULT_PASSWORD));
      assertThat(r.ok()).isFalse();
      assertThat(r.error().code()).isEqualTo("redis.cluster");
      assertThat(r.error().message()).isEqualTo(RedisErrors.CLUSTER);
      assertThat(r.checks()).extracting(TestResult.Check::name).containsExactly("connect");
    }
  }

  @Test
  void aDatabaseThatDoesNotExistIsNamed() {
    TestResult r = test(cfg(primary, null).database(99).build(), TestSecrets.password(LocalRedis.DEFAULT_PASSWORD));
    assertThat(r.ok()).isFalse();
    assertThat(r.error().code()).isEqualTo("redis.database");
    assertThat(r.error().message()).isEqualTo("Database 99 doesn't exist on 127.0.0.1:" + primary.port + ".");
  }

  @Test
  void aClosedPortIsRefusedAtOnce() throws IOException {
    int port = LocalRedis.freePort();
    long t0 = System.nanoTime();
    TestResult r = test(Configs.redis("127.0.0.1", port).build(), TestSecrets.password("x"));
    assertThat(r.error().code()).isEqualTo("redis.refused");
    assertThat(r.error().message()).isEqualTo("Connection refused on port " + port + " of 127.0.0.1. Is "
        + "redis-server running there?");
    assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(2));
  }

  @Test
  void aServerThatNeverAnswersTimesOutWithinTheCommandTimeout() throws Exception {
    List<Socket> held = new CopyOnWriteArrayList<>();
    try (ServerSocket silent = new ServerSocket(0, 10, InetAddress.getLoopbackAddress())) {
      Thread.ofVirtual().start(() -> {
        try {
          while (true) {
            held.add(silent.accept());
          }
        } catch (IOException e) {
          // Closed at the end of the test.
        }
      });
      long t0 = System.nanoTime();
      TestResult r = test(Configs.redis("127.0.0.1", silent.getLocalPort()).advanced("commandTimeoutMs", 500)
          .build(), TestSecrets.password("x"));
      assertThat(r.error().code()).isEqualTo("redis.timeout");
      assertThat(r.error().message()).isEqualTo("127.0.0.1:" + silent.getLocalPort() + " didn't answer within "
          + "0.5 s. Check the VPN or the host.");
      assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(2));
    } finally {
      for (Socket s : held) {
        s.close();
      }
    }
  }

  @Test
  void healthIsAPingOnThePool() {
    ConnectionConfig c = cfg(primary, "ro").build();
    HealthResult h = connector.health(c, TestSecrets.password("ro-pass"), TestCtx.of());
    assertThat(h.state()).isEqualTo(HealthResult.State.HEALTHY);
    assertThat(connector.clients().current(c.id()).idle()).isEqualTo(1);
    HealthResult down = connector.health(Configs.redis("127.0.0.1", 1).build(), TestSecrets.password("x"),
        TestCtx.of());
    assertThat(down.state()).isEqualTo(HealthResult.State.UNREACHABLE);
  }

  @Test
  void aDynamicLoginLogsInAsItsOwnUser() {
    TestResult r = test(cfg(primary, "ignored-by-dynamic").build(), new TestSecrets().dynamic("ro", "ro-pass", "l1"));
    assertThat(r.ok()).as("%s", r.error()).isTrue();
    assertThat(r.identity()).isEqualTo("ro");
    assertThat(r.permissions().level()).isEqualTo(Level.READ);
  }
}
