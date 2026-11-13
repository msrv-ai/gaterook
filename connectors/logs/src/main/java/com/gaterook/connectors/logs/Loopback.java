package com.gaterook.connectors.logs;

import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connector.api.Suggestions;
import com.gaterook.connectors.support.LocalPorts;
import com.gaterook.connectors.support.egress.AddressPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.SSLContext;

/**
 * The setup helpers' look at a server on this computer (Loki, OpenSearch or Elasticsearch in Docker, say): a TCP
 * connection, a TLS hello and one GET without credentials to a loopback address. No credential is sent, no other host
 * is reached, the core's own listeners are never asked ({@link BackendAddresses}), and every step has a short timeout
 * so the form doesn't wait.
 */
final class Loopback {

  /** One answer to a GET: its status and the start of its body. */
  record Answer(int status, String body) {}

  /** How long one loopback step may take; a server on this computer answers in milliseconds. */
  static final Duration TIMEOUT = Duration.ofMillis(600);
  /** How much of an answer is read. */
  static final int MAX_BODY = 64 * 1024;

  private Loopback() {}

  /** Whether {@code host} names this computer: {@code localhost}, an address of {@code 127.0.0.0/8} or {@code ::1}. */
  static boolean host(String host) {
    if (host == null || host.isBlank()) {
      return false;
    }
    String h = host.toLowerCase(Locale.ROOT);
    return h.equals("localhost") || AddressPolicy.canonicalIp(h).map(InetAddress::isLoopbackAddress).orElse(false);
  }

  /**
   * {@code http://localhost:<port>} or {@code https://localhost:<port>} for {@code pointer} when a server answers on
   * {@code port} of this computer, the scheme as a TLS hello found it. Never filled in: a person setting up a remote
   * server picks it only on purpose.
   */
  static void offer(Suggestions.Builder out, String pointer, String what, int port, ExecContext ctx) {
    if (ctx.remaining().compareTo(TIMEOUT.multipliedBy(2)) < 0 || !LocalPorts.listening(port)) {
      return;
    }
    Tls.Transport transport = Tls.probe("localhost", addresses("localhost"), port, null, TIMEOUT);
    if (transport == Tls.Transport.UNREACHABLE) {
      return;
    }
    boolean tls = transport != Tls.Transport.PLAIN;
    out.options(pointer, "this computer", List.of(new Suggestions.Option((tls ? "https" : "http") + "://localhost:"
        + port, null, what + " answers on port " + port + (tls ? " with TLS" : " without TLS"))));
  }

