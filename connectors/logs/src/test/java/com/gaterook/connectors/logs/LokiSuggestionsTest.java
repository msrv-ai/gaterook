package com.gaterook.connectors.logs;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.Suggestions;
import com.gaterook.connector.api.Suggestions.Option;
import com.gaterook.connector.api.TestResult;
import com.gaterook.connectors.cloudauth.CloudCredentials;
import com.gaterook.connectors.cloudauth.CloudEndpoints;
import com.gaterook.connectors.cloudauth.CloudLocal;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Help for the Loki form: Grafana Cloud's sign-in from its address, a Loki on this computer asked once without
 * credentials (none needed, a tenant needed, the other scheme, a certificate to trust), and once the draft signs in,
 * its version and labels. A typed token never goes to a server for a local suggestion.
 */
class LokiSuggestionsTest {

  private static final String TOKEN = "cnry-loki-suggest-" + Long.toHexString(System.nanoTime());

  private final MockWebServer server = new MockWebServer();
  private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  private volatile Function<RecordedRequest, MockResponse> answer = r -> json(404, "{}");
  private CloudCredentials cloud;
  private BackendConnector connector;

  @TempDir
  Path home;

  @BeforeEach
  void start() throws IOException {
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        requests.add(request);
        return answer.apply(request);
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    cloud = new CloudCredentials(CloudEndpoints.PRODUCTION, Clock.systemUTC());
    connector = new BackendConnector("loki", cloud, Clock.systemUTC(),
        new CloudLocal(CloudEndpoints.PRODUCTION, k -> null, home));
  }

  @AfterEach
  void stop() throws IOException {
    connector.close();
    cloud.close();
    server.close();
  }

  private static ConnectionConfig draft(String json) {
    return new ConnectionConfig("draft-1", "draft", "loki", Env.DEV, 0, TestCtx.JSON.readTree(json),
        Set.of("/auth/secret"), null);
  }

  private String local() {
    return "http://127.0.0.1:" + server.getPort();
  }

  private Suggestions suggest(String json, Suggestions.Scope scope) throws Exception {
    return connector.suggest(draft(json), new TestSecrets().with("/auth/secret", TOKEN), TestCtx.of(), scope);
  }

  @Test
  void grafanaCloudSignsInWithItsUserIdAndAnAccessPolicyToken() throws Exception {
    Suggestions s = suggest("""
        {"backend":"loki","url":"https://logs-prod-012.grafana.net","tenant":"team-a","auth":{"mode":"bearer"}}""",
        Suggestions.Scope.LOCAL);

    assertThat(s.fields().get("/auth/mode").value()).isEqualTo("basic");
    assertThat(s.fields().get("/auth/mode").source()).isEqualTo("the URL");
    assertThat(s.notes()).anyMatch(n -> n.contains("numeric user ID") && n.contains("logs:read"))
        .anyMatch(n -> n.contains("leave Tenant empty"));
    assertThat(requests).isEmpty();
  }

  @Test
  void aLokiOnThisComputerWithoutAuthNeedsNoCredentialsAndNeverSeesTheTypedToken() throws Exception {
    answer = r -> r.getUrl().encodedPath().equals("/loki/api/v1/labels")
        ? json(200, "{\"status\":\"success\",\"data\":[\"app\",\"job\"]}") : json(404, "{}");

    Suggestions s = suggest("{\"backend\":\"loki\",\"url\":\"" + local() + "\",\"auth\":{\"mode\":\"bearer\"}}",
        Suggestions.Scope.LOCAL);

    assertThat(s.fields().get("/auth/mode").value()).isEqualTo("none");
    assertThat(s.notes()).contains("Loki on 127.0.0.1:" + server.getPort() + " answers without credentials: 2 labels "
        + "in the last hour (app, job).");
    assertThat(requests).singleElement().satisfies(r -> {
      assertThat(r.getHeaders().get("Authorization")).isNull();
      assertThat(r.getHeaders().get("X-Scope-OrgID")).isNull();
      assertThat(r.getUrl().encodedQuery()).isEqualTo("since=1h");
    });
  }

