package ai.msrv.gaterook.connectors.support.egress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import mockwebserver3.SocketEffect;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The guard end to end against MockWebServer on 127.0.0.1 (report 10 §8.2): names resolve through a fake resolver,
 * and the test policy allows only {@code 127.0.0.1/32}.
 */
class EgressClientsTest {

  private static final InetAddress LOOPBACK = ip("127.0.0.1");

  private MockWebServer server;
  private Map<String, Deque<List<InetAddress>>> names;
  private EgressClients clients;
  private final AddressPolicy onlyServer = AddressPolicy.builder().allowPrivate("127.0.0.1/32").build();

  @BeforeEach
  void start() throws IOException {
    server = new MockWebServer();
    server.start(LOOPBACK, 0);
    names = new java.util.HashMap<>();
    answer("api.test", LOOPBACK);
    clients = new EgressClients(new EgressClients.Settings("GateRook/test", Set.of(7465, 7466), this::resolve));
  }

  @AfterEach
  void stop() {
    clients.close();
    server.close();
  }

  @Test
  void sendsAnAllowedRequestAndReadsTheAnswer() throws Exception {
    server.enqueue(new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
        .addHeader("Set-Cookie", "session=abc").body("{\"ok\":true}").build());

    ExecContext.EgressResponse response = send(EgressSpec.of("conn-1", onlyServer), "GET", url("api.test", "/v1/x"),
        null, 1024);

    assertThat(response.status()).isEqualTo(200);
    assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("{\"ok\":true}");
    assertThat(response.truncated()).isFalse();
    assertThat(response.headers()).containsKey("content-type").doesNotContainKey("set-cookie");
    RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
    assertThat(request.getHeaders().get("User-Agent")).isEqualTo("GateRook/test");
    assertThat(request.getHeaders().get("Cookie")).isNull();
  }

  @Test
  void refusesANameThatResolvesToAnotherPrivateAddress() {
    answer("evil.test", ip("127.0.0.2"));

    assertDenied(() -> send(EgressSpec.of("conn-1", onlyServer), "GET", url("evil.test", "/"), null, 1024),
        "evil.test resolves to 127.0.0.2, a private address");
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void refusesANameWithOneBadAddressAmongGoodOnes() {
    answer("mixed.test", LOOPBACK, ip("10.1.2.3"));

    assertDenied(() -> send(EgressSpec.of("conn-1", onlyServer), "GET", url("mixed.test", "/"), null, 1024),
        "10.1.2.3");
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void ipLiteralsNeverAskTheResolverAndTheSocketGuardStopsThem() {
    AddressPolicy publicOnly = AddressPolicy.publicOnly(List.of());
    names.clear(); // any resolution would fail the test with UnknownHostException instead of a denial

    assertDenied(() -> send(EgressSpec.of("conn-2", publicOnly), "GET",
        URI.create("http://127.0.0.1:" + server.getPort() + "/"), null, 1024), "127.0.0.1 is a private address");
    assertDenied(() -> send(EgressSpec.of("conn-2", publicOnly), "GET",
        URI.create("http://[::ffff:7f00:1]:" + server.getPort() + "/"), null, 1024), "127.0.0.1");
    assertDenied(() -> send(EgressSpec.of("conn-2", publicOnly), "GET",
        URI.create("http://169.254.169.254/latest/meta-data/"), null, 1024), "never connects there");
    assertDenied(() -> send(EgressSpec.of("conn-2", publicOnly), "GET",
        URI.create("http://2130706433:" + server.getPort() + "/"), null, 1024), "non-standard form");
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void theCoresOwnPortIsNeverReachable() {
    EgressClients withServerAsCore = new EgressClients(new EgressClients.Settings("GateRook/test",
        Set.of(server.getPort()), this::resolve));
    AddressPolicy everything = AddressPolicy.builder().allowPrivate("127.0.0.0/8").build();
    try {
      assertDenied(() -> withServerAsCore.send(EgressSpec.of("conn-3", everything),
          new ExecContext.EgressRequest("GET", url("api.test", "/"), Map.of(), null, Duration.ofSeconds(5)), 1024),
          "is GateRook itself");
    } finally {
      withServerAsCore.close();
    }
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void aRedirectIsReturnedAsDataAndNotFollowed() throws Exception {
    server.enqueue(new MockResponse.Builder().code(302).addHeader("Location", "http://169.254.169.254/latest/").build());

    ExecContext.EgressResponse response = send(EgressSpec.of("conn-1", onlyServer), "GET", url("api.test", "/go"),
        null, 1024);

    assertThat(response.status()).isEqualTo(302);
    assertThat(response.headers().get("location")).containsExactly("http://169.254.169.254/latest/");
    assertThat(server.getRequestCount()).isEqualTo(1);
  }

  @Test
  void aPostIsSentExactlyOnceWhenThePooledConnectionDrops() throws Exception {
    server.enqueue(new MockResponse.Builder().code(200).body("warm").build());
    server.enqueue(new MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection.INSTANCE).build());
    server.enqueue(new MockResponse.Builder().code(201).body("a second refund").build());
    EgressSpec spec = EgressSpec.of("conn-1", onlyServer);
    send(spec, "GET", url("api.test", "/warm"), null, 1024);

    assertThatThrownBy(() -> send(spec, "POST", url("api.test", "/v1/refunds"),
        "{\"amount\":100}".getBytes(StandardCharsets.UTF_8), 1024))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.OUTCOME_UNKNOWN);
          assertThat(e.getMessage()).contains("Check before retrying");
        });

    assertThat(server.getRequestCount()).isEqualTo(2);
    server.takeRequest(5, TimeUnit.SECONDS);
    RecordedRequest post = server.takeRequest(5, TimeUnit.SECONDS);
    assertThat(post.getMethod()).isEqualTo("POST");
    assertThat(post.getBody().utf8()).isEqualTo("{\"amount\":100}");
    assertThat(post.getHeaders().get("Content-Type")).isEqualTo("application/json");
  }

