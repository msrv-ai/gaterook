package ai.msrv.gaterook.connectors.http;

import static ai.msrv.gaterook.connectors.http.Fixtures.cfg;
import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connectors.support.egress.EgressClients;
import ai.msrv.gaterook.connectors.support.egress.GuardedEgress;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * Test connection (report 10 §5): trust on first use of a certificate before any credential leaves, the private
 * address that names its policy change, and the hints that read the answers with and without credentials.
 */
class HttpTestProbeTest {

  private static final HeldCertificate CA = new HeldCertificate.Builder().certificateAuthority(0)
      .commonName("Acme internal CA").build();
  private static final HeldCertificate LEAF = new HeldCertificate.Builder().commonName("api.test")
      .addSubjectAlternativeName("api.test").signedBy(CA).build();

  private MockWebServer server;
  private Fixtures.Names names;
  private EgressClients clients;
  private GuardedEgress guard;
  private final HttpConnector connector = new HttpConnector();

  @BeforeEach
  void start() throws IOException {
    server = new MockWebServer();
    server.useHttps(new HandshakeCertificates.Builder().heldCertificate(LEAF, CA.certificate()).build()
        .sslSocketFactory());
    server.start(Fixtures.ip("127.0.0.1"), 0);
    names = new Fixtures.Names().answer("api.test", "127.0.0.1");
    clients = Fixtures.clients(names, Set.of(7465));
    guard = Fixtures.guard(clients, "conn-1", "127.0.0.1/32");
  }

  @AfterEach
  void stop() {
    clients.close();
    server.close();
  }

  private String base() {
    return "https://api.test:" + server.getPort() + "/v1";
  }

  private ConnectionConfig trusted(String auth, String extra) {
    return cfg("billing-api", Env.DEV, "{\"baseUrls\":[\"" + base() + "\"],\"auth\":" + auth + ",\"tls\":{"
        + "\"caCertificates\":" + Fixtures.JSON.writeValueAsString(CA.certificatePem()) + extra + "}}");
  }