  @Test
  void aMultiTenantLokiAsksForATenantAndHearsTheOneTyped() throws Exception {
    answer = r -> r.getHeaders().get("X-Scope-OrgID") == null ? text(401, "no org id\n")
        : json(200, "{\"status\":\"success\",\"data\":[\"app\"]}");

    Suggestions without = suggest("{\"backend\":\"loki\",\"url\":\"" + local() + "\"}", Suggestions.Scope.LOCAL);
    Suggestions with = suggest("{\"backend\":\"loki\",\"url\":\"" + local() + "\",\"tenant\":\"team-a\"}",
        Suggestions.Scope.LOCAL);

    assertThat(without.notes()).contains("Loki on 127.0.0.1:" + server.getPort() + " is multi-tenant: set Tenant "
        + "(X-Scope-OrgID) to the one your log shippers send.");
    assertThat(without.fields()).doesNotContainKey("/auth/mode");
    assertThat(with.fields().get("/auth/mode").value()).isEqualTo("none");
    assertThat(requests.getLast().getHeaders().get("X-Scope-OrgID")).isEqualTo("team-a");
  }

  @Test
  void signedInItNamesTheVersionAndTheLabelsOfTheTenant() throws Exception {
    answer = r -> switch (r.getUrl().encodedPath()) {
      case "/loki/api/v1/labels" -> json(200, "{\"status\":\"success\",\"data\":[\"app\",\"namespace\",\"pod\"]}");
      case "/loki/api/v1/status/buildinfo" -> json(200, "{\"version\":\"3.7.9\"}");
      default -> json(404, "{}");
    };

    Suggestions s = suggest("{\"backend\":\"loki\",\"url\":\"" + local() + "\",\"tenant\":\"team-a\","
        + "\"auth\":{\"mode\":\"bearer\"}}", Suggestions.Scope.SERVICE);

    assertThat(s.notes()).containsExactly("Signed in to Loki 3.7.9 at 127.0.0.1:" + server.getPort() + " as tenant "
        + "team-a: 3 labels in the last hour (app, namespace, pod).");
    assertThat(requests).allSatisfy(r -> {
      assertThat(r.getHeaders().get("Authorization")).isEqualTo("Bearer " + TOKEN);
      assertThat(r.getHeaders().get("X-Scope-OrgID")).isEqualTo("team-a");
      assertThat(r.getMethod()).isEqualTo("GET");
    });
  }

  @Test
  void signedInWithoutATenantLokiSaysWhatIsMissing() throws Exception {
    answer = r -> text(401, "no org id\n");

    Suggestions s = suggest("{\"backend\":\"loki\",\"url\":\"" + local() + "\",\"auth\":{\"mode\":\"bearer\"}}",
        Suggestions.Scope.SERVICE);

    assertThat(s.notes()).containsExactly("Loki needs a tenant: set Tenant (X-Scope-OrgID) in the connection.");
    assertThat(s.fields()).isEmpty();
  }

