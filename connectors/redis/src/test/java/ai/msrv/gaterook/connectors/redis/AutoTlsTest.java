package ai.msrv.gaterook.connectors.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.TestResult;
import java.io.EOFException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.time.Instant;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.exceptions.JedisConnectionException;

/**
 * Connection type Auto against stand-ins of the connector's own sockets: a plain server gets plain RESP only after a
 * PING without credentials answered, a server that speaks TLS gets TLS whatever its certificate, and a server that is
 * neither is an error. Nothing that signs in is sent before the transport is known.
 */
class AutoTlsTest {

  private static final String PASSWORD = "cnry-redis-auto-77";

  private final RedisConnector connector = new RedisConnector();

  @AfterEach
  void close() {
    connector.close();
  }

  private static RedisSettings auto(String host, int port, String caPem) throws Exception {
    return RedisSettings.parse(Configs.redis(host, port).auto(caPem).build());
  }

  private static Instant soon() {
    return Instant.now().plusSeconds(10);
  }

  @Test
  void onlyTransportClosureAndNonTlsBytesPermitAPlainPing() {
    assertThat(AutoTls.mayBePlaintext(new EOFException())).isTrue();
    assertThat(AutoTls.mayBePlaintext(new SocketException("Connection reset"))).isTrue();
    assertThat(AutoTls.mayBePlaintext(new SSLException("Unsupported or unrecognized SSL message"))).isTrue();
    assertThat(AutoTls.mayBePlaintext(new SocketTimeoutException())).isFalse();
    assertThat(AutoTls.mayBePlaintext(new SocketException("Connection refused"))).isFalse();
    assertThat(AutoTls.mayBePlaintext(new SSLHandshakeException("Received fatal alert: handshake_failure"))).isFalse();
    assertThat(AutoTls.mayBePlaintext(new SSLHandshakeException("No subject alternative DNS name matching x")))
        .isFalse();
    SSLHandshakeException certificate = new SSLHandshakeException("Remote host terminated the handshake");
    certificate.initCause(new CertificateException("certificate rejected", new EOFException()));
    assertThat(AutoTls.mayBePlaintext(certificate)).isFalse();
  }

  @Test
  void aPlainServerThatStaysSilentOnTheHelloGetsPlainRespAfterAPing() throws Exception {
    try (FakeRedis plain = FakeRedis.plain(PASSWORD)) {
      long started = System.nanoTime();
      RedisSettings resolved = AutoTls.resolve(auto("127.0.0.1", plain.port, null), soon());

      assertThat(resolved.tls()).isFalse();
      assertThat(resolved.autoTls()).isFalse();
      // The hello waited unanswered, then one PING without credentials: nothing else reached the server.
      assertThat(plain.commands).containsExactly("PING");
      assertThat(plain.connections).hasValue(2);
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }
  }

  @Test
  void aServerThatSpeaksTlsGetsTlsWithoutAPlainByte() throws Exception {
    try (FakeRedis tls = FakeRedis.tls(PASSWORD)) {
      RedisSettings resolved = AutoTls.resolve(auto("localhost", tls.port, tls.caPem()), soon());

      assertThat(resolved.tls()).isTrue();
      assertThat(resolved.autoTls()).isFalse();
      assertThat(tls.connections).hasValue(1);
      assertThat(tls.commands).isEmpty();
    }
  }

  @Test
  void anUntrustedCertificateIsTheAnswerNeverAReasonToTryPlainText() throws Exception {
    try (FakeRedis tls = FakeRedis.tls(PASSWORD)) {
      TestResult r = connector.test(Configs.redis("localhost", tls.port).auto(null).build(),
          TestSecrets.password(PASSWORD), TestCtx.of());

      assertThat(r.ok()).isFalse();
      assertThat(r.error().code()).isEqualTo("redis.tls");
      assertThat(r.error().message()).isEqualTo("TLS: the server certificate isn't trusted. Add its CA certificate.");
      assertThat(tls.connections).as("no second, plain connection").hasValue(1);
      assertThat(tls.plainPings).hasValue(0);
    }
  }

  @Test
  void aSlowTlsServerIsTriedAgainNotTakenForAPlainOne() throws Exception {
    // With a CA in the form the server must speak TLS: after the silence comes the handshake again, never a PING.
    try (FakeRedis slow = FakeRedis.slowTls(PASSWORD, 600)) {
      RedisSettings resolved = AutoTls.resolve(auto("localhost", slow.port, slow.caPem()), soon());
      assertThat(resolved.tls()).isTrue();
      assertThat(slow.connections).hasValue(2);
    }
    // Without one, the PING finds no plain Redis, and the second handshake's certificate is the answer.
    try (FakeRedis slow = FakeRedis.slowTls(PASSWORD, 600)) {
      assertThatThrownBy(() -> AutoTls.resolve(auto("localhost", slow.port, null), soon()))
          .isInstanceOf(JedisConnectionException.class).hasCauseInstanceOf(SSLHandshakeException.class);
      assertThat(slow.connections).hasValue(3);
      assertThat(slow.plainPings).hasValue(0);
    }
  }

