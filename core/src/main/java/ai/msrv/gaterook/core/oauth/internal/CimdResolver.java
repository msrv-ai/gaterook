package ai.msrv.gaterook.core.oauth.internal;

import ai.msrv.gaterook.core.platform.BuildFlags;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Client ID Metadata Documents (draft-ietf-oauth-client-id-metadata-document, report 02 §6.5): claude.ai's
 * {@code client_id} is the URL of its own metadata, and the tunnel's authorization server reads it there instead of
 * keeping a registration.
 *
 * <ul>
 *   <li>Which URL: only hosts of {@code allowedClientHosts} ({@code claude.ai}), https, a path, nothing else
 *       ({@link OAuthUris#clientIdUrl}).</li>
 *   <li>How: no redirects, at most 5 KB, 5 s for the whole answer (the request timeout of {@code java.net.http} ends
 *       with the headers, so the body has its own deadline), status 200, JSON, through the system proxy; every
 *       address of the host outside the special-use ranges of RFC 6890 (the host allowlist closes the gap between
 *       that check and the connection).</li>
 *   <li>What: {@code client_id} equal to the URL byte for byte, a public client
 *       ({@code token_endpoint_auth_method} {@code none} or absent), its {@code redirect_uris}.</li>
 *   <li>Cache: as {@code Cache-Control} says, bounded to 60 s … 24 h; failures aren't cached.</li>
 * </ul>
 * Tests of a non-release build may point it at a loopback HTTPS server: {@code gateway.test.oauth.cimd-ca} (a PEM
 * certificate it trusts instead of the system's, which also lets loopback addresses through) and
 * {@code gateway.test.oauth.client-hosts}.
 */
final class CimdResolver {

  static final int MAX_BYTES = 5 * 1024;
  static final Duration TIMEOUT = Duration.ofSeconds(5);
  static final Duration MIN_CACHE = Duration.ofSeconds(60);
  static final Duration MAX_CACHE = Duration.ofHours(24);

  private static final Logger log = LoggerFactory.getLogger(CimdResolver.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Pattern MAX_AGE = Pattern.compile("(?:^|[,\\s])(?:s-)?max-age\\s*=\\s*(\\d{1,9})");

  /** A client's document as read: who it is and where it may be redirected. */
  record ClientMetadata(String clientId, String host, String name, List<String> redirectUris, Instant until) {}

  /** Why a client is refused; the message is shown on the error page, never a redirect. */
  static final class CimdException extends Exception {
    private static final long serialVersionUID = 1L;

    CimdException(String message) {
      super(message);
    }
  }

  private final List<String> allowedHosts;
  private final boolean allowLoopback;
  private final HttpClient http;
  private final Clock clock;
  private final Duration timeout;
  private final Map<String, ClientMetadata> cache = new ConcurrentHashMap<>();

  CimdResolver(List<String> allowedHosts, String testCa, Clock clock) {
    this(allowedHosts, testCa, clock, TIMEOUT);
  }

  /** {@code timeout}: the whole fetch, connection to the last byte (a test's is shorter than {@link #TIMEOUT}). */
  CimdResolver(List<String> allowedHosts, String testCa, Clock clock, Duration timeout) {
    this.clock = clock;
    this.timeout = timeout;
    this.allowedHosts = List.copyOf(allowedHosts);
    boolean test = testCa != null && !testCa.isBlank() && !BuildFlags.RELEASE;
    this.allowLoopback = test;
    HttpClient.Builder b = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(timeout)
        .proxy(ProxySelector.getDefault());
    if (test) {
      log.warn("Test override: client metadata documents are read with the CA of {} and may live on loopback",
          testCa);
      b.sslContext(trusting(Path.of(testCa)));
    }
    this.http = b.build();
  }

  List<String> allowedHosts() {
    return allowedHosts;
  }

  /** Whether {@code clientId} has the shape of a metadata document URL on an allowed host (no I/O). */
  boolean accepts(String clientId) {
    return OAuthUris.clientIdUrl(clientId, allowLoopback)
        .map(u -> allowedHosts.contains(u.getHost().toLowerCase(Locale.ROOT))).orElse(false);
  }

  ClientMetadata resolve(String clientId) throws CimdException, InterruptedException {
    URI url = OAuthUris.clientIdUrl(clientId, allowLoopback).orElseThrow(() -> new CimdException("The client id "
        + "isn't a URL of a client metadata document."));
    String host = url.getHost().toLowerCase(Locale.ROOT);
    if (!allowedHosts.contains(host)) {
      throw new CimdException("Client " + host + " isn't allowed to sign in here.");
    }
    ClientMetadata cached = cache.get(clientId);
    Instant now = clock.instant();
    if (cached != null && cached.until().isAfter(now)) {
      return cached;
    }
    requirePublic(host);
    HttpResponse<byte[]> rs = fetch(url, host);
    if (rs.statusCode() != 200) {
      throw new CimdException("The client's metadata answered HTTP " + rs.statusCode() + ".");
    }
    byte[] body = rs.body();
    JsonNode doc;
    try {
      doc = JSON.readTree(body);
    } catch (JacksonException e) {
      throw new CimdException("The client's metadata isn't JSON.");
    }
    if (doc == null || !doc.isObject() || !clientId.equals(doc.path("client_id").asString(""))) {
      throw new CimdException("The client's metadata names another client_id than its own URL.");
    }
    String auth = doc.path("token_endpoint_auth_method").asString("none");
    if (!"none".equals(auth)) {
      throw new CimdException("Only public clients may sign in here (token_endpoint_auth_method " + auth + ").");
    }
    List<String> redirects = new ArrayList<>();
    for (JsonNode r : doc.path("redirect_uris")) {
      String uri = r.asString("");
      if (!uri.isBlank() && uri.length() <= 512 && redirects.size() < 10) {
        redirects.add(uri);
      }
    }
    if (redirects.isEmpty()) {
      throw new CimdException("The client's metadata has no redirect_uris.");
    }
    String name = ConsentPages.plain(doc.path("client_name").asString(host), 128);
    ClientMetadata meta = new ClientMetadata(clientId, host, name, List.copyOf(redirects),
        now.plus(cacheFor(rs.headers().firstValue("Cache-Control").orElse(null))));
    cache.put(clientId, meta);
    return meta;
  }

  /**
   * The document's answer within {@link #timeout} from the first byte sent to the last received: a 200's body of at
   * most {@link #MAX_BYTES} (another status's body is dropped).
   */
  private HttpResponse<byte[]> fetch(URI url, String host) throws CimdException, InterruptedException {
    CompletableFuture<HttpResponse<byte[]>> answer = http.sendAsync(HttpRequest.newBuilder(url).timeout(timeout)
        .header("Accept", "application/json").GET().build(), info -> info.statusCode() == 200
            ? HttpResponse.BodySubscribers.limiting(HttpResponse.BodySubscribers.ofByteArray(), MAX_BYTES)
            : HttpResponse.BodySubscribers.replacing(null));
    try {
      return answer.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      answer.cancel(true);
      throw new CimdException("The client's metadata at " + host + " didn't arrive within " + timeout.toSeconds()
          + " s.");
    } catch (InterruptedException e) {
      answer.cancel(true);
      throw e;
    } catch (ExecutionException e) {
      for (Throwable t = e.getCause(); t != null; t = t.getCause() == t ? null : t.getCause()) {
        if (t instanceof IOException && String.valueOf(t.getMessage()).contains("exceeds capacity")) {
          throw new CimdException("The client's metadata is larger than 5 KB.");
        }
      }
      Throwable cause = e.getCause() == null ? e : e.getCause();
      throw new CimdException("The client's metadata at " + host + " can't be read: " + (cause.getMessage() == null
          ? cause.getClass().getSimpleName() : cause.getMessage()));
    }
  }

  /** How long a document stays cached: its {@code max-age}, bounded to 60 s … 24 h. */
  static Duration cacheFor(String cacheControl) {
    if (cacheControl == null) {
      return MIN_CACHE;
    }
    Matcher m = MAX_AGE.matcher(cacheControl.toLowerCase(Locale.ROOT));
    Duration d = m.find() ? Duration.ofSeconds(Long.parseLong(m.group(1))) : MIN_CACHE;
    return d.compareTo(MIN_CACHE) < 0 ? MIN_CACHE : d.compareTo(MAX_CACHE) > 0 ? MAX_CACHE : d;
  }

  private void requirePublic(String host) throws CimdException {
    InetAddress[] all;
    try {
      all = InetAddress.getAllByName(host);
    } catch (IOException e) {
      throw new CimdException(host + " doesn't resolve.");
    }
    for (InetAddress a : all) {
      if (!SpecialUse.global(a) && !(allowLoopback && a.isLoopbackAddress())) {
        throw new CimdException(host + " resolves to an address that isn't public.");
      }
    }
  }

  Optional<ClientMetadata> cached(String clientId) {
    return Optional.ofNullable(cache.get(clientId));
  }

  private static SSLContext trusting(Path pem) {
    try (InputStream in = Files.newInputStream(pem)) {
      KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
      store.load(null, null);
      int i = 0;
      for (Certificate c : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
        store.setCertificateEntry("ca-" + i++, c);
      }
      TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      tmf.init(store);
      SSLContext ctx = SSLContext.getInstance("TLS");
      ctx.init(null, tmf.getTrustManagers(), null);
      return ctx;
    } catch (Exception e) {
      throw new IllegalStateException("Can't read the test CA " + pem, e);
    }
  }
}
