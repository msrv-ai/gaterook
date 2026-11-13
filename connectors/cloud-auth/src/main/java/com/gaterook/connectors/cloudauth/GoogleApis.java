package com.gaterook.connectors.cloudauth;

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
 *
 * <p>The Firebase Realtime Database answers under two domains of its own, which {@link #host} doesn't accept: a
 * database connection asks {@link #realtimeDatabase} instead, which accepts nothing else.
 */
public final class GoogleApis {

  /** One or more DNS labels, then {@code googleapis.com}. */
  private static final Pattern HOST = Pattern.compile(
      "^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+googleapis\\.com$");

  /** One DNS label: the name of a Realtime Database instance, such as {@code acme-default-rtdb}. */
  private static final String DATABASE = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?";

  /** A Google Cloud region as the second label of a {@code firebasedatabase.app} host: {@code europe-west1}. */
  private static final String REGION = "[a-z]{2,20}-[a-z]{2,20}[0-9]{1,2}";

  /** {@code <database>.firebaseio.com} or {@code <database>.<region>.firebasedatabase.app}, and nothing else. */
  private static final Pattern REALTIME_DATABASE = Pattern.compile(
      "^(?:" + DATABASE + "\\.firebaseio\\.com|" + DATABASE + "\\." + REGION + "\\.firebasedatabase\\.app)$");

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

  /**
   * Whether {@code url} is HTTPS on a Firebase Realtime Database host, on port 443, without user info: exactly one
   * database label under {@code firebaseio.com} ({@code https://acme-default-rtdb.firebaseio.com}), or a database label
   * and a region label under {@code firebasedatabase.app} ({@code https://acme.europe-west1.firebasedatabase.app}).
   * Google serves the databases of these two domains itself and runs nobody's code there. Firebase Hosting and the
   * other places where people deploy code ({@code web.app}, {@code firebaseapp.com}, a custom domain) never match, nor
   * does a deeper or shallower name under the two domains, nor any host of {@link #host}.
   */
  public static boolean realtimeDatabase(URI url) {
    if (url == null || !"https".equalsIgnoreCase(url.getScheme()) || url.getRawUserInfo() != null) {
      return false;
    }
    String host = url.getHost();
    int port = url.getPort();
    return host != null && (port == -1 || port == 443)
        && REALTIME_DATABASE.matcher(host.toLowerCase(Locale.ROOT)).matches();
  }
}