  /** Answers 401 without credentials and 200 with the right token, as most APIs do. */
  private void answerByToken(String token) {
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        String auth = request.getHeaders().get("Authorization");
        if (auth == null) {
          return new MockResponse.Builder().code(401).build();
        }
        return auth.equals("Bearer " + token) ? new MockResponse.Builder().code(200).addHeader("Server", "acme/2.4")
            .body("{}").build() : new MockResponse.Builder().code(403).build();
      }
    });
  }

  @Test
  void anUnknownCertificateAsksBeforeAnyCredentialIsSent() throws Exception {
    ConnectionConfig fresh = cfg("billing-api", Env.DEV, "{\"baseUrls\":[\"" + base() + "\"],"
        + "\"auth\":{\"type\":\"bearer\"}}");
    Fixtures.Secrets secrets = Fixtures.Secrets.of(HttpSettings.TOKEN, "tok-cnry-1");
    answerByToken("tok-cnry-1");

    TestResult asks = connector.test(fresh, secrets, Fixtures.Context.of(guard));

    assertThat(asks.ok()).isFalse();
    assertThat(asks.needsConfirmation().kind()).isEqualTo("tls-cert");
    assertThat(asks.needsConfirmation().text()).startsWith("Trust the certificate of api.test? CN=api.test, issued "
        + "by CN=Acme internal CA, valid until ");
    assertThat(asks.needsConfirmation().data().path("pin").asString()).startsWith("sha256/");
    assertThat(server.getRequestCount()).as("no request, no credential before trust").isZero();
    assertThat(secrets.resolved).isEmpty();

    // The core applies the patch (a Setup change) and tests again: now trusted, and the token is accepted.
    ObjectNode config = (ObjectNode) fresh.config().deepCopy();
    config.set("tls", asks.needsConfirmation().configPatch().path("tls"));
    ConnectionConfig confirmed = new ConnectionConfig(fresh.id(), fresh.name(), fresh.type(), fresh.env(), 2, config,
        fresh.secretFields(), null);
    TestResult ok = connector.test(confirmed, secrets, Fixtures.Context.of(guard));

    assertThat(ok.ok()).isTrue();
    assertThat(ok.serverVersion()).isEqualTo("acme/2.4");
    assertThat(ok.hints()).extracting(TestResult.Hint::code).contains("http.auth-required");
    assertThat(ok.checks()).extracting(TestResult.Check::name).containsExactly("dns", "tls", "request",
        "credentials");
    assertThat(server.takeRequest(5, TimeUnit.SECONDS).getHeaders().get("Authorization")).isNull();
    assertThat(server.takeRequest(5, TimeUnit.SECONDS).getHeaders().get("Authorization"))
        .isEqualTo("Bearer tok-cnry-1");

    // A certificate swapped later is unknown again: it asks again rather than sending the token.
    HeldCertificate other = new HeldCertificate.Builder().commonName("api.test").addSubjectAlternativeName("api.test")
        .build();
    try (MockWebServer swapped = new MockWebServer()) {
      swapped.useHttps(new HandshakeCertificates.Builder().heldCertificate(other).build().sslSocketFactory());
      swapped.start(Fixtures.ip("127.0.0.1"), 0);
      ObjectNode moved = (ObjectNode) confirmed.config().deepCopy();
      moved.putArray("baseUrls").add("https://api.test:" + swapped.getPort() + "/v1");
      ConnectionConfig elsewhere = new ConnectionConfig(fresh.id(), fresh.name(), fresh.type(), fresh.env(), 3, moved,
          fresh.secretFields(), null);
      assertThat(connector.test(elsewhere, secrets, Fixtures.Context.of(guard)).needsConfirmation()).isNotNull();
      assertThat(swapped.getRequestCount()).isZero();
    }
  }

  @Test
  void aPinThatTheCertificateBreaksFailsTheTest() {
    ConnectionConfig pinned = trusted("{\"type\":\"none\"}", ",\"pins\":[\"sha256/"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\"]");

    TestResult r = connector.test(pinned, new Fixtures.Secrets(), Fixtures.Context.of(guard));

    assertThat(r.ok()).isFalse();
    assertThat(r.error().code()).isEqualTo("http.tls-name");
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void aPrivateAddressNamesThePolicyChange() {
    GuardedEgress closed = Fixtures.guard(clients, "conn-2");

    TestResult r = connector.test(trusted("{\"type\":\"none\"}", ""), new Fixtures.Secrets(),
        Fixtures.Context.of(closed));

    assertThat(r.ok()).isFalse();
    assertThat(r.error().code()).isEqualTo("http.private-address");
    assertThat(r.error().message()).isEqualTo("api.test resolves to 127.0.0.1, a private address. Allow "
        + "127.0.0.1/32 for billing-api on dev.");
    assertThat(r.suggestedPolicyChange().toString())
        .isEqualTo("{\"kind\":\"allow-private\",\"cidr\":\"127.0.0.1/32\",\"connection\":\"billing-api\"}");
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void aRejectedTokenAndAnApiThatAnswersAnyone() {
    answerByToken("the-right-one");
    TestResult rejected = connector.test(trusted("{\"type\":\"bearer\"}", ""),
        Fixtures.Secrets.of(HttpSettings.TOKEN, "a-wrong-one"), Fixtures.Context.of(guard));
    assertThat(rejected.ok()).isFalse();
    assertThat(rejected.error().code()).isEqualTo("http.auth-rejected");
    assertThat(rejected.error().message()).isEqualTo("The token was rejected (403). Replace it under Credentials.");

    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        return new MockResponse.Builder().code(200).body("{}").build();
      }
    });
    TestResult open = connector.test(trusted("{\"type\":\"bearer\"}", ""),
        Fixtures.Secrets.of(HttpSettings.TOKEN, "tok"), Fixtures.Context.of(guard));
    assertThat(open.ok()).isTrue();
    assertThat(open.hints()).contains(new TestResult.Hint(TestResult.Severity.WARNING, "http.no-auth-needed",
        "/auth", "This API answers without credentials."));
  }

  @Test
  void aJwtWithWriteScopesNearItsExpiryIsNamed() {
    long exp = Instant.now().plusSeconds(3 * 86_400 + 600).getEpochSecond();
    String claims = "{\"sub\":\"svc-billing\",\"scope\":\"invoices:read refunds:write\",\"exp\":" + exp + "}";
    String jwt = "eyJhbGciOiJIUzI1NiJ9." + Base64.getUrlEncoder().withoutPadding().encodeToString(
        claims.getBytes(StandardCharsets.UTF_8)) + ".c2lnbmF0dXJl";
    answerByToken(jwt);

    TestResult r = connector.test(trusted("{\"type\":\"bearer\"}", ""), Fixtures.Secrets.of(HttpSettings.TOKEN, jwt),
        Fixtures.Context.of(guard));

    assertThat(r.ok()).isTrue();
    assertThat(r.permissions().level()).isEqualTo(TestResult.Level.WRITE);
    assertThat(r.hints()).contains(
        new TestResult.Hint(TestResult.Severity.WARNING, "http.write-scopes", HttpSettings.TOKEN,
            "Token has write scopes: refunds:write. Use a read-only token for prod."),
        new TestResult.Hint(TestResult.Severity.WARNING, "http.token-expiring", HttpSettings.TOKEN,
            "The token expires in 3 days."));
    assertThat(r.toString()).doesNotContain(jwt);
  }

  @Test
  void aSecretHeaderOfOtherMethodsIsCheckedWithoutBeingSent() {
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        return new MockResponse.Builder().code(200).build();
      }
    });
    ConnectionConfig hooks = trusted("{\"type\":\"none\"}", "");
    ObjectNode config = (ObjectNode) hooks.config().deepCopy();
    config.set("secretHeaders", Fixtures.tree("[{\"name\":\"X-Webhook-Secret\",\"value\":{\"$secret\":\"s\"},"
        + "\"methods\":[\"POST\"]}]"));
    ConnectionConfig withHeader = cfg("billing-api", Env.DEV, config.toString());
    Fixtures.Secrets secrets = new Fixtures.Secrets();
    secrets.unresolvable.add("/secretHeaders/0/value");

    TestResult r = connector.test(withHeader, secrets, Fixtures.Context.of(guard));

    assertThat(r.ok()).isTrue();
    assertThat(r.hints()).extracting(TestResult.Hint::code).contains("http.secret-header-unresolved");
  }

  @Test
  void healthReadsTheAnswerWithTheCredentials() {
    server.enqueue(new MockResponse.Builder().code(200).build());
    server.enqueue(new MockResponse.Builder().code(503).build());
    server.enqueue(new MockResponse.Builder().code(401).build());
    ConnectionConfig cfg = trusted("{\"type\":\"bearer\"}", "");
    Fixtures.Secrets secrets = Fixtures.Secrets.of(HttpSettings.TOKEN, "tok");

    assertThat(connector.health(cfg, secrets, Fixtures.Context.of(guard)).state())
        .isEqualTo(HealthResult.State.HEALTHY);
    HealthResult down = connector.health(cfg, secrets, Fixtures.Context.of(guard));
    assertThat(down.state()).isEqualTo(HealthResult.State.DEGRADED);
    assertThat(down.message()).isEqualTo("Answers 503 Service Unavailable");
    assertThat(connector.health(cfg, secrets, Fixtures.Context.of(guard)).message())
        .isEqualTo("The token was rejected (401)");

    server.close();
    assertThat(connector.health(cfg, secrets, Fixtures.Context.of(guard)).state())
        .isEqualTo(HealthResult.State.UNREACHABLE);
  }
}
