package ai.msrv.gaterook.core.connectors.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connectors.support.egress.EgressClients;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.HttpUrl;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The catalog's downloads (ARCHITECTURE.md §9 "SSRF", report 11 §8.2, review 2026-10-27: the negative cases had no
 * test): {@code https://} only, also after a redirect; at most three redirects; a body over its limit refused, not
 * cut; and without the test override never this computer, whatever a name resolves to.
 */
class CatalogDownloadsTest {

  private static final HeldCertificate CA = new HeldCertificate.Builder().certificateAuthority(0)
      .commonName("Catalog downloads test CA").build();
  private static final HeldCertificate LEAF = new HeldCertificate.Builder().commonName("127.0.0.1")
      .addSubjectAlternativeName("127.0.0.1").addSubjectAlternativeName("catalog.test").signedBy(CA).build();

  private final Map<String, MockResponse> answers = new ConcurrentHashMap<>();
  private MockWebServer server;
  private EgressClients egress;

  @BeforeEach
  void start() throws Exception {
    server = new MockWebServer();
    server.useHttps(new HandshakeCertificates.Builder().heldCertificate(LEAF).build().sslSocketFactory());
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        MockResponse r = answers.get(request.getTarget());
        return r != null ? r : new MockResponse.Builder().code(404).build();
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    // catalog.test names this computer: a catalog host that rebinds to loopback.
    egress = new EgressClients(new EgressClients.Settings("mcp-gateway/test", Set.of(7465, 7466),
        host -> List.of(InetAddress.getByName("127.0.0.1"))));
  }

  @AfterEach
  void stop() {
    server.close();
    egress.close();
  }

  @Test
  void aRedirectToPlainHttpIsRefused() {
    redirect("/index.json", "http://127.0.0.1:" + server.getPort() + "/index.json");
    assertThatThrownBy(() -> testOverride().get(url("/index.json"), 1024, Duration.ofSeconds(5), "*/*", "index.json"))
        .isInstanceOf(CatalogDownloads.DownloadException.class).hasMessageContaining("https:// only");
  }

  @Test
  void threeRedirectsAreFollowedAndAFourthIsNot() throws Exception {
    redirect("/a", "/b");
    redirect("/b", "/c");
    redirect("/c", "/d");
    answers.put("/d", new MockResponse.Builder().code(200).body("{\"ok\":true}").build());
    byte[] body = testOverride().get(url("/a"), 1024, Duration.ofSeconds(5), "*/*", "index.json");
    assertThat(new String(body, StandardCharsets.UTF_8)).isEqualTo("{\"ok\":true}");

    redirect("/d", "/e");
    assertThatThrownBy(() -> testOverride().get(url("/a"), 1024, Duration.ofSeconds(5), "*/*", "index.json"))
        .isInstanceOf(CatalogDownloads.DownloadException.class).hasMessageContaining("too many redirects");
  }

  @Test
  void aBodyOverItsLimitIsRefusedNotCut() {
    answers.put("/big", new MockResponse.Builder().code(200).body("x".repeat(2048)).build());
    assertThatThrownBy(() -> testOverride().get(url("/big"), 1024, Duration.ofSeconds(5), "*/*", "the plugin"))
        .isInstanceOf(CatalogDownloads.DownloadException.class).hasMessageContaining("is larger than");
  }

  @Test
  void withoutTheTestOverrideThisComputerIsNeverReached() {
    answers.put("/index.json", new MockResponse.Builder().code(200).body("{}").build());
    CatalogDownloads release = new CatalogDownloads(egress, List.of());
    // By address, and by a name that resolves to it.
    for (String host : List.of("127.0.0.1", "catalog.test")) {
      HttpUrl at = HttpUrl.get("https://" + host + ":" + server.getPort() + "/index.json");
      assertThatThrownBy(() -> release.get(at, 1024, Duration.ofSeconds(5), "*/*", "index.json")).as(host)
          .isInstanceOf(CatalogDownloads.DownloadException.class);
    }
    assertThat(server.getRequestCount()).as("nothing reached the server").isZero();
  }

  private CatalogDownloads testOverride() {
    return new CatalogDownloads(egress, List.of(CA.certificate()));
  }

  private HttpUrl url(String path) {
    return HttpUrl.get("https://127.0.0.1:" + server.getPort() + path);
  }

  private void redirect(String from, String to) {
    answers.put(from, new MockResponse.Builder().code(302).addHeader("Location", to).build());
  }
}
