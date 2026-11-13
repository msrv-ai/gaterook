package com.gaterook.connectors.cassandra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.TestResult;
import java.io.EOFException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Connection type Auto against stand-ins of a node's native port: a plain node gets CQL without TLS only after
 * {@code OPTIONS} without credentials answered, a node that speaks TLS gets TLS whatever its certificate, and one that
 * is neither, or stays silent, is an error without a plain connection.
 */
class AutoTlsTest {

  private static final Duration BUDGET = Duration.ofSeconds(5);

  static ConnectionConfig cfg(String contactPoint, Consumer<ObjectNode> tweak) {
    ObjectNode c = JsonNodeFactory.instance.objectNode();
    c.putArray("contactPoints").add(contactPoint);
    c.put("localDataCenter", "dc1");
    c.put("tls", "auto");
    c.putObject("credentials").put("user", "gw_reader");
    if (tweak != null) {
      tweak.accept(c);
    }
    return new ConnectionConfig("conn-auto", "c", "cassandra", Env.DEV, 1, c, Set.of(CassandraSettings.PASSWORD),
        null);
  }

  private static CassandraSettings resolve(CassandraSettings s) throws ConnectorException {
    return AutoTls.resolve(s, CassandraSessions.contactPoints(s), BUDGET);
  }

  @Test
  void onlyTransportClosureAndNonTlsBytesPermitAPlainOptions() {
    assertThat(AutoTls.mayBePlaintext(new EOFException())).isTrue();
    assertThat(AutoTls.mayBePlaintext(new SocketException("Connection reset"))).isTrue();
    assertThat(AutoTls.mayBePlaintext(new SSLException("Unsupported or unrecognized SSL message"))).isTrue();
    assertThat(AutoTls.mayBePlaintext(new SocketTimeoutException())).isFalse();
    assertThat(AutoTls.mayBePlaintext(new SSLHandshakeException("Received fatal alert: handshake_failure"))).isFalse();
    SSLHandshakeException certificate = new SSLHandshakeException("Remote host terminated the handshake");
    certificate.initCause(new CertificateException("certificate rejected", new EOFException()));
    assertThat(AutoTls.mayBePlaintext(certificate)).isFalse();
  }

  @Test
  void aPlainNodeGetsCqlWithoutTlsAfterOptionsAnswered() throws Exception {
    try (FakeCqlNode node = new FakeCqlNode(FakeCqlNode.Mode.PLAIN)) {
      CassandraSettings resolved = resolve(CassandraSettings.of(cfg(node.contactPoint("127.0.0.1"), null)));

      assertThat(resolved.tls()).isFalse();
      assertThat(resolved.autoTls()).isFalse();
      assertThat(node.options).hasValue(1);
      assertThat(node.connections).hasValue(2);
    }
  }

  @Test
  void aNodeThatSpeaksTlsGetsTlsWithoutAPlainFrame() throws Exception {
    try (FakeCqlNode node = new FakeCqlNode(FakeCqlNode.Mode.TLS)) {
      CassandraSettings resolved = resolve(CassandraSettings.of(cfg(node.contactPoint("localhost"),
          c -> c.put("caCertificate", node.caPem()))));

      assertThat(resolved.tls()).isTrue();
      assertThat(resolved.autoTls()).isFalse();
      assertThat(node.connections).hasValue(1);
    }
  }

  @Test
  void anUntrustedCertificateIsTheAnswerNeverAReasonToTryPlainText() throws Exception {
    try (FakeCqlNode node = new FakeCqlNode(FakeCqlNode.Mode.TLS)) {
      TestResult r = new CassandraConnector().test(cfg(node.contactPoint("localhost"), null),
          TestSecrets.password("cnry-cassandra-auto-5"), TestCtx.of());

      assertThat(r.ok()).isFalse();
      assertThat(r.error().code()).isEqualTo("cassandra.tls");
      assertThat(r.error().message()).startsWith("TLS to localhost:" + node.port + " failed: ")
          .endsWith("Check the CA certificate and the host name.");
      assertThat(node.connections).as("no second, plain connection").hasValue(1);
    }
  }

