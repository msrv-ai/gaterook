package ai.msrv.gaterook.pluginhost;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.plugin.protocol.Messages;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code ExecContext.egress()} inside the plugin host (ARCHITECTURE.md §3, §9 SSRF): HTTP to the hosts the manifest's
 * {@code access.hosts} names for this connection and nothing else. HTTPS only, except to a private address the
 * environment's policy allows for the connection (a service on the local network, a test server); no redirects (a
 * redirect would carry the plugin's credentials elsewhere), no proxy, never the core's own ports. Addresses are
 * checked again when the connection is made ({@link GuardedResolverProvider}), so a name can't rebind past the check.
 *
 * <p>A declaration the plugin can sidestep with a client of its own — the boundary is the OS sandbox and the process
 * (report 16 §5) — but the client the SPI hands out never reaches what the policy closes.
 */
final class EgressHttpClient implements ExecContext.Egress {

  private static final Set<String> RESTRICTED = Set.of("host", "connection", "content-length", "expect", "upgrade",
      "keep-alive", "transfer-encoding", "te", "trailer");
  private static final Duration MAX_TIMEOUT = Duration.ofSeconds(60);

  private static final HttpClient CLIENT = HttpClient.newBuilder()
      .followRedirects(HttpClient.Redirect.NEVER)
      .proxy(HttpClient.Builder.NO_PROXY)
      .connectTimeout(Duration.ofSeconds(10))
      .build();

  private final Messages.EgressGrant grant;
  private final List<AddressRules.Cidr> allowedPrivate;

  EgressHttpClient(Messages.EgressGrant grant) {
    this.grant = grant;
    List<AddressRules.Cidr> ranges = new ArrayList<>();
    for (String r : grant.allowPrivate()) {
      ranges.add(AddressRules.Cidr.parse(r));
    }
    this.allowedPrivate = List.copyOf(ranges);
  }

  @Override
  public ExecContext.EgressResponse send(ExecContext.EgressRequest request, long maxBytes) throws ConnectorException {
    URI uri = request.uri();
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!scheme.equals("https") && !scheme.equals("http")) {
      throw ConnectorException.denied("Only HTTPS requests are allowed, not " + uri.getScheme());
    }
    String host = uri.getHost();
    if (host == null || host.isBlank()) {
      throw ConnectorException.badInput("The request has no host");
    }
    host = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    int port = uri.getPort() >= 0 ? uri.getPort() : scheme.equals("https") ? 443 : 80;
    if (!granted(host, port)) {
      throw ConnectorException.denied("This plugin may only reach " + String.join(", ", grant.hosts())
          + " for this connection, not " + host + ":" + port);
    }
    try (AutoCloseable ignored = GuardedResolverProvider.grant(host, allowedPrivate)) {
      InetAddress[] addresses;
      try {
        addresses = InetAddress.getAllByName(host);
      } catch (UnknownHostException e) {
        throw ConnectorException.unavailable(host + " can't be reached: " + e.getMessage(), null);
      }
      boolean allPrivate = true;
      for (InetAddress a : addresses) {
        if (!AddressRules.allowed(a, allowedPrivate)) {
          throw ConnectorException.denied(host + " resolves to " + a.getHostAddress() + ", which this plugin may not "
              + "reach. A private address needs http.allowedPrivateRanges in the environment's policy.");
        }
        if (a.isLoopbackAddress() && grant.corePorts().contains(port)) {
          throw ConnectorException.denied("MCP Gateway's own port " + port + " is never reachable from a plugin");
        }
        allPrivate &= AddressRules.privateAddress(a);
      }
      if (scheme.equals("http") && !allPrivate) {
        throw ConnectorException.denied("Plain HTTP goes only to an allowed private address; use HTTPS for " + host);
      }
      return exchange(request, maxBytes);
    } catch (ConnectorException e) {
      throw e;
    } catch (Exception e) {
      throw ConnectorException.unavailable("The request failed: " + e.getMessage(), e);
    }
  }

  private boolean granted(String host, int port) {
    for (String entry : grant.hosts()) {
      int colon = entry.lastIndexOf(':');
      String h = colon < 0 ? entry : entry.substring(0, colon);
      String p = colon < 0 ? null : entry.substring(colon + 1);
      if (h.startsWith("[") && h.endsWith("]")) {
        h = h.substring(1, h.length() - 1);
      }
      if (h.equalsIgnoreCase(host) && (p == null || p.equals("*") || p.equals(String.valueOf(port)))) {
        return true;
      }
    }
    return false;
  }

  private static ExecContext.EgressResponse exchange(ExecContext.EgressRequest request, long maxBytes)
      throws ConnectorException {
    Duration timeout = request.timeout() == null || request.timeout().compareTo(MAX_TIMEOUT) > 0 ? MAX_TIMEOUT
        : request.timeout();
    HttpRequest.BodyPublisher body = request.body() == null ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofByteArray(request.body());
    HttpRequest.Builder b = HttpRequest.newBuilder(request.uri()).timeout(timeout)
        .method(request.method().toUpperCase(Locale.ROOT), body);
    if (request.headers() != null) {
      request.headers().forEach((name, values) -> {
        if (!RESTRICTED.contains(name.toLowerCase(Locale.ROOT))) {
          values.forEach(v -> b.header(name, v));
        }
      });
    }
    try {
      HttpResponse<InputStream> response = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
      byte[] bytes;
      boolean truncated;
      try (InputStream in = response.body()) {
        int cap = (int) Math.min(Integer.MAX_VALUE - 8, Math.max(0, maxBytes));
        bytes = in.readNBytes(cap);
        truncated = in.read() >= 0;
      }
      Map<String, List<String>> headers = new LinkedHashMap<>(response.headers().map());
      return new ExecContext.EgressResponse(response.statusCode(), Map.copyOf(headers), bytes, truncated);
    } catch (HttpConnectTimeoutException e) {
      throw ConnectorException.unavailable(request.uri().getHost() + " didn't accept the connection in time", e);
    } catch (HttpTimeoutException e) {
      throw ConnectorException.timeout(request.uri().getHost() + " didn't answer in " + timeout.toSeconds() + " s");
    } catch (IOException e) {
      throw ConnectorException.unavailable(request.uri().getHost() + " can't be reached: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ConnectorException.timeout("The call was cancelled");
    }
  }
}
