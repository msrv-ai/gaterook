package ai.msrv.gaterook.core.oauth.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.core.platform.TestGateway;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Client ID Metadata Documents over real HTTPS (ARCHITECTURE.md §10 K3: "CIMD over HTTPS (HeldCertificate)"): a
 * loopback document server with its own CA that only a test resolver trusts. What claude.ai serves is read once and
 * cached by its {@code Cache-Control}; anything else about the document refuses the client.
 */
class CimdResolverTest {

  private static final HeldCertificate CA = new HeldCertificate.Builder().certificateAuthority(0)
      .commonName("CIMD test CA").build();
  private static final HeldCertificate LEAF = new HeldCertificate.Builder().commonName("localhost")
      .addSubjectAlternativeName("localhost").signedBy(CA).build();

  private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-25T10:00:00Z"));
  private final Clock clock = new Clock() {
    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now.get();
    }
  };

  private MockWebServer server;
  private Path caFile;
  private String clientId;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.useHttps(new HandshakeCertificates.Builder().heldCertificate(LEAF).build().sslSocketFactory());
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    Path dir = TestGateway.newHome("cimd");
    caFile = dir.resolve("ca.pem");
    Files.writeString(caFile, CA.certificatePem());
    clientId = "https://localhost:" + server.getPort() + "/oauth/mcp-oauth-client-metadata";
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  private CimdResolver resolver() {
    return new CimdResolver(List.of("claude.ai", "localhost"), caFile.toString(), clock);
  }

  private void serve(int code, String body, String cacheControl) {
    MockResponse.Builder b = new MockResponse.Builder().code(code).addHeader("Content-Type", "application/json")
        .body(body);
    if (cacheControl != null) {
      b.addHeader("Cache-Control", cacheControl);
    }
    server.enqueue(b.build());
  }

  private String document(String id) {
    return """
        {"client_id":"%s","client_name":"Claude\\u0007 \\u202e(by Anthropic)","client_uri":"https://claude.ai",
         "redirect_uris":["https://claude.ai/api/mcp/auth_callback"],"grant_types":["authorization_code",
         "refresh_token"],"response_types":["code"],"token_endpoint_auth_method":"none"}""".formatted(id);
  }

  @Test
  void claudesDocumentIsReadOverHttpsAndCachedByItsMaxAge() throws Exception {
    serve(200, document(clientId), "public, max-age=3600");
    CimdResolver resolver = resolver();
    assertThat(resolver.accepts(clientId)).isTrue();
    CimdResolver.ClientMetadata meta = resolver.resolve(clientId);
    assertThat(meta.clientId()).isEqualTo(clientId);
    assertThat(meta.host()).isEqualTo("localhost");
    assertThat(meta.name()).as("controls and bidi overrides dropped").isEqualTo("Claude (by Anthropic)");
    assertThat(meta.redirectUris()).containsExactly("https://claude.ai/api/mcp/auth_callback");
    assertThat(meta.until()).isEqualTo(now.get().plus(Duration.ofHours(1)));
    var request = server.takeRequest();
    assertThat(request.getHeaders().get("Accept")).isEqualTo("application/json");
    assertThat(request.getHeaders().get("Authorization")).isNull();

    // Within the hour: no second fetch.
    now.set(now.get().plusSeconds(3599));
    assertThat(resolver.resolve(clientId)).isSameAs(meta);
    assertThat(server.getRequestCount()).isEqualTo(1);
    // After it: read again.
    now.set(now.get().plusSeconds(2));
    serve(200, document(clientId), "max-age=5");
    CimdResolver.ClientMetadata again = resolver.resolve(clientId);
    assertThat(server.getRequestCount()).isEqualTo(2);
    assertThat(again.until()).as("never under a minute").isEqualTo(now.get().plusSeconds(60));
  }

  @Test
  void anyOtherDocumentRefusesTheClient() throws Exception {
    CimdResolver resolver = resolver();
    serve(200, document("https://claude.ai/oauth/mcp-oauth-client-metadata"), null);
    assertThatThrownBy(() -> resolver.resolve(clientId)).hasMessageContaining("another client_id");
    serve(200, document(clientId).replace("\"none\"", "\"client_secret_basic\""), null);
    assertThatThrownBy(() -> resolver.resolve(clientId)).hasMessageContaining("Only public clients");
    serve(200, document(clientId).replace("[\"https://claude.ai/api/mcp/auth_callback\"]", "[]"), null);
    assertThatThrownBy(() -> resolver.resolve(clientId)).hasMessageContaining("no redirect_uris");
    serve(200, "<html>not json</html>", null);
    assertThatThrownBy(() -> resolver.resolve(clientId)).hasMessageContaining("isn't JSON");
    serve(200, document(clientId).replace("\"client_uri\"", "\"padding\":\"" + "x".repeat(6000) + "\",\"client_uri\""),
        null);
    assertThatThrownBy(() -> resolver.resolve(clientId)).hasMessageContaining("larger than 5 KB");
    server.enqueue(new MockResponse.Builder().code(302).addHeader("Location", "https://evil.example/meta").build());
    assertThatThrownBy(() -> resolver.resolve(clientId)).as("never followed").hasMessageContaining("HTTP 302");
    serve(404, "{}", null);
    assertThatThrownBy(() -> resolver.resolve(clientId)).hasMessageContaining("HTTP 404");
    // Failures aren't cached: the next good answer is taken.
    serve(200, document(clientId), null);
    assertThat(resolver.resolve(clientId).clientId()).isEqualTo(clientId);
    assertThat(server.getRequestCount()).isEqualTo(8);
  }

  @Test
  void onlyAllowedHostsAndPublicAddressesAreAsked() throws Exception {
    CimdResolver resolver = resolver();
    assertThat(resolver.accepts("https://evil.example/oauth/meta")).isFalse();
    assertThatThrownBy(() -> resolver.resolve("https://evil.example/oauth/meta"))
        .hasMessageContaining("isn't allowed to sign in here");
    assertThatThrownBy(() -> resolver.resolve("http://localhost/x")).hasMessageContaining("isn't a URL");

    // Without the test CA (a release, or a core started without the override): loopback is never a document server.
    CimdResolver production = new CimdResolver(List.of("localhost"), null, clock);
    assertThat(production.accepts(clientId)).as("and no other port than 443").isFalse();
    assertThatThrownBy(() -> production.resolve("https://localhost/oauth/meta"))
        .hasMessageContaining("isn't public");
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void aDocumentThatTricklesInIsRefusedAtTheDeadlineOfTheWholeAnswer() {
    // Headers at once, then a byte a second: java.net.http's request timeout ends with the headers.
    server.enqueue(new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
        .body(document(clientId)).throttleBody(1, 1, java.util.concurrent.TimeUnit.SECONDS).build());
    CimdResolver resolver = new CimdResolver(List.of("localhost"), caFile.toString(), clock, Duration.ofMillis(800));
    long t0 = System.nanoTime();
    org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
        assertThatThrownBy(() -> resolver.resolve(clientId)).isInstanceOf(CimdResolver.CimdException.class)
            .hasMessageContaining("didn't arrive within"));
    assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(5));
    assertThat(resolver.cached(clientId)).as("a failure isn't cached").isEmpty();
  }

  @Test
  void aServerWithAnotherCertificateIsNotTrusted() throws Exception {
    HeldCertificate other = new HeldCertificate.Builder().certificateAuthority(0).commonName("Other CA").build();
    Path otherCa = caFile.resolveSibling("other.pem");
    Files.writeString(otherCa, other.certificatePem());
    serve(200, document(clientId), null);
    CimdResolver resolver = new CimdResolver(List.of("localhost"), otherCa.toString(), clock);
    assertThatThrownBy(() -> resolver.resolve(clientId)).hasMessageContaining("can't be read");
  }
}
