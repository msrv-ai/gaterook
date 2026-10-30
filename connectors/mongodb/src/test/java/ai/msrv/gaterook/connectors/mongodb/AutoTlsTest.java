package ai.msrv.gaterook.connectors.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.Env;
import com.mongodb.MongoSocketException;
import java.io.EOFException;
import java.net.ServerSocket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

class AutoTlsTest {

  @Test
  void onlyTransportClosureAndNonTlsBytesPermitAnUnauthenticatedPlaintextProbe() {
    assertThat(AutoTls.mayBePlaintext(new EOFException())).isTrue();
    assertThat(AutoTls.mayBePlaintext(new SocketException("Connection reset"))).isTrue();
    assertThat(AutoTls.mayBePlaintext(new SSLException("Unsupported or unrecognized SSL message"))).isTrue();
    assertThat(AutoTls.mayBePlaintext(new SocketTimeoutException())).isFalse();
    assertThat(AutoTls.mayBePlaintext(new SocketException("Connection refused"))).isFalse();
    assertThat(AutoTls.mayBePlaintext(new SSLHandshakeException("Received fatal alert: handshake_failure"))).isFalse();
    assertThat(AutoTls.mayBePlaintext(new SSLHandshakeException("No subject alternative DNS name matching host"))).isFalse();
    SSLHandshakeException certificate = new SSLHandshakeException("Remote host terminated the handshake");
    certificate.initCause(new CertificateException("certificate rejected", new EOFException()));
    assertThat(AutoTls.mayBePlaintext(certificate)).isFalse();
  }

  @Test
  void aHandshakeTimeoutNeverStartsAPlaintextConnection() throws Exception {
    try (ServerSocket server = new ServerSocket(0)) {
      AtomicInteger connections = new AtomicInteger();
      CountDownLatch accepted = new CountDownLatch(1);
      Thread listener = Thread.ofVirtual().start(() -> {
        try {
          while (!server.isClosed()) {
            try (var socket = server.accept()) {
              connections.incrementAndGet();
              accepted.countDown();
              socket.getInputStream().readAllBytes();
            }
          }
        } catch (Exception ignored) {
          // The test closes the listener and the timed-out handshake socket.
        }
      });
      MongoSettings settings = MongoSettings.of(Fixtures.config(Env.DEV,
          "{\"connection\":{\"host\":\"127.0.0.1\",\"port\":" + server.getLocalPort() + ",\"tls\":\"auto\"}}"));
      long start = System.nanoTime();
      assertThatThrownBy(() -> AutoTls.resolve(settings, Duration.ofMillis(200)))
          .isInstanceOf(MongoSocketException.class).hasCauseInstanceOf(SocketTimeoutException.class);
      assertThat(accepted.await(1, TimeUnit.SECONDS)).isTrue();
      assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
      assertThat(connections.get()).isEqualTo(1);
      server.close();
      listener.join(1_000);
    }
  }
}
