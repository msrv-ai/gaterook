package gaterook.plugins.clickhouse;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.Suggestions.Option;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.SSLException;
import tools.jackson.databind.JsonNode;

/**
 * Help for the ClickHouse form ({@link ai.msrv.gaterook.connector.api.Connector#suggest}). From the form's own values:
 * ClickHouse serves its HTTP interface with TLS on 8443 and without it on 8123, so TLS follows the port, and a
 * ClickHouse Cloud host takes 8443. Signed in: first {@code /ping}, which needs no credentials, says whether the server
 * speaks TLS on that port; only when TLS matches does the password go to the server, to say who signed in. A
 * certificate that fails is a note, never a reason to suggest plain HTTP. The database selection has its own discovery.
 */
final class ClickHouseSuggestions {

  static final String PORT = "/port";
  static final String TLS = "/options/tls";
  static final int HTTPS_PORT = 8443;
  static final int HTTP_PORT = 8123;
  static final Duration PING_TIMEOUT = Duration.ofSeconds(3);

  private ClickHouseSuggestions() {}

  static Suggestions suggest(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, Suggestions.Scope scope) {
    Suggestions.Builder out = Suggestions.builder();
    if (scope == Suggestions.Scope.SERVICE) {
      service(cfg, secrets, ctx, out);
    } else {
      local(cfg, out);
    }
    return out.build();
  }

  /** TLS as ClickHouse pairs it with the port; port 8443 for a ClickHouse Cloud host. */
  static void local(ConnectionConfig cfg, Suggestions.Builder out) {
    String host = cfg.string("/host");
    int port = cfg.integer(PORT, HTTPS_PORT);
    boolean tls = cfg.bool(TLS, true);
    if (host != null && host.strip().toLowerCase(Locale.ROOT).endsWith(".clickhouse.cloud")) {
      out.value(PORT, "ClickHouse Cloud", Integer.toString(HTTPS_PORT), List.of(new Option(
          Integer.toString(HTTPS_PORT), null, "ClickHouse Cloud serves HTTPS on 8443")));
      port = HTTPS_PORT;
    }
    if (port == HTTP_PORT && tls) {
      out.value(TLS, "the port", "false", List.of(new Option("false", "Off", "Port 8123 serves HTTP without TLS")));
    } else if (port == HTTPS_PORT && !tls) {
      out.value(TLS, "the port", "true", List.of(new Option("true", "On", "Port 8443 serves HTTPS")));
    }
  }

  /**
   * Whether TLS matches what the server speaks on the port, asked with {@code /ping} before any credential; then the
   * server's version and the signed-in user.
   */
  static void service(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, Suggestions.Builder out) {
    ClickHouseConfig config;
    try {
      config = ClickHouseConfig.of(cfg);
    } catch (ConnectorException e) {
      out.note(e.getMessage());
      return;
    }
    String where = config.host() + ":" + config.port();
    boolean speaksTls;
    try {
      speaksTls = speaksTls(config, ctx);
    } catch (ConnectorException e) {
      out.note(certificate(e) ? "ClickHouse at " + where + " speaks TLS, but this computer doesn't trust its "
          + "certificate." : e.getMessage());
      return;
    }
    if (speaksTls != config.tls()) {
      out.value(TLS, "the server", Boolean.toString(speaksTls), List.of(new Option(Boolean.toString(speaksTls),
          speaksTls ? "On" : "Off", speaksTls ? "The server speaks TLS on " + config.port()
              : "The server answers without TLS on " + config.port())));
      out.note(speaksTls ? "ClickHouse speaks TLS on port " + config.port() + ": turn TLS on."
          : "ClickHouse answers without TLS on port " + config.port() + ": turn TLS off. Without TLS the plugin "
              + "reaches only a private address the environment's policy allows.");
      return;
    }
    ClickHouseClient ch = new ClickHouseClient(config, secrets, ctx);
    try {
      ClickHouseClient.Answer about = ch.query("SELECT version(), currentUser()", Map.of(), 1);
      if (!about.ok()) {
        out.note(about.failure().account() ? "ClickHouse rejected the user or the password."
            : "ClickHouse refused the query: " + about.failure().message());
        return;
      }
      JsonNode row = about.table().rows().path(0);
      out.note("Signed in to " + where + " as " + row.path(1).asString(config.user()) + " (ClickHouse "
          + row.path(0).asString("?") + "). Load the databases and choose the ones agents may use.");
    } catch (ConnectorException e) {
      out.note(e.getMessage());
    }
  }

