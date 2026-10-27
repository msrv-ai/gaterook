package ai.msrv.gaterook.core.connectors.plugins;

import ai.msrv.gaterook.core.platform.ProblemException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import okhttp3.HttpUrl;
import tools.jackson.databind.JsonNode;

/**
 * Files the signed catalog pins — a plugin's JAR, an MCP server's npm lockfile — downloaded through
 * {@link CatalogDownloads} (report 11 §4.4, 16 §6.1, step K6). A file's address is resolved against the folder the
 * index came from; the bytes must match the signed {@code sha256} and {@code size} and must not be on the revocation
 * list — otherwise "Package doesn't match the signed catalog", and nothing of it is kept.
 */
public final class PluginArtifacts {

  private static final Duration TIMEOUT = Duration.ofSeconds(60);

  private final CatalogDownloads downloads;
  private final PluginCatalog catalog;

  public PluginArtifacts(CatalogDownloads downloads, PluginCatalog catalog) {
    this.downloads = downloads;
    this.catalog = catalog;
  }

  /** The bytes of a plugin entry's JAR, verified against its signed SHA-256 and size. */
  public byte[] download(JsonNode entry) {
    return fetchPinned(entry.path("artifact"), PluginInstaller.MAX_JAR_BYTES, "the plugin",
        "application/java-archive, */*;q=0.5");
  }

  /**
   * The bytes of a file the catalog pins ({@code {url, sha256, size}}).
   *
   * @throws ProblemException 409 when it can't be downloaded or isn't the file the catalog signed
   */
  public byte[] fetchPinned(JsonNode ref, long maxBytes, String what, String accept) {
    String expectedSha = ref.path("sha256").asString(null);
    long expectedSize = ref.path("size").asLong(-1);
    if (expectedSha == null || !expectedSha.matches("[0-9a-fA-F]{64}")) {
      throw ProblemException.conflict("Bad catalog entry", "The catalog entry has no SHA-256 for " + what + ".");
    }
    byte[] bytes;
    try {
      HttpUrl url = CatalogDownloads.resolve(catalog.source(), ref.path("url").asString(null), what);
      bytes = downloads.get(url, maxBytes, TIMEOUT, accept, what);
    } catch (CatalogDownloads.DownloadException e) {
      throw ProblemException.conflict("Can't download " + what, e.getMessage());
    }
    if (expectedSize >= 0 && bytes.length != expectedSize) {
      throw ProblemException.conflict("Package doesn't match the signed catalog", "The download of " + what + " is "
          + bytes.length + " bytes, the catalog says " + expectedSize + ".");
    }
    String sha = sha256(bytes);
    if (!sha.equalsIgnoreCase(expectedSha)) {
      throw ProblemException.conflict("Package doesn't match the signed catalog", "The download of " + what
          + " isn't the file the catalog signed (SHA-256 " + sha + ").");
    }
    if (catalog.isRevoked(sha)) {
      throw ProblemException.conflict("Revoked", "This build of " + what + " was revoked: "
          + catalog.revocationReason(sha).orElse("no reason given") + ".");
    }
    return bytes;
  }

  /** The file name a catalog entry's artifact goes by ({@code gw-jira-service-desk-1.3.0.jar}). */
  static String fileName(JsonNode entry, String fallback) {
    String url = entry.path("artifact").path("url").asString("");
    String path = url.replaceAll("[?#].*$", "");
    String name = path.substring(path.lastIndexOf('/') + 1);
    return name.isBlank() ? fallback : name;
  }

  static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
