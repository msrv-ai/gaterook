package ai.msrv.gaterook.connectors.http;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Header rules of the HTTP connector (report 10 §6.3, §3.3): which request headers an agent may send, what a valid
 * name and value are, and which response headers reach the agent.
 */
final class Headers {

  /** An HTTP token (RFC 9110 §5.6.2), at most 64 characters. */
  private static final Pattern NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,64}");

  /**
   * Set by the HTTP layer or the gateway, or trusted by internal APIs as a statement about the caller — its address
   * ({@code X-Real-IP}, {@code True-Client-IP}…), its method ({@code X-HTTP-Method-Override}) or the path it asked for
   * ({@code X-Original-URL}, {@code X-Rewrite-URL}: IIS and Symfony route by them, past the allowed paths).
   * Accept-Encoding too: with it OkHttp no longer decompresses, so the size limit would count compressed bytes and the
   * agent would get gzip as "binary".
   */
  private static final Set<String> FORBIDDEN = Set.of("host", "content-length", "transfer-encoding", "connection",
      "keep-alive", "upgrade", "te", "trailer", "cookie", "cookie2", "authorization", "forwarded", "x-real-ip",
      "accept-encoding", "content-encoding", "content-type", "expect", "user-agent", "via", "max-forwards",
      "x-http-method-override", "x-http-method", "x-method-override", "x-original-url", "x-rewrite-url",
      "x-override-url", "x-host", "x-forwarded", "x-client-ip", "true-client-ip", "cf-connecting-ip",
      "fastly-client-ip", "x-cluster-client-ip", "x-originating-ip", "x-remote-ip", "x-remote-addr", "client-ip");
  private static final List<String> FORBIDDEN_PREFIXES = List.of("proxy-", "x-forwarded-", "sec-");

  /** Response headers an agent sees (report 10 §3.3); everything else, {@code Set-Cookie} first, is dropped. */
  private static final Set<String> RESPONSE = Set.of("content-type", "content-length", "location", "etag",
      "last-modified", "cache-control", "retry-after", "link", "allow", "www-authenticate", "x-request-id",
      "x-correlation-id", "content-language", "content-range", "accept-ranges", "date");
  private static final List<String> RESPONSE_PREFIXES = List.of("ratelimit-", "x-ratelimit-", "x-rate-limit-");

  static final int MAX_HEADERS = 20;
  static final int MAX_VALUE = 1_024;
  static final int MAX_TOTAL = 8 * 1_024;

  private Headers() {}

  static boolean validName(String name) {
    return name != null && NAME.matcher(name).matches();
  }

  /** No line break, NUL or other control character (a tab is fine), at most {@link #MAX_VALUE} characters. */
  static boolean validValue(String value) {
    if (value == null || value.length() > MAX_VALUE) {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if ((c < 0x20 && c != '\t') || c == 0x7F) {
        return false;
      }
    }
    return true;
  }

  static boolean isForbidden(String name) {
    String lower = name.toLowerCase(Locale.ROOT);
    if (FORBIDDEN.contains(lower)) {
      return true;
    }
    for (String prefix : FORBIDDEN_PREFIXES) {
      if (lower.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  static boolean shownToAgent(String lowerName) {
    if (RESPONSE.contains(lowerName)) {
      return true;
    }
    for (String prefix : RESPONSE_PREFIXES) {
      if (lowerName.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }
}