  /**
   * OkHttp repeats a request by itself when the server answers 503 with {@code Retry-After: 0}, whatever
   * {@code retryOnConnectionFailure} says (RetryAndFollowUpInterceptor 5.5.1): the gateway sends every request once and
   * hands the answer back instead.
   */
  @Test
  void aRequestIsSentOnceWhenTheServerAsksForItAgainAtOnce() throws Exception {
    EgressSpec spec = EgressSpec.of("conn-1", onlyServer);
    for (String method : new String[] {"POST", "DELETE", "GET"}) {
      server.enqueue(new MockResponse.Builder().code(503).addHeader("Retry-After", "0").body("busy").build());
      server.enqueue(new MockResponse.Builder().code(201).body("done twice").build());
      int before = server.getRequestCount();

      ExecContext.EgressResponse r = send(spec, method, url("api.test", "/v1/refunds"), method.equals("POST")
          ? "{\"amount\":100}".getBytes(StandardCharsets.UTF_8) : null, 1024);

      assertThat(r.status()).as(method).isEqualTo(503);
      assertThat(r.headers().get("retry-after")).as(method).containsExactly("0");
      Thread.sleep(200);
      assertThat(server.getRequestCount() - before).as(method + " sent once").isEqualTo(1);
      server.takeRequest(5, TimeUnit.SECONDS);
      // The unused answer of this round.
      send(spec, "GET", url("api.test", "/drain"), null, 1024);
      server.takeRequest(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void readsAtMostMaxBytesOfALargeBody() throws Exception {
    Buffer big = new Buffer();
    byte[] chunk = new byte[64 * 1024];
    for (int i = 0; i < 80; i++) {
      big.write(chunk);
    }
    server.enqueue(new MockResponse.Builder().code(200).body(big).build());

    ExecContext.EgressResponse response = send(EgressSpec.of("conn-1", onlyServer), "GET", url("api.test", "/big"),
        null, 256 * 1024);

    assertThat(response.truncated()).isTrue();
    assertThat(response.body()).hasSize(256 * 1024);
  }

  @Test
  void dnsRebindingIsCaughtOnTheNextConnection() throws Exception {
    server.enqueue(new MockResponse.Builder().code(200).body("first").build());
    answer("rebind.test", LOOPBACK);
    names.get("rebind.test").add(List.of(ip("10.1.2.3")));
    EgressSpec spec = EgressSpec.of("conn-4", onlyServer);

    assertThat(send(spec, "GET", url("rebind.test", "/"), null, 1024).status()).isEqualTo(200);
    clients.evict("conn-4");
    assertDenied(() -> send(spec, "GET", url("rebind.test", "/"), null, 1024), "10.1.2.3");
  }

  @Test
  void theSocketGuardChecksEveryConnectWhateverResolvedBefore() throws IOException {
    GuardSocketFactory guard = new GuardSocketFactory(AddressPolicy.publicOnly(List.of()));
    try (Socket socket = guard.createSocket()) {
      assertThatThrownBy(() -> socket.connect(new InetSocketAddress(LOOPBACK, server.getPort()), 1000))
          .isInstanceOf(ConnectDeniedException.class).hasMessageContaining("127.0.0.1 is a private address");
    }
    try (Socket socket = guard.createSocket()) {
      assertThatThrownBy(() -> socket.connect(InetSocketAddress.createUnresolved("api.test", 80), 1000))
          .isInstanceOf(ConnectDeniedException.class);
    }
    assertThatThrownBy(() -> guard.createSocket("api.test", 80)).isInstanceOf(ConnectDeniedException.class);
  }

  @Test
  void aRefusedConnectionIsUnavailableNotAnOutcomeQuestion() throws Exception {
    int closedPort;
    try (java.net.ServerSocket probe = new java.net.ServerSocket(0, 1, LOOPBACK)) {
      closedPort = probe.getLocalPort();
    }
    assertThatThrownBy(() -> send(EgressSpec.of("conn-5", onlyServer), "POST",
        URI.create("http://api.test:" + closedPort + "/x"), new byte[] {1}, 1024))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_UNAVAILABLE);
          assertThat(e.getMessage()).contains("connection refused");
        });
  }

