package ai.msrv.gaterook.connectors.support.egress;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import okhttp3.HttpUrl;

/**
 * URL rules of the egress guard (report 10 §6.2, all rows checked against {@code HttpUrl} of OkHttp 5.5.1). Base URLs
 * come from people (connection config, policy); paths come from agents. A path is never concatenated as text:
 * {@code //evil.com/x} and {@code \\evil.com/x} would make OkHttp go to <em>evil.com</em>, and a string prefix check
 * would let {@code https://billing.stage.acme.dev.evil.com/v1/} through as "starting with" the base URL.
 */
public final class UrlRules {

  /** Percent-escapes a server may decode into a separator or a control character: {@code %2F %5C %00-%1F %7F}. */
  private static final Pattern DANGEROUS_ESCAPE = Pattern.compile("%(2[fF]|5[cC]|[01][0-9a-fA-F]|7[fF])");
  private static final Pattern DOT_SEGMENT = Pattern.compile("(?i)(\\.|%2e){1,2}");
  /** Where a segment's path parameters start, as written or percent-encoded. */
  private static final Pattern PARAMETERS = Pattern.compile("(?i);|%3b");

  private UrlRules() {}

  /** A URL refused by the rules, with a text for the person or the agent. */
  public static final class UrlRejectedException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public UrlRejectedException(String message) {
      super(message);
    }
  }

  /**
   * Parses a URL a person configured ({@code baseUrls}, a spec or plugin URL): http or https, a canonical host, no
   * user info, no fragment.
   *
   * @throws UrlRejectedException with the reason
   */
  public static HttpUrl parse(String url) {
    if (url == null || url.isBlank()) {
      throw new UrlRejectedException("The URL is empty");
    }
    if (url.chars().anyMatch(c -> c < 0x21 || c == 0x7F)) {
      throw new UrlRejectedException("The URL contains spaces or control characters");
    }
    HttpUrl parsed = HttpUrl.parse(url);
    if (parsed == null) {
      throw new UrlRejectedException("Not an http(s) URL: " + url);
    }
    if (!parsed.username().isEmpty() || !parsed.password().isEmpty() || hasUserInfo(url)) {
      throw new UrlRejectedException("Put credentials under Credentials, not in the URL");
    }
    if (parsed.fragment() != null) {
      throw new UrlRejectedException("The URL can't have a #fragment");
    }
    // The host as written and as OkHttp read it: "https://127.1\x/" is host 127.1 to OkHttp (a backslash ends the
    // authority), and full-width digits become ASCII ones on the way to the host.
    for (String h : new String[] {hostOf(url), parsed.host()}) {
      Optional<AddressPolicy.Verdict> host = AddressPolicy.checkHostSyntax(h);
      if (host.isPresent()) {
        throw new UrlRejectedException(host.get().text());
      }
    }
    checkEscapes(parsed.encodedPath());
    for (String segment : parsed.encodedPathSegments()) {
      if (dotSegment(segment)) {
        throw new UrlRejectedException("The URL's path can't contain . or .. segments, also with ;parameters");
      }
    }
    return parsed;
  }

  /**
   * Resolves a path an agent gave against a base URL, after the path rules: exactly one leading {@code /}, no
   * backslash, no dot segments in any spelling (also with {@code ;parameters}: {@code ..;x}), no escapes a server could
   * decode into a separator or a control character. The base path is kept: {@code /refunds} on {@code https://api/v1}
   * is {@code https://api/v1/refunds}.
   * A {@code ?query} is allowed; a {@code #fragment} is dropped.
   *
   * @throws UrlRejectedException with the reason
   */
  public static HttpUrl resolve(HttpUrl base, String path) {
    if (path == null || !path.startsWith("/")) {
      throw new UrlRejectedException("The path must start with /");
    }
    if (path.startsWith("//") || path.indexOf('\\') >= 0) {
      throw new UrlRejectedException("The path can't start with // or contain \\");
    }
    if (path.chars().anyMatch(c -> c < 0x20 || c == 0x7F || c == ' ')) {
      throw new UrlRejectedException("The path contains spaces or control characters; encode them");
    }
    int hash = path.indexOf('#');
    String withoutFragment = hash < 0 ? path : path.substring(0, hash);
    int question = withoutFragment.indexOf('?');
    String rawPath = question < 0 ? withoutFragment : withoutFragment.substring(0, question);
    String query = question < 0 ? null : withoutFragment.substring(question + 1);
    checkEscapes(rawPath);
    for (String segment : rawPath.split("/", -1)) {
      if (dotSegment(segment)) {
        throw new UrlRejectedException("The path can't contain . or .. segments, also with ;parameters");
      }
    }
    String basePath = base.encodedPath();
    String joined = (basePath.endsWith("/") ? basePath.substring(0, basePath.length() - 1) : basePath) + rawPath;
    HttpUrl.Builder builder = base.newBuilder().encodedPath(joined).encodedQuery(query).fragment(null);
    HttpUrl resolved = builder.build();
    if (!within(base, resolved)) {
      throw new UrlRejectedException("The path leaves the base URL " + base);
    }
    return resolved;
  }

  /**
   * Whether {@code target} lies under {@code base}: same scheme, host (case-insensitive, no trailing dot) and effective
   * port, and the path continues the base path at a segment boundary ({@code /v1} covers {@code /v1} and
   * {@code /v1/x}, not {@code /v10}).
   */
  public static boolean within(HttpUrl base, HttpUrl target) {
    if (!base.scheme().equals(target.scheme()) || base.port() != target.port()
        || !normalizeHost(base.host()).equals(normalizeHost(target.host()))) {
      return false;
    }
    String basePath = base.encodedPath();
    String path = target.encodedPath();
    if (basePath.endsWith("/")) {
      return path.startsWith(basePath) || path.equals(basePath.substring(0, basePath.length() - 1));
    }
    return path.equals(basePath) || path.startsWith(basePath + "/");
  }

  /**
   * Whether a raw path segment is {@code .} or {@code ..} in any spelling once its path parameters are cut off:
   * servlet containers (Tomcat, Jetty, Spring) drop {@code ;…} before they resolve dot segments, so {@code ..;x} is
   * {@code ..} to them — a path OkHttp and the prefix check keep under the base path would leave it on the server.
   */
  static boolean dotSegment(String rawSegment) {
    var parameters = PARAMETERS.matcher(rawSegment);
    String bare = parameters.find() ? rawSegment.substring(0, parameters.start()) : rawSegment;
    return DOT_SEGMENT.matcher(bare).matches();
  }

  private static void checkEscapes(String encodedPath) {
    if (DANGEROUS_ESCAPE.matcher(encodedPath).find()) {
      throw new UrlRejectedException("The path can't contain %2F, %5C or escaped control characters");
    }
  }

  private static boolean hasUserInfo(String url) {
    int scheme = url.indexOf("://");
    if (scheme < 0) {
      return false;
    }
    int start = scheme + 3;
    int end = start;
    while (end < url.length() && "/?#".indexOf(url.charAt(end)) < 0) {
      end++;
    }
    return url.substring(start, end).indexOf('@') >= 0;
  }

  /** The host as written in the URL (OkHttp's {@code host()} already canonicalized numeric forms). */
  private static String hostOf(String url) {
    int scheme = url.indexOf("://");
    int start = scheme < 0 ? 0 : scheme + 3;
    int end = start;
    if (end < url.length() && url.charAt(end) == '[') {
      int close = url.indexOf(']', end);
      return close < 0 ? url.substring(start) : url.substring(start, close + 1);
    }
    while (end < url.length() && "/?#:".indexOf(url.charAt(end)) < 0) {
      end++;
    }
    return url.substring(start, end);
  }

  private static String normalizeHost(String host) {
    String lower = host.toLowerCase(Locale.ROOT);
    return lower.endsWith(".") ? lower.substring(0, lower.length() - 1) : lower;
  }
}
