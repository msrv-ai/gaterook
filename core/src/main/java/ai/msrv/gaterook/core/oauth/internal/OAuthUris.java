package ai.msrv.gaterook.core.oauth.internal;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The URI rules of both authorization servers (report 02 §6–§7): canonical resources (RFC 8707), Client ID Metadata
 * Document URLs, redirect URIs (exact, loopback with any port per RFC 8252 §7.3) and PKCE S256 (RFC 7636).
 */
final class OAuthUris {

  /** RFC 7636 §4.1: 43–128 unreserved characters. */
  private static final Pattern VERIFIER = Pattern.compile("[A-Za-z0-9._~-]{43,128}");

  /** An S256 challenge: base64url of 32 bytes, no padding. */
  private static final Pattern CHALLENGE = Pattern.compile("[A-Za-z0-9_-]{43}");

  private static final Set<String> LOOPBACK_HOSTS = Set.of("127.0.0.1", "localhost", "[::1]", "::1");

  private OAuthUris() {}

  /**
   * The canonical form of a resource indicator: scheme and host in lower case, no default port, no trailing slash,
   * no fragment, no query; empty when it isn't an absolute http(s) URI.
   */
  static Optional<String> canonicalResource(String resource) {
    if (resource == null || resource.isBlank() || resource.length() > 512) {
      return Optional.empty();
    }
    try {
      URI u = new URI(resource.strip());
      String scheme = u.getScheme() == null ? null : u.getScheme().toLowerCase(Locale.ROOT);
      if (!"https".equals(scheme) && !"http".equals(scheme) || u.getRawAuthority() == null || u.getHost() == null
          || u.getRawUserInfo() != null || u.getRawQuery() != null || u.getRawFragment() != null) {
        return Optional.empty();
      }
      String host = u.getHost().toLowerCase(Locale.ROOT);
      int port = u.getPort();
      boolean defaultPort = port == -1 || "https".equals(scheme) && port == 443 || "http".equals(scheme) && port == 80;
      String path = u.getRawPath() == null ? "" : u.getRawPath();
      while (path.endsWith("/")) {
        path = path.substring(0, path.length() - 1);
      }
      return Optional.of(scheme + "://" + host + (defaultPort ? "" : ":" + port) + path);
    } catch (URISyntaxException e) {
      return Optional.empty();
    }
  }

  /**
   * A Client ID Metadata Document URL as accepted (report 02 §6.5): https, a host, a path other than {@code /}, no
   * port other than 443, no userinfo, query, fragment or dot segments.
   */
  static Optional<URI> clientIdUrl(String clientId) {
    return clientIdUrl(clientId, false);
  }

  /** {@link #clientIdUrl(String)}; {@code anyPort} only for the loopback document server of a test. */
  static Optional<URI> clientIdUrl(String clientId, boolean anyPort) {
    if (clientId == null || clientId.length() > 512 || !clientId.startsWith("https://")) {
      return Optional.empty();
    }
    try {
      URI u = new URI(clientId);
      String path = u.getRawPath();
      if (u.getHost() == null || u.getRawUserInfo() != null || u.getRawQuery() != null || u.getRawFragment() != null
          || path == null || path.length() < 2 || path.contains("/./") || path.contains("/../")
          || path.endsWith("/.") || path.endsWith("/..") || path.contains("%2e") || path.contains("%2E")
          || !anyPort && u.getPort() != -1 && u.getPort() != 443) {
        return Optional.empty();
      }
      return Optional.of(u);
    } catch (URISyntaxException e) {
      return Optional.empty();
    }
  }

  /** Whether {@code uri} is a loopback {@code http} redirect (RFC 8252 §7.3). */
  static boolean loopback(String uri) {
    try {
      URI u = new URI(uri);
      return "http".equalsIgnoreCase(u.getScheme()) && u.getHost() != null
          && LOOPBACK_HOSTS.contains(u.getHost().toLowerCase(Locale.ROOT)) && u.getRawUserInfo() == null
          && u.getRawFragment() == null;
    } catch (URISyntaxException e) {
      return false;
    }
  }

  /**
   * Whether the requested redirect equals a registered one: byte for byte, except that a loopback registration
   * matches the same URI on any port (native apps pick a free one at run time, RFC 8252 §7.3).
   */
  static boolean redirectMatches(String registered, String requested) {
    if (registered.equals(requested)) {
      return true;
    }
    if (!loopback(registered) || !loopback(requested)) {
      return false;
    }
    try {
      URI a = new URI(registered);
      URI b = new URI(requested);
      return a.getHost().equalsIgnoreCase(b.getHost()) && java.util.Objects.equals(a.getRawPath(), b.getRawPath())
          && java.util.Objects.equals(a.getRawQuery(), b.getRawQuery());
    } catch (URISyntaxException e) {
      return false;
    }
  }

  static boolean validChallenge(String challenge) {
    return challenge != null && CHALLENGE.matcher(challenge).matches();
  }

  /** RFC 7636 §4.6: {@code BASE64URL(SHA256(ASCII(code_verifier))) == code_challenge}, compared in constant time. */
  static boolean pkceS256(String verifier, String challenge) {
    if (verifier == null || challenge == null || !VERIFIER.matcher(verifier).matches()) {
      return false;
    }
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
      byte[] expected = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
          .getBytes(StandardCharsets.US_ASCII);
      return MessageDigest.isEqual(expected, challenge.getBytes(StandardCharsets.US_ASCII));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The host of a URI for people ("claude.ai"), or the URI itself when it has none (a custom scheme). */
  static String hostOf(String uri) {
    try {
      URI u = new URI(uri);
      return u.getHost() != null ? u.getHost().toLowerCase(Locale.ROOT) : u.getScheme() + ":";
    } catch (URISyntaxException e) {
      return uri;
    }
  }

  /** {@code application/x-www-form-urlencoded} encoding of one query value. */
  static String q(String value) {
    return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  /** {@code base} with query parameters appended (pairs of name, value; null values left out). */
  static String withQuery(String base, String... pairs) {
    StringBuilder out = new StringBuilder(base);
    char sep = base.contains("?") ? '&' : '?';
    for (int i = 0; i + 1 < pairs.length; i += 2) {
      if (pairs[i + 1] == null) {
        continue;
      }
      out.append(sep).append(pairs[i]).append('=').append(q(pairs[i + 1]));
      sep = '&';
    }
    return out.toString();
  }
}