  @Test
  void trustsAPrivateCaOnlyWhenTheConnectionSaysSo() throws Exception {
    HeldCertificate root = new HeldCertificate.Builder().commonName("Acme Test Root").certificateAuthority(0).build();
    HeldCertificate leaf = new HeldCertificate.Builder().commonName("api.test").addSubjectAlternativeName("api.test")
        .signedBy(root).build();
    HandshakeCertificates serverCerts = new HandshakeCertificates.Builder()
        .heldCertificate(leaf, root.certificate()).build();
    server.useHttps(serverCerts.sslSocketFactory());
    server.enqueue(new MockResponse.Builder().code(204).build());
    URI https = URI.create("https://api.test:" + server.getPort() + "/");

    assertThatThrownBy(() -> send(EgressSpec.of("tls-1", onlyServer), "GET", https, null, 1024))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_UNAVAILABLE);
          assertThat(e.getMessage()).contains("Trust the certificate");
        });

    EgressSpec trusting = EgressSpec.of("tls-2", onlyServer)
        .withTls(new EgressSpec.Tls(List.of(root.certificate()), false, List.of(), false));
    assertThat(send(trusting, "GET", https, null, 1024).status()).isEqualTo(204);
  }

  @Test
  void aRequestHttpCantCarryIsBadInputThatNeverQuotesAHeaderValue() {
    EgressSpec spec = EgressSpec.of("conn-bad", onlyServer);
    URI uri = url("api.test", "/v1/x");
    Map<String, List<String>> injected = Map.of("X-Agent", List.of("a\r\nInjected: 1"));
    Map<String, List<String>> token = Map.of("Authorization", List.of("Bearer cnry-egress-token\n"));

    assertBadInput(() -> clients.send(spec, new ExecContext.EgressRequest("GET", uri, injected, null,
        Duration.ofSeconds(5)), 1024), "X-Agent", "Injected");
    assertBadInput(() -> clients.send(spec, new ExecContext.EgressRequest("GET", uri, token, null,
        Duration.ofSeconds(5)), 1024), "Authorization", "cnry-egress-token");
    assertBadInput(() -> clients.send(spec, new ExecContext.EgressRequest("GET", uri,
        Map.of("X-Bad\nName", List.of("v")), null, Duration.ofSeconds(5)), 1024), "invalid name", "X-Bad");
    assertBadInput(() -> send(spec, "GET", uri, "{}".getBytes(StandardCharsets.UTF_8), 1024), "can't carry a body",
        "{}");
    assertBadInput(() -> send(spec, "GET /admin HTTP/1.1\r\nHost: x\r\n\r\nGET", uri, null, 1024),
        "Unsupported HTTP method", "admin");
    assertThat(server.getRequestCount()).as("nothing was sent").isZero();
  }

  // ── helpers ────────────────────────────────────────────────────────────────────────────────────────────────────

  private static void assertBadInput(Call call, String text, String neverQuoted) {
    assertThatThrownBy(call::run).isInstanceOfSatisfying(ConnectorException.class, e -> {
      assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT);
      assertThat(e.getMessage()).contains(text).doesNotContain(neverQuoted);
    });
  }

  private ExecContext.EgressResponse send(EgressSpec spec, String method, URI uri, byte[] body, long maxBytes)
      throws ConnectorException {
    Map<String, List<String>> headers = body == null ? Map.of() : Map.of("Content-Type", List.of("application/json"));
    return clients.send(spec, new ExecContext.EgressRequest(method, uri, headers, body, Duration.ofSeconds(5)),
        maxBytes);
  }

  private interface Call {
    void run() throws Exception;
  }

  private static void assertDenied(Call call, String text) {
    assertThatThrownBy(call::run).isInstanceOfSatisfying(ConnectorException.class, e -> {
      assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
      assertThat(e.getMessage()).contains(text);
    });
  }

  private URI url(String host, String path) {
    return URI.create("http://" + host + ":" + server.getPort() + path);
  }

  private void answer(String host, InetAddress... addresses) {
    names.computeIfAbsent(host, h -> new ArrayDeque<>()).add(List.of(addresses));
  }

  /** Answers in order; the last answer repeats. */
  private List<InetAddress> resolve(String host) throws UnknownHostException {
    Deque<List<InetAddress>> answers = names.get(host);
    if (answers == null || answers.isEmpty()) {
      throw new UnknownHostException(host);
    }
    return answers.size() > 1 ? answers.poll() : answers.peek();
  }

  private static InetAddress ip(String literal) {
    return AddressPolicy.canonicalIp(literal).orElseThrow();
  }
}