  @Test
  void aCertificateThisComputerDoesNotTrustAsksForTheCaAndNothingIsSent() throws Exception {
    HeldCertificate ca = new HeldCertificate.Builder().commonName("Loki Test Root").certificateAuthority(0).build();
    HeldCertificate leaf = new HeldCertificate.Builder().commonName("Loki Test Server")
        .addSubjectAlternativeName("127.0.0.1").signedBy(ca).build();
    try (MockWebServer tls = new MockWebServer()) {
      tls.useHttps(new HandshakeCertificates.Builder().heldCertificate(leaf, ca.certificate()).build()
          .sslSocketFactory());
      tls.setDispatcher(new Dispatcher() {
        @Override
        public MockResponse dispatch(RecordedRequest request) {
          return json(200, "{\"status\":\"success\",\"data\":[\"app\"]}");
        }
      });
      tls.start(InetAddress.getByName("127.0.0.1"), 0);
      String address = "127.0.0.1:" + tls.getPort();
      String config = "{\"backend\":\"loki\",\"url\":\"https://" + address + "\",\"auth\":{\"mode\":\"bearer\"}";

      Suggestions local = suggest(config + "}", Suggestions.Scope.LOCAL);
      Suggestions service = suggest(config + "}", Suggestions.Scope.SERVICE);
      TestResult test = connector.test(draft(config + "}"), new TestSecrets().with("/auth/secret", TOKEN),
          TestCtx.of());

      String note = address + " uses a certificate this computer doesn't trust: paste the CA certificate (PEM) that "
          + "signed it under Advanced settings.";
      assertThat(local.notes()).containsExactly(note);
      assertThat(service.notes()).containsExactly(note);
      assertThat(test.ok()).isFalse();
      assertThat(test.error().message()).contains("isn't trusted on this computer");
      assertThat(test.hints()).anySatisfy(h -> {
        assertThat(h.code()).isEqualTo("logs.untrusted-certificate");
        assertThat(h.field()).isEqualTo("/caCertificate");
      });
      assertThat(tls.getRequestCount()).as("no request without a verified certificate").isZero();

      // With its CA pasted, the server is asked: still without the token.
      Suggestions trusted = suggest(config + ",\"caCertificate\":" + TestCtx.JSON.writeValueAsString(
          ca.certificatePem()) + "}", Suggestions.Scope.LOCAL);
      assertThat(trusted.fields().get("/auth/mode").value()).isEqualTo("none");
      assertThat(tls.getRequestCount()).isEqualTo(1);
      assertThat(tls.takeRequest().getHeaders().get("Authorization")).isNull();
    }
  }

  @Test
  void anAddressThatSpeaksTlsOffersHttpsAndAPlainOneHttp() throws Exception {
    HeldCertificate ca = new HeldCertificate.Builder().certificateAuthority(0).build();
    HeldCertificate leaf = new HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").signedBy(ca).build();
    try (MockWebServer tls = new MockWebServer()) {
      tls.useHttps(new HandshakeCertificates.Builder().heldCertificate(leaf, ca.certificate()).build()
          .sslSocketFactory());
      tls.start(InetAddress.getByName("127.0.0.1"), 0);

      Suggestions toTls = suggest("{\"backend\":\"loki\",\"url\":\"http://127.0.0.1:" + tls.getPort() + "\"}",
          Suggestions.Scope.LOCAL);
      Suggestions toPlain = suggest("{\"backend\":\"loki\",\"url\":\"https://127.0.0.1:" + server.getPort() + "\"}",
          Suggestions.Scope.LOCAL);

      assertThat(toTls.fields().get("/url").options()).extracting(Option::value)
          .containsExactly("https://127.0.0.1:" + tls.getPort());
      assertThat(toTls.notes()).containsExactly("127.0.0.1:" + tls.getPort() + " speaks TLS: use https://127.0.0.1:"
          + tls.getPort() + ", and paste the CA certificate (PEM) that signed its certificate under Advanced "
          + "settings.");
      assertThat(toPlain.fields().get("/url").options()).extracting(Option::value)
          .containsExactly("http://127.0.0.1:" + server.getPort());
      assertThat(tls.getRequestCount()).isZero();
      assertThat(requests).isEmpty();
    }
  }

  @Test
  void anEmptyUrlIsOfferedAServerThatListensOnThisComputer() {
    Suggestions.Builder out = Suggestions.builder();

    Loopback.offer(out, "/url", "A server", server.getPort(), TestCtx.of());

    Suggestions.Field url = out.build().fields().get("/url");
    assertThat(url.value()).as("never filled in").isNull();
    assertThat(url.source()).isEqualTo("this computer");
    assertThat(url.options()).singleElement().satisfies(o -> {
      assertThat(o.value()).isEqualTo("http://localhost:" + server.getPort());
      assertThat(o.detail()).isEqualTo("A server answers on port " + server.getPort() + " without TLS");
    });
    assertThat(requests).as("a TLS hello only, no request").isEmpty();
  }

  private static MockResponse json(int status, String body) {
    return new MockResponse.Builder().code(status).addHeader("Content-Type", "application/json").body(body).build();
  }

  private static MockResponse text(int status, String body) {
    return new MockResponse.Builder().code(status).addHeader("Content-Type", "text/plain").body(body).build();
  }
}
