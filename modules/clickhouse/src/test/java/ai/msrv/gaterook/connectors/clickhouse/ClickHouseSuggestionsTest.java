package ai.msrv.gaterook.connectors.clickhouse;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.SecretUnavailableException;
import ai.msrv.gaterook.connector.api.Suggestions;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;

/**
 * Help for the ClickHouse form: TLS follows the port the person typed; signed in, {@code /ping} (no credentials) says
 * whether the server speaks TLS on that port before the password goes anywhere, then who signed in.
 */
class ClickHouseSuggestionsTest {

  private final ClickHouseConnector connector = new ClickHouseConnector(TestCalls.definition());
  private final List<RecordedRequest> seen = new CopyOnWriteArrayList<>();
  private MockWebServer server;

  @TempDir
  Path dir;

  @AfterEach
  void stop() throws Exception {
    if (server != null) {
      server.close();
    }
  }

  private void start(SSLContext tls) throws Exception {
    server = new MockWebServer();
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        seen.add(request);
        if ("/ping".equals(request.getUrl().encodedPath())) {
          return new MockResponse.Builder().code(200).body("Ok.\n").build();
        }
        return new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
            .body("{\"meta\":[{\"name\":\"version()\",\"type\":\"String\"},{\"name\":\"currentUser()\","
                + "\"type\":\"String\"}],\"data\":[[\"24.8.4.13\",\"gw_reader\"]],\"rows\":1}").build();
      }
    });
    if (tls != null) {
      server.useHttps(tls.getSocketFactory());
    }
    server.start(InetAddress.getByName("127.0.0.1"), 0);
  }

  private static ConnectionConfig cfg(String host, Integer port, Boolean tls) {
    ConnectionConfig base = TestCalls.local(1);
    ObjectNode c = (ObjectNode) base.config().deepCopy();
    c.put("host", host);
    if (port == null) {
      c.remove("port");
    } else {
      c.put("port", port);
    }
    if (tls == null) {
      ((ObjectNode) c.get("options")).remove("tls");
    } else {
      ((ObjectNode) c.get("options")).put("tls", tls);
    }
    return new ConnectionConfig(base.id(), base.name(), base.type(), base.env(), base.version(), c,
        base.secretFields(), null);
  }

  private Suggestions suggest(ConnectionConfig cfg, Suggestions.Scope scope) {
    return connector.suggest(cfg, new TestCalls.Secrets(), new TestCalls.Context(false), scope);
  }

  // ── The form's own values ───────────────────────────────────────────────────────────────────────────────────

  @Test
  void tlsFollowsThePortTyped() {
    Suggestions http = suggest(cfg("clickhouse.acme.internal", 8123, null), Suggestions.Scope.LOCAL);
    assertThat(http.fields().get(ClickHouseSuggestions.TLS).value()).isEqualTo("false");

    Suggestions https = suggest(cfg("clickhouse.acme.internal", 8443, false), Suggestions.Scope.LOCAL);
    assertThat(https.fields().get(ClickHouseSuggestions.TLS).value()).isEqualTo("true");

    // A port and TLS that already go together: nothing to say.
    assertThat(suggest(cfg("clickhouse.acme.internal", null, null), Suggestions.Scope.LOCAL).isEmpty()).isTrue();
    assertThat(suggest(cfg("clickhouse.acme.internal", 8123, false), Suggestions.Scope.LOCAL).isEmpty()).isTrue();
  }

  @Test
  void aClickHouseCloudHostTakesItsHttpsPort() {
    Suggestions s = suggest(cfg("abc123.eu-central-1.aws.clickhouse.cloud", 8123, false), Suggestions.Scope.LOCAL);

    assertThat(s.fields().get(ClickHouseSuggestions.PORT).value()).isEqualTo("8443");
    assertThat(s.fields().get(ClickHouseSuggestions.TLS).value()).isEqualTo("true");
  }

  // ── Signed in ───────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void signedInItSaysWhoAndWhichVersionAfterAPingWithoutCredentials() throws Exception {
    start(null);

    Suggestions s = suggest(cfg("127.0.0.1", server.getPort(), false), Suggestions.Scope.SERVICE);

    assertThat(s.fields()).isEmpty();
    assertThat(s.notes()).containsExactly("Signed in to 127.0.0.1:" + server.getPort() + " as gw_reader "
        + "(ClickHouse 24.8.4.13). Load the databases and choose the ones agents may use.");
    assertThat(seen.getFirst().getUrl().encodedPath()).isEqualTo("/ping");
    assertThat(seen.getFirst().getHeaders().get("X-ClickHouse-Key")).isNull();
    assertThat(seen.getFirst().getHeaders().get("X-ClickHouse-User")).isNull();
    assertThat(seen.get(1).getHeaders().get("X-ClickHouse-Key")).isEqualTo(TestCalls.PASSWORD);
  }

  @Test
  void aServerAnsweringWithoutTlsTurnsTlsOffBeforeThePasswordGoesAnywhere() throws Exception {
    // An HTTP server as ClickHouse's: a ClientHello isn't a request line, so it answers 400 in plain text.
    List<String> received = new CopyOnWriteArrayList<>();
    try (java.net.ServerSocket plain = new java.net.ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))) {
      Thread.ofVirtual().start(() -> {
        while (!plain.isClosed()) {
          try (java.net.Socket s = plain.accept()) {
            byte[] head = new byte[512];
            int n = s.getInputStream().read(head);
            String text = n <= 0 ? "" : new String(head, 0, n, StandardCharsets.ISO_8859_1);
            received.add(text);
            String answer = text.startsWith("GET /ping ") ? "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n"
                + "Connection: close\r\n\r\nOk.\n" : "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n"
                + "Connection: close\r\n\r\n";
            s.getOutputStream().write(answer.getBytes(StandardCharsets.ISO_8859_1));
            s.getOutputStream().flush();
          } catch (java.io.IOException e) {
            // The listener closed.
          }
        }
      });

      Suggestions s = suggest(cfg("127.0.0.1", plain.getLocalPort(), true), Suggestions.Scope.SERVICE);

      assertThat(s.fields().get(ClickHouseSuggestions.TLS).value()).isEqualTo("false");
      assertThat(s.notes()).singleElement().satisfies(n -> assertThat(n).startsWith("ClickHouse answers without "
          + "TLS on port " + plain.getLocalPort() + ": turn TLS off."));
      // A TLS hello, then the plain ping: neither carries the user or the password.
      assertThat(received).hasSize(2);
      assertThat(received.get(1)).startsWith("GET /ping ");
      assertThat(received).noneMatch(r -> r.toLowerCase(java.util.Locale.ROOT).contains("x-clickhouse"));
    }
  }

  @Test
  void aTlsAttemptThatGetsNoAnswerIsNeverRetriedWithoutTls() throws Exception {
    List<java.net.Socket> accepted = new CopyOnWriteArrayList<>();
    try (java.net.ServerSocket silent = new java.net.ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))) {
      Thread.ofVirtual().start(() -> {
        while (!silent.isClosed()) {
          try {
            accepted.add(silent.accept());
          } catch (java.io.IOException e) {
            // The listener closed.
          }
        }
      });

      Suggestions s = suggest(cfg("127.0.0.1", silent.getLocalPort(), true), Suggestions.Scope.SERVICE);

      assertThat(s.fields()).isEmpty();
      assertThat(s.notes()).hasSize(1);
      // Only the TLS attempt: a timeout is never a reason to ask again without TLS.
      assertThat(accepted).hasSize(1);
    } finally {
      for (java.net.Socket socket : accepted) {
        socket.close();
      }
    }
  }

  @Test
  void aServerSpeakingTlsTurnsTlsOnAndAnUntrustedCertificateIsOnlyANote() throws Exception {
    start(selfSigned());

    Suggestions plain = suggest(cfg("127.0.0.1", server.getPort(), false), Suggestions.Scope.SERVICE);
    assertThat(plain.fields().get(ClickHouseSuggestions.TLS).value()).isEqualTo("true");

    Suggestions verified = suggest(cfg("127.0.0.1", server.getPort(), true), Suggestions.Scope.SERVICE);
    // This computer doesn't trust the certificate: no suggestion to go without TLS, and no password sent.
    assertThat(verified.fields()).isEmpty();
    assertThat(verified.notes()).containsExactly("ClickHouse at 127.0.0.1:" + server.getPort() + " speaks TLS, but "
        + "this computer doesn't trust its certificate.");
    assertThat(seen).noneMatch(r -> r.getHeaders().get("X-ClickHouse-Key") != null);
  }

  @Test
  void onlyATransportFailureOfATlsAttemptIsAskedAgainWithoutTls() {
    assertThat(ClickHouseSuggestions.mayBePlaintext(new javax.net.ssl.SSLException(
        "Unsupported or unrecognized SSL message"))).isTrue();
    javax.net.ssl.SSLHandshakeException ended = new javax.net.ssl.SSLHandshakeException(
        "Remote host terminated the handshake");
    ended.initCause(new java.io.EOFException());
    assertThat(ClickHouseSuggestions.mayBePlaintext(ended)).isTrue();
    assertThat(ClickHouseSuggestions.mayBePlaintext(new javax.net.ssl.SSLHandshakeException(
        "PKIX path building failed"))).isFalse();
    assertThat(ClickHouseSuggestions.mayBePlaintext(new javax.net.ssl.SSLHandshakeException(
        "Received fatal alert: handshake_failure"))).isFalse();
    assertThat(ClickHouseSuggestions.mayBePlaintext(new java.net.SocketTimeoutException("Read timed out")))
        .isFalse();
    assertThat(ClickHouseSuggestions.mayBePlaintext(new java.net.ConnectException("Connection refused")))
        .isFalse();
  }

  @Test
  void anUnreachableServerIsANote() {
    Suggestions s = suggest(cfg("127.0.0.1", 1, false), Suggestions.Scope.SERVICE);

    assertThat(s.fields()).isEmpty();
    assertThat(s.notes()).singleElement().satisfies(n -> assertThat(n).contains("can't be reached"));
  }

  @Test
  void aUserWithoutAPasswordSendsNoKey() throws Exception {
    start(null);
    SecretResolver none = new SecretResolver() {
      @Override
      public Secret secret(String field) throws SecretUnavailableException {
        throw new SecretUnavailableException(field + " has no value", null);
      }

      @Override
      public Login login(String field) throws SecretUnavailableException {
        throw new SecretUnavailableException("no login", null);
      }

      @Override
      public boolean isSet(String field) {
        return false;
      }

      @Override
      public Meta meta(String field) {
        return null;
      }

      @Override
      public SshCertificate sshCertificate(String field, java.security.PublicKey key, SshCertRequest request)
          throws SecretUnavailableException {
        throw new SecretUnavailableException("no Vault", null);
      }
    };

    connector.suggest(cfg("127.0.0.1", server.getPort(), false), none, new TestCalls.Context(false),
        Suggestions.Scope.SERVICE);

    assertThat(seen).hasSize(2);
    assertThat(seen.get(1).getHeaders().get("X-ClickHouse-User")).isEqualTo("gw_reader");
    assertThat(seen.get(1).getHeaders().get("X-ClickHouse-Key")).isNull();
  }

  /** A server certificate for localhost that this computer doesn't trust, made with the JDK's keytool. */
  private SSLContext selfSigned() throws Exception {
    Path store = dir.resolve("server.p12");
    List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "keytool")
        .toString(), "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-dname",
        "CN=localhost", "-ext", "SAN=dns:localhost", "-validity", "2", "-keystore", store.toString(), "-storetype",
        "PKCS12", "-storepass", "test-only-0001", "-keypass", "test-only-0001", "-noprompt"));
    Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0).as(out).isTrue();
    KeyStore keys = KeyStore.getInstance("PKCS12");
    try (InputStream in = Files.newInputStream(store)) {
      keys.load(in, "test-only-0001".toCharArray());
    }
    KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    kmf.init(keys, "test-only-0001".toCharArray());
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(kmf.getKeyManagers(), null, null);
    return context;
  }
}