  /**
   * Whether the server speaks TLS on the connection's port, from {@code /ping} (no credentials) over the connection's
   * scheme. When that fails as a mismatch of schemes would (a handshake the server ended or answered in plain HTTP, a
   * plain request a TLS port dropped), the other scheme is asked once, and only ClickHouse's own answer (or, over TLS, a
   * certificate this computer doesn't trust) says what the server speaks. A certificate, an alert or a timeout of a
   * TLS attempt is never a reason to try without TLS.
   *
   * @throws ConnectorException the server can't be reached, or its certificate fails: nothing is suggested then
   */
  static boolean speaksTls(ClickHouseConfig config, ExecContext ctx) throws ConnectorException {
    try {
      ping(config, config.tls(), ctx);
      return config.tls();
    } catch (ConnectorException first) {
      if (first.kind() == ConnectorException.Kind.DENIED || refusedOrTimedOut(first) || certificate(first)
          || (config.tls() && !mayBePlaintext(first))) {
        throw first;
      }
      try {
        ping(config, !config.tls(), ctx);
        return !config.tls();
      } catch (ConnectorException second) {
        if (!config.tls() && certificate(second)) {
          return true;
        }
        throw first;
      }
    }
  }

  /**
   * A TLS attempt that may have reached a server without TLS: the connection closed or reset during the handshake, or
   * bytes that aren't TLS came back. Never a certificate, an alert or a timeout.
   */
  static boolean mayBePlaintext(Throwable error) {
    boolean eligible = false;
    for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
      String m = String.valueOf(t.getMessage()).toLowerCase(Locale.ROOT);
      if (t instanceof CertificateException || t instanceof java.net.SocketTimeoutException
          || m.contains("certificate") || m.contains("pkix") || m.contains("fatal alert")
          || m.contains("protocol_version") || m.contains("handshake_failure")) {
        return false;
      }
      eligible |= t instanceof java.io.EOFException
          || (t instanceof java.net.SocketException && m.contains("connection reset"))
          || (t instanceof SSLException && (m.contains("unrecognized ssl message")
              || m.contains("terminated the handshake") || m.contains("peer shut down incorrectly")));
    }
    return eligible;
  }

  private static void ping(ClickHouseConfig config, boolean tls, ExecContext ctx) throws ConnectorException {
    URI uri = URI.create((tls ? "https" : "http") + "://" + config.host() + ":" + config.port() + "/ping");
    // A handshake or a ping takes milliseconds: a server silent for seconds won't answer this one either.
    Duration left = ctx.remaining().minusMillis(200);
    Duration timeout = left.compareTo(PING_TIMEOUT) > 0 ? PING_TIMEOUT
        : left.compareTo(Duration.ofMillis(100)) < 0 ? Duration.ofMillis(100) : left;
    ExecContext.EgressResponse r = ctx.egress().send(new ExecContext.EgressRequest("GET", uri, Map.of(), null,
        timeout), 1024);
    String body = r.body() == null ? "" : new String(r.body(), StandardCharsets.UTF_8).strip();
    if (r.status() != 200 || !body.startsWith("Ok.")) {
      throw ConnectorException.resourceError("The HTTP interface at " + config.host() + ":" + config.port()
          + " doesn't answer as ClickHouse does (HTTP " + r.status() + ").", null);
    }
  }

  /** A TLS handshake that failed on the server's certificate: the server does speak TLS. */
  static boolean certificate(Throwable error) {
    for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
      String m = String.valueOf(t.getMessage()).toLowerCase(Locale.ROOT);
      if (t instanceof CertificateException || m.contains("pkix") || m.contains("certificate")
          || m.contains("no subject alternative")) {
        return true;
      }
    }
    return false;
  }

  private static boolean refusedOrTimedOut(Throwable error) {
    for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
      if (t instanceof java.net.ConnectException || t instanceof java.net.http.HttpConnectTimeoutException
          || t instanceof java.net.http.HttpTimeoutException || t instanceof java.net.UnknownHostException) {
        return true;
      }
    }
    return error instanceof ConnectorException ce && ce.kind() == ConnectorException.Kind.TIMEOUT;
  }
}