  @Test
  void aCaInTheFormNeedsTls() throws Exception {
    try (FakeCqlNode node = new FakeCqlNode(FakeCqlNode.Mode.PLAIN); FakeCqlNode other = new FakeCqlNode(
        FakeCqlNode.Mode.TLS)) {
      CassandraSettings s = CassandraSettings.of(cfg(node.contactPoint("127.0.0.1"),
          c -> c.put("caCertificate", other.caPem())));

      assertThatThrownBy(() -> resolve(s)).isInstanceOf(ConnectorException.class).hasMessageStartingWith("TLS to ");
      assertThat(node.options).hasValue(0);
    }
  }

  @Test
  void aSilentNodeOrOneThatIsNeitherIsAnErrorWithoutPlainText() throws Exception {
    try (FakeCqlNode silent = new FakeCqlNode(FakeCqlNode.Mode.SILENT)) {
      CassandraSettings s = CassandraSettings.of(cfg(silent.contactPoint("127.0.0.1"), null));
      assertThatThrownBy(() -> AutoTls.resolve(s, CassandraSessions.contactPoints(s), Duration.ofMillis(600)))
          .isInstanceOf(ConnectorException.class).hasMessage("Auto: 127.0.0.1:" + silent.port + " didn't finish a "
              + "TLS handshake in time. If the nodes don't speak TLS, choose Without TLS under Connection type.");
      assertThat(silent.connections).hasValue(1);
    }
    try (FakeCqlNode web = new FakeCqlNode(FakeCqlNode.Mode.HTTP)) {
      CassandraSettings s = CassandraSettings.of(cfg(web.contactPoint("127.0.0.1"), null));
      assertThatThrownBy(() -> resolve(s)).isInstanceOf(ConnectorException.class).hasMessage("Auto: 127.0.0.1:"
          + web.port + " answered neither a TLS handshake nor CQL without TLS. Check the contact points.");
    }
  }

  @Test
  void aDeadContactPointLeavesTheNextOneTheRestOfTheTime() throws Exception {
    int closed;
    try (ServerSocket s = new ServerSocket(0)) {
      closed = s.getLocalPort();
    }
    try (FakeCqlNode node = new FakeCqlNode(FakeCqlNode.Mode.PLAIN)) {
      CassandraSettings s = CassandraSettings.of(cfg("127.0.0.1:" + closed, c -> c.withArray("contactPoints")
          .add(node.contactPoint("127.0.0.1"))));
      assertThat(resolve(s).tls()).isFalse();
    }
    CassandraSettings none = CassandraSettings.of(cfg("127.0.0.1:" + closed, null));
    assertThatThrownBy(() -> AutoTls.resolve(none, List.of(new InetSocketAddress("127.0.0.1", closed)), BUDGET))
        .hasMessage("No contact point answered (127.0.0.1:" + closed + ": connection refused). Check the contact "
            + "points and the network.");
  }

  @Test
  void explicitChoicesAndStoredConnectionsNeverDetect() throws Exception {
    CassandraSettings stored = CassandraSettings.of(cfg("db.test:9042", c -> c.remove("tls")));
    assertThat(stored.autoTls()).isFalse();
    assertThat(stored.tls()).as("the old default").isTrue();
    assertThat(AutoTls.resolve(stored, List.of(), BUDGET)).isSameAs(stored);
    assertThat(CassandraSettings.of(cfg("db.test:9042", c -> c.put("tls", false))).tls()).isFalse();
    CassandraSettings auto = CassandraSettings.of(cfg("db.test:9042", null));
    assertThat(auto.autoTls()).isTrue();
    assertThat(auto.tls()).isTrue();
    assertThatThrownBy(() -> CassandraSettings.of(cfg("db.test:9042", c -> c.put("tls", "AUTO"))))
        .hasMessage("Connection type is Auto, TLS or Without TLS.");
    // A session never opens with Auto unresolved.
    assertThat(CassandraSessions.open("x", auto, new CassandraSessions.Login(null, null, "none", null),
        List.of(new InetSocketAddress("127.0.0.1", 9042)))).isCompletedExceptionally();
  }
}
