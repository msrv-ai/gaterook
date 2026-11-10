package ai.msrv.gaterook.connectors.cloudauth;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Where a Google access token may go (ARCHITECTURE.md §7.6): Google's own API front ends, the HTTPS hosts under
 * {@code googleapis.com} ({@code https://bigquery.googleapis.com/mcp}), on the default port and without user info.
 * Google answers every name under that domain itself. Code that other people deploy runs under other domains
 * ({@code run.app}, {@code cloudfunctions.net}, a custom domain), where a token with the {@code cloud-platform} scope
 * would reach whoever deployed it, so neither the name of an authorization server nor anything else a server says about
 * itself makes a host one of Google's.
 */
public final class GoogleApis {

  /** One or more DNS labels, then {@code googleapis.com}. */
  private static final Pattern HOST = Pattern.compile(
      "^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+googleapis\\.com$");

  private GoogleApis() {}

  /** Whether {@code url} is HTTPS on a host under {@code googleapis.com}, on port 443, without user info. */
  public static boolean host(URI url) {
    if (url == null || !"https".equalsIgnoreCase(url.getScheme()) || url.getRawUserInfo() != null) {
      return false;
    }
    String host = url.getHost();
    int port = url.getPort();
    return host != null && (port == -1 || port == 443) && HOST.matcher(host.toLowerCase(Locale.ROOT)).matches();
  }
}