  @Test
  void aServerThatAnswersNeitherIsAnError() throws Exception {
    try (FakeRedis web = FakeRedis.other(FakeRedis.Mode.HTTP)) {
      TestResult r = connector.test(Configs.redis("127.0.0.1", web.port).auto(null).build(),
          TestSecrets.password(PASSWORD), TestCtx.of());

      assertThat(r.ok()).isFalse();
      assertThat(r.error().code()).isEqualTo("redis.tls");
      assertThat(r.error().message()).isEqualTo("Auto: 127.0.0.1:" + web.port + " answered neither a TLS handshake "
          + "nor PING without TLS. Check the host and port.");
    }
  }

  @Test
  void aRedisThatRefusesEveryClientIsPlainAndItsRefusalIsShown() throws Exception {
    try (FakeRedis denied = FakeRedis.other(FakeRedis.Mode.DENIED)) {
      TestResult r = connector.test(Configs.redis("127.0.0.1", denied.port).auto(null).build(),
          TestSecrets.password(PASSWORD), TestCtx.of());

      assertThat(r.ok()).isFalse();
      assertThat(r.error().message()).startsWith("Redis answered: DENIED Redis is running in protected mode");
    }
  }

  @Test
  void testConnectionSaysWhichTransportAutoChose() throws Exception {
    try (FakeRedis plain = FakeRedis.plain(PASSWORD); FakeRedis tls = FakeRedis.tls(PASSWORD)) {
      TestResult p = connector.test(Configs.redis("127.0.0.1", plain.port).auto(null).env(Env.STAGE).build(),
          TestSecrets.password(PASSWORD), TestCtx.of());
      assertThat(p.ok()).as("%s %s", p.error(), plain.commands).isTrue();
      assertThat(p.checks().getFirst().detail()).isEqualTo("Without TLS (Auto) · 127.0.0.1:" + plain.port);
      assertThat(p.details().path("tls").asBoolean()).isFalse();
      assertThat(p.details().path("connectionType").asString()).isEqualTo("auto");
      assertThat(p.hints()).anyMatch(h -> h.code().equals("redis.tls-off") && h.text().equals("This server "
          + "doesn't speak TLS, so Auto connects without it: the password and the values travel in clear text."));
      // Signing in came only after the PING of the detection.
      assertThat(plain.commands.getFirst()).isEqualTo("PING");
      assertThat(plain.commands.get(1)).isEqualTo("HELLO 2 AUTH default ‹password›");

      TestResult t = connector.test(Configs.redis("localhost", tls.port).auto(tls.caPem()).build(),
          TestSecrets.password(PASSWORD), TestCtx.of());
      assertThat(t.ok()).as("%s", t.error()).isTrue();
      assertThat(t.checks().getFirst().detail()).isEqualTo("TLS (Auto) · localhost:" + tls.port);
      assertThat(t.details().path("tls").asBoolean()).isTrue();
      assertThat(tls.plainPings).hasValue(0);
    }
  }

  @Test
  void poolsDetectOncePerVersionOfTheConnection() throws Exception {
    try (FakeRedis plain = FakeRedis.plain(PASSWORD)) {
      ConnectionConfig cfg = Configs.redis("127.0.0.1", plain.port).auto(null).id("c-auto").build();
      for (int i = 0; i < 2; i++) {
        assertThat(connector.health(cfg, TestSecrets.password(PASSWORD), TestCtx.of()).state())
            .isEqualTo(HealthResult.State.HEALTHY);
      }
      assertThat(connector.clients().knownTls("c-auto")).isFalse();
      // The hello, the PING of the detection, then one pooled connection for both calls.
      assertThat(plain.connections).hasValue(3);

      connector.onConnectionChanged("c-auto");
      assertThat(connector.clients().knownTls("c-auto")).isNull();
      assertThat(connector.health(cfg, TestSecrets.password(PASSWORD), TestCtx.of()).state())
          .isEqualTo(HealthResult.State.HEALTHY);
      assertThat(plain.connections).hasValue(6);
    }
  }

  @Test
  void explicitChoicesAndStoredConnectionsNeverDetect() throws Exception {
    RedisSettings stored = RedisSettings.parse(Configs.redis("cache", 6379).build());
    assertThat(stored.autoTls()).isFalse();
    assertThat(AutoTls.resolve(stored, soon())).isSameAs(stored);
    assertThat(RedisSettings.parse(Configs.redis("cache", 6379).env(Env.PROD).build()).tls()).isTrue();
    // Auto is TLS until resolved, and no factory connects with it unresolved.
    RedisSettings auto = auto("cache", 6379, null);
    assertThat(auto.tls()).isTrue();
    assertThatThrownBy(() -> new GuardedSocketFactory(auto)).isInstanceOf(IllegalStateException.class);
  }
}