  /**
   * One GET of {@code path} to {@code base}, a server on this computer, without credentials ({@code headers} name
   * nothing secret: a tenant). The URL's scheme is checked against what the server speaks: an {@code https://} address
   * gets a TLS hello first and is asked only when its certificate verifies; an {@code http://} one that doesn't answer
   * is tried with a TLS hello. A certificate that doesn't verify, a scheme the server doesn't speak (the URL with the
   * other one is then offered for {@code pointer}) or nothing answering ends in a note.
   *
   * @param ca      the connection's CA certificate (PEM), or {@code null}
   * @param backend {@code loki}, {@code opensearch} or {@code elasticsearch}, for where its CA is kept
   * @return the answer, or {@code null} when it ended in a note
   */
  static Answer ask(URI base, String path, Map<String, String> headers, String ca, String backend, String pointer,
      ExecContext ctx, Suggestions.Builder out) {
    String address = Settings.hostPort(base);
    String host = bare(base.getHost());
    boolean https = "https".equals(base.getScheme());
    int port = base.getPort() >= 0 ? base.getPort() : https ? 443 : 80;
    String other = (https ? "http" : "https") + base.toString().substring(base.getScheme().length());
    try {
      BackendAddresses.of(ctx).check(base);
    } catch (ConnectorException e) {
      out.note(e.getMessage());
      return null;
    }
    if (ctx.remaining().compareTo(TIMEOUT.multipliedBy(3)) < 0) {
      return null;
    }
    SSLContext ssl = null;
    if (https) {
      if (ca != null) {
        try {
          ssl = Clients.sslContext(ca);
        } catch (ConnectorException e) {
          out.note(e.getMessage());
          return null;
        }
      }
      switch (Tls.probe(host, addresses(host), port, ssl, TIMEOUT)) {
        case VERIFIED -> { }
        case UNTRUSTED -> {
          out.note(Tls.untrustedNote(address, backend, ca != null));
          return null;
        }
        case WRONG_NAME -> {
          out.note(Tls.wrongNameNote(address, host));
          return null;
        }
        case OTHER -> {
          out.note(address + " speaks TLS but refused the handshake.");
          return null;
        }
        case PLAIN -> {
          out.options(pointer, "a TLS check of " + address, List.of(Suggestions.Option.of(other)));
          out.note(address + " answers without TLS: use " + other + ".");
          return null;
        }
        default -> {
          out.note("Nothing answers on " + address + ". Start the server or check the port.");
          return null;
        }
      }
    }
    try {
      return get(URI.create(base + path), headers, ssl, ctx);
    } catch (ConnectorException e) {
      if (!https) {
        Tls.Transport transport = Tls.probe(host, addresses(host), port, null, TIMEOUT);
        if (transport == Tls.Transport.UNREACHABLE) {
          out.note("Nothing answers on " + address + ". Start the server or check the port.");
          return null;
        }
        if (transport != Tls.Transport.PLAIN) {
          out.options(pointer, "a TLS check of " + address, List.of(Suggestions.Option.of(other)));
          out.note(address + " speaks TLS: use " + other + (transport == Tls.Transport.UNTRUSTED ? ", and paste the "
              + "CA certificate (PEM) that signed its certificate under Advanced settings." + Tls.where(backend)
              : "."));
          return null;
        }
      }
      out.note(e.getMessage());
      return null;
    }
  }

  /** One GET without credentials, within {@link #TIMEOUT}; redirects are not followed. */
  private static Answer get(URI uri, Map<String, String> headers, SSLContext ssl, ExecContext ctx)
      throws ConnectorException {
    BackendAddresses.of(ctx).check(uri);
    Duration left = ctx.remaining().minus(Http.MARGIN);
    Duration timeout = left.compareTo(TIMEOUT) < 0 ? left : TIMEOUT;
    if (timeout.isNegative() || timeout.isZero()) {
      throw ConnectorException.timeout("No time was left to ask " + Http.host(uri) + ".");
    }
    HttpClient.Builder builder = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NEVER).proxy(HttpClient.Builder.NO_PROXY).connectTimeout(timeout);
    if (ssl != null) {
      builder.sslContext(ssl);
    }
    HttpClient client = builder.build();
    try {
      HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/json")
          .GET();
      headers.forEach(request::header);
      HttpResponse<InputStream> r = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream in = r.body()) {
        return new Answer(r.statusCode(), new String(in.readNBytes(MAX_BODY), StandardCharsets.UTF_8));
      }
    } catch (IOException e) {
      throw ConnectorException.unavailable(Http.host(uri) + " didn't answer: " + (e.getMessage() == null
          ? e.getClass().getSimpleName() : e.getMessage()), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ConnectorException.timeout("Asking " + Http.host(uri) + " was cancelled.");
    } finally {
      client.shutdownNow();
    }
  }

  /** The loopback addresses of a host that names this computer; no other address is ever tried. */
  static List<InetAddress> addresses(String host) {
    List<InetAddress> out = new ArrayList<>();
    try {
      for (InetAddress a : InetAddress.getAllByName(bare(host))) {
        if (a.isLoopbackAddress()) {
          out.add(a);
        }
      }
    } catch (UnknownHostException | SecurityException e) {
      // Nothing to try.
    }
    return out;
  }

  private static String bare(String host) {
    return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
  }
}
