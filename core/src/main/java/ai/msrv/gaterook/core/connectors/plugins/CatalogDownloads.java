package ai.msrv.gaterook.core.connectors.plugins;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connectors.support.egress.AddressPolicy;
import ai.msrv.gaterook.connectors.support.egress.EgressClients;
import ai.msrv.gaterook.connectors.support.egress.EgressSpec;
import ai.msrv.gaterook.connectors.support.egress.UrlRules;
import java.net.URI;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import okhttp3.HttpUrl;

/**
 * Every download of the catalog — {@code index.json}, its signature, plugin JARs, npm lockfiles — through the egress
 * guard (ARCHITECTURE.md §9 "SSRF", report 11 §4.4): {@code https://} only, at most three redirects, each hop checked
 * again, never this computer ({@code neverLoopback}) and never the core's own ports, the platform's CAs.
 *
 * <p><b>Test override.</b> A non-release build started with {@code gateway.test.catalog.ca} (a PEM file of CA
 * certificates) trusts only those CAs for the catalog and may reach loopback ({@code 127.0.0.0/8}, {@code ::1}) — but
 * still not the core's own ports — so a test serves the catalog from an HTTPS MockWebServer with a
 * {@code HeldCertificate}. A release ignores the property ({@code BuildFlags.RELEASE}), like the test CA of
 * {@code oauth.CimdResolver}.
 */
public final class CatalogDownloads {

  static final int MAX_REDIRECTS = 3;

  /** Why a download failed: the message says what happened, in the app's words. */
  public static final class DownloadException extends Exception {
    private static final long serialVersionUID = 1L;
    private final int status;

    DownloadException(String message, int status, Throwable cause) {
      super(message, cause);
      this.status = status;
    }

    /** The HTTP status that ended the download, or 0 when no answer came. */
    public int status() {
      return status;
    }
  }

  private final EgressClients egress;
  private final List<X509Certificate> testCa;

  /**
   * @param testCa the CAs of the test override (non-release builds only), or empty: the platform's CAs, no loopback
   */
  public CatalogDownloads(EgressClients egress, List<X509Certificate> testCa) {
    this.egress = egress;
    this.testCa = List.copyOf(testCa);
  }

  /** Whether the test override is on (the CAs of {@code gateway.test.catalog.ca}, loopback allowed). */
  public boolean testOverride() {
    return !testCa.isEmpty();
  }

  /**
   * GETs {@code url} and reads at most {@code maxBytes}; a larger answer is refused, not cut.
   *
   * @param what what is downloaded, for the messages ("index.json", "the plugin")
   */
  public byte[] get(HttpUrl url, long maxBytes, Duration timeout, String accept, String what)
      throws DownloadException {
    HttpUrl current = url;
    EgressSpec spec = new EgressSpec("catalog", policy(), Duration.ofSeconds(5), timeout, tls());
    for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
      ExecContext.EgressResponse r;
      try {
        r = egress.send(spec, new ExecContext.EgressRequest("GET", URI.create(current.toString()),
            Map.of("Accept", List.of(accept)), null, timeout), maxBytes);
      } catch (ConnectorException e) {
        throw new DownloadException(what + " can't be downloaded from " + current.host() + ": " + e.getMessage(), 0,
            e);
      }
      if (r.status() >= 300 && r.status() < 400) {
        List<String> location = r.headers().get("location");
        if (location == null || location.isEmpty()) {
          throw new DownloadException(current.host() + " redirected " + what + " without a Location.", r.status(),
              null);
        }
        HttpUrl next = current.resolve(location.getFirst());
        if (next == null) {
          throw new DownloadException(current.host() + " redirected " + what + " to an invalid address.", r.status(),
              null);
        }
        current = https(next.toString(), what);
        continue;
      }
      if (r.status() < 200 || r.status() >= 300) {
        throw new DownloadException(current.host() + " answered HTTP " + r.status() + " for " + what + ".",
            r.status(), null);
      }
      if (r.truncated()) {
        throw new DownloadException(what + " is larger than " + (maxBytes / (1024 * 1024)) + " MB.", r.status(),
            null);
      }
      return r.body();
    }
    throw new DownloadException(what + ": too many redirects.", 0, null);
  }

  /** {@code url} parsed and checked: {@code https://} only. */
  public static HttpUrl https(String url, String what) throws DownloadException {
    try {
      HttpUrl parsed = UrlRules.parse(url);
      if (!parsed.scheme().equals("https")) {
        throw new DownloadException(what + " is downloaded over https:// only.", 0, null);
      }
      return parsed;
    } catch (UrlRules.UrlRejectedException e) {
      throw new DownloadException("Bad address for " + what + ": " + e.getMessage(), 0, null);
    }
  }

  /**
   * An artifact's address: an absolute {@code https://} URL as is, a file name or relative path against the folder
   * the index came from (so one GitHub release carries the index and its files).
   */
  public static HttpUrl resolve(String indexUrl, String ref, String what) throws DownloadException {
    if (ref == null || ref.isBlank()) {
      throw new DownloadException(what + " has no address in the catalog.", 0, null);
    }
    if (ref.contains("://")) {
      return https(ref, what);
    }
    HttpUrl base = https(indexUrl, what);
    HttpUrl resolved = base.resolve(ref);
    if (resolved == null) {
      throw new DownloadException("Bad address for " + what + ": " + ref, 0, null);
    }
    return https(resolved.toString(), what);
  }

  private AddressPolicy policy() {
    AddressPolicy.Builder b = AddressPolicy.builder().corePorts(egress.settings().corePorts().get());
    return testOverride() ? b.allowPrivate("127.0.0.0/8", "::1/128").build() : b.neverLoopback().build();
  }

  private EgressSpec.Tls tls() {
    return testOverride() ? new EgressSpec.Tls(testCa, false, List.of(), false) : EgressSpec.Tls.PLATFORM;
  }
}
