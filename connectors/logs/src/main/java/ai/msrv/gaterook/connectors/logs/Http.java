package ai.msrv.gaterook.connectors.logs;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connectors.support.OutputSanitizer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import javax.net.ssl.SSLException;

/**
 * The HTTP side of Loki, Cloud Logging and OpenSearch on {@code java.net.http} (ARCHITECTURE.md §1 table, §9 "SSRF":
 * the person typed the address, so it is checked before the connection, not by the egress guard). Every request runs
 * against the call's deadline; answers are read up to a size cap; redirects are never followed (a token must not
 * travel to another host); the reason a request failed is told in words the agent and the person can act on.
 */
final class Http {

  /** Largest answer a backend may give one request: 1,000 entries of the longest Loki lines stay below it. */
  static final long MAX_BODY = 32L * 1024 * 1024;
  /** Time kept back from the deadline for building the answer. */
  static final Duration MARGIN = Duration.ofMillis(150);
  /** No single request waits longer than this, whatever the deadline. */
  static final Duration MAX_REQUEST = Duration.ofSeconds(30);

  private Http() {}

  /** One answer, its body decoded as UTF-8. */
  record Response(int status, String body, String contentType) {
    boolean ok() {
      return status / 100 == 2;
    }
  }

  /** A client for one backend: HTTP/1.1, no proxy, no redirects, a private CA when the connection names one. */
  static HttpClient client(javax.net.ssl.SSLContext ssl) {
    HttpClient.Builder b = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NEVER)
        .proxy(HttpClient.Builder.NO_PROXY)
        .connectTimeout(Duration.ofSeconds(5));
    if (ssl != null) {
      b.sslContext(ssl);
    }
    return b.build();
  }

  /**
   * Sends {@code request} with the time the call has left.
   *
   * @throws ConnectorException {@code TIMEOUT} at the deadline, {@code RESOURCE_UNAVAILABLE} when the host can't be
   *                            reached or the TLS handshake fails, {@code RESOURCE_ERROR} for an oversized answer
   */
  static Response send(HttpClient client, HttpRequest.Builder request, ExecContext ctx, String what)
      throws ConnectorException {
    Duration left = ctx.remaining().minus(MARGIN);
    if (left.isNegative() || left.isZero()) {
      throw ConnectorException.timeout(what + " didn't answer in time.");
    }
    HttpRequest built = request.timeout(left.compareTo(MAX_REQUEST) > 0 ? MAX_REQUEST : left).build();
    try {
      HttpResponse<InputStream> r = client.send(built, HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream in = r.body()) {
        byte[] body = read(in, what);
        return new Response(r.statusCode(), new String(body, StandardCharsets.UTF_8),
            r.headers().firstValue("Content-Type").orElse(""));
      }
    } catch (HttpConnectTimeoutException e) {
      throw ConnectorException.unavailable("Can't connect to " + host(built.uri()) + " (timed out). Check the URL "
          + "and that this computer can reach it.", e);
    } catch (HttpTimeoutException e) {
      throw ConnectorException.timeout(what + " didn't answer in time.");
    } catch (ConnectException e) {
      throw ConnectorException.unavailable("Can't connect to " + host(built.uri()) + ": " + reason(e)
          + ". Check the URL and that the service is running.", e);
    } catch (SSLException e) {
      throw ConnectorException.unavailable("The TLS handshake with " + host(built.uri()) + " failed: " + reason(e)
          + ". If it uses a private CA, paste the CA certificate in the connection.", e);
    } catch (IOException e) {
      throw ConnectorException.unavailable(what + " failed: " + reason(e), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ConnectorException.timeout(what + " was cancelled.");
    }
  }

  /** The {@code Authorization} value of HTTP auth: {@code Bearer …}, {@code Basic …}, {@code ApiKey …}. */
  static String authorization(Settings.HttpAuth auth, SecretResolver secrets) throws ConnectorException {
    if (auth.secretField() == null) {
      return null;
    }
    if (!secrets.isSet(auth.secretField())) {
      throw ConnectorException.unavailable("The connection has no stored token or password: set it under "
          + "Credentials.", null);
    }
    try (SecretResolver.Secret secret = secrets.secret(auth.secretField())) {
      return switch (auth.mode()) {
        case BEARER -> "Bearer " + secret.unsafeString();
        case API_KEY -> "ApiKey " + secret.unsafeString();
        case BASIC -> "Basic " + Base64.getEncoder().encodeToString((auth.username() + ":" + secret.unsafeString())
            .getBytes(StandardCharsets.UTF_8));
        default -> null;
      };
    }
  }

  /** The first line of an error body, sanitized and short: what a backend said, for the agent. */
  static String excerpt(String body) {
    if (body == null || body.isBlank()) {
      return "";
    }
    String text = OutputSanitizer.sanitize(body.strip());
    return text.length() <= 500 ? text : text.substring(0, 499) + "…";
  }

  static String host(URI uri) {
    return uri.getPort() < 0 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
  }

  private static byte[] read(InputStream in, String what) throws IOException, ConnectorException {
    ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
    byte[] buf = new byte[16384];
    long total = 0;
    int n;
    while ((n = in.read(buf)) > 0) {
      total += n;
      if (total > MAX_BODY) {
        throw ConnectorException.resourceError(what + " answered with more than " + (MAX_BODY >> 20) + " MB. Lower "
            + "limit or narrow the query.", null);
      }
      out.write(buf, 0, n);
    }
    return out.toByteArray();
  }

  private static String reason(Throwable e) {
    String m = e.getMessage();
    return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
  }
}
